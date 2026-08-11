package com.helpinminutes.api.matching;

import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.geo.GeoProviderChain;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.uber.h3core.H3Core;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Offers a task to partners.
 *
 * <h2>Dispatch model</h2>
 *
 * Offers go out in <b>escalating waves</b>. Wave 0 covers the nearest tier with a
 * modest fanout; each unanswered offer window widens both the radius and the number
 * of partners contacted, until the widest configured tier is reached. Previously
 * there was a single hard 3km ceiling and a fanout of 5, so a job's entire lifetime
 * reached at most ~5–10 partners and in a thin-supply area it was simply cancelled
 * after five minutes.
 *
 * <p>Within a wave, all offers are simultaneous and first-to-tap wins. That is
 * deliberate: sequential offering adds a full round of latency per partner, and the
 * concurrency layers below make the race safe.
 *
 * <p>Push offers are only half the reach. Every online partner also sees SEARCHING
 * jobs in the much wider pull feed ({@code GET /tasks/available}), which costs them
 * nothing and costs us nothing per partner.
 *
 * <h2>Ranking</h2>
 *
 * By {@link CandidateScorer}: ETA-dominant, with acceptance rate, an
 * anti-starvation term and rating. Travel times come from one matrix call to the
 * routing provider (self-hosted OSRM), so ranking N candidates is one request, not
 * N. If routing is unavailable the scorer falls back to straight-line estimates and
 * ranking degrades to roughly distance order — never to a failed dispatch.
 *
 * <h2>Concurrency</h2>
 *
 * Four layers, unchanged and all still required: a {@code SELECT … FOR UPDATE} on
 * the task here, a unique {@code (task_id, helper_id)} index, the compare-and-set in
 * {@code TaskRepository.assignIfUnassigned}, and a per-partner row lock in
 * {@code acceptTask}.
 */
@Service
public class MatchingService {

  private static final List<TaskStatus> HELPER_ACTIVE_TASK_STATUSES = List.of(
      TaskStatus.ASSIGNED,
      TaskStatus.ARRIVED,
      TaskStatus.STARTED);

  /**
   * Ceiling on candidates pulled from the GEO index in one pass.
   *
   * <p>Two tiers: the cheap first pass, then a wider one only when the near window
   * turned out to be mostly busy or ineligible. Widening is common at launch
   * density, so the second limit has to stay bounded — it is also the cap on the
   * ETA matrix.
   */
  private static final int GEO_CANDIDATE_LIMIT = 120;
  private static final int GEO_CANDIDATE_LIMIT_WIDE = 400;

  /**
   * Ola's fallback Distance Matrix permits 50 pairs. Preselect by cheap
   * straight-line distance so OSRM and Ola always receive a bounded request and
   * every remaining candidate still receives the scorer's local ETA estimate.
   */
  private static final int ETA_MATRIX_CANDIDATE_LIMIT = 50;

  /**
   * Cap on H3 cells unioned in the cold-start fallback.
   *
   * <p>The bound belongs on cell count, not on k. Capping k (at 3) silently shrank
   * the fallback to a ~1.2km disk while the primary path searched 3km+; capping
   * cells lets k follow the requested radius and still keeps the SUNION small.
   */
  private static final int MAX_FALLBACK_H3_CELLS = 64;

  private final AppProperties props;
  private final H3Core h3;
  private final HelperPresenceService presence;
  private final TaskOfferRepository offers;
  private final TaskRepository tasks;
  private final RealtimePublisher realtime;
  private final NotificationQueueService notificationQueue;
  private final HelperProfileRepository helperProfiles;
  private final GeoProviderChain geo;
  private final CandidateScorer scorer;
  private final Executor realtimeDispatchExecutor;

  public MatchingService(
      AppProperties props,
      H3Core h3,
      HelperPresenceService presence,
      TaskOfferRepository offers,
      TaskRepository tasks,
      RealtimePublisher realtime,
      NotificationQueueService notificationQueue,
      HelperProfileRepository helperProfiles,
      GeoProviderChain geo,
      CandidateScorer scorer,
      @Qualifier("realtimeDispatchExecutor") Executor realtimeDispatchExecutor) {
    this.props = props;
    this.h3 = h3;
    this.presence = presence;
    this.offers = offers;
    this.tasks = tasks;
    this.realtime = realtime;
    this.notificationQueue = notificationQueue;
    this.helperProfiles = helperProfiles;
    this.geo = geo;
    this.scorer = scorer;
    this.realtimeDispatchExecutor = realtimeDispatchExecutor;
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MatchingService.class);

  @Transactional
  public List<UUID> dispatchOffers(TaskEntity task) {
    return dispatchOffers(task, true);
  }

  @Transactional
  public List<UUID> dispatchOffers(TaskEntity task, boolean sendPushNotifications) {
    task = tasks.findByIdForUpdate(task.getId()).orElse(null);
    if (task == null || task.getStatus() != TaskStatus.SEARCHING || task.getAssignedHelperId() != null) {
      return List.of();
    }

    int wave = Math.max(0, task.getDispatchWave());
    double radiusMeters = props.matching().radiusForWave(wave);
    int fanout = props.matching().fanoutForWave(wave);

    CandidatePool pool = findCandidatePool(task, radiusMeters, fanout);
    Map<UUID, HelperPresenceService.HelperState> nearbyStates = pool.states();
    Set<UUID> eligible = pool.eligible();

    Map<UUID, Double> distanceByHelper = new HashMap<>();
    Set<UUID> excludedHelperIds = excludedHelperIds(task.getId());
    for (UUID helperId : eligible) {
      if (excludedHelperIds.contains(helperId)) continue;
      var state = nearbyStates.get(helperId);
      if (!isEligibleOnlineHelper(state)) continue;
      double distanceMeters =
          GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng());
      if (distanceMeters <= radiusMeters) {
        distanceByHelper.merge(helperId, distanceMeters, Math::min);
      }
    }

    List<CandidateScorer.ScoredCandidate> ranked =
        rankCandidates(task, distanceByHelper, nearbyStates);
    List<CandidateScorer.ScoredCandidate> chosen = ranked.stream().limit(fanout).toList();

    log.info("Matching task {} wave {} - radius {}m, fanout {}, nearby {}, eligible {}, chosen {}",
        task.getId(), wave, (long) radiusMeters, fanout, nearbyStates.size(), eligible.size(),
        chosen.size());

    List<UUID> helperIds = writeOffers(task, chosen);

    // The wave advances even when nobody was found: the next attempt should search
    // wider, and the cleanup job's backoff keys off the same counter.
    task.setDispatchWave(wave + 1);
    task.setLastDispatchedAt(Instant.now());
    tasks.save(task);

    if (!helperIds.isEmpty()) {
      recordOffered(helperIds);
      if (sendPushNotifications) {
        notificationQueue.enqueueTaskOffered(helperIds, task);
      }
    }
    return helperIds;
  }

  /** Nearby online partners, plus the subset actually eligible for this task. */
  private record CandidatePool(
      Map<UUID, HelperPresenceService.HelperState> states, Set<UUID> eligible) {}

  /**
   * Finds online partners near the task, GEO index first and H3 as a cold fallback.
   *
   * <p>Returns the eligibility result alongside the states so the caller does not
   * recompute it — each eligibility pass is three database queries.
   */
  private CandidatePool findCandidatePool(TaskEntity task, double radiusMeters, int fanout) {
    Map<UUID, HelperPresenceService.HelperState> nearbyStates =
        new LinkedHashMap<>(presence.getNearbyActiveHelperStates(
            task.getLat(), task.getLng(), radiusMeters, GEO_CANDIDATE_LIMIT));

    if (!nearbyStates.isEmpty()) {
      Set<UUID> eligible = eligibleHelpers(nearbyStates.keySet(), task.getBuyerId(), fanout);
      // Widen only when the nearest window came back mostly busy or ineligible. This
      // keeps the common case cheap while stopping a small top-N cutoff from hiding
      // available partners in a dense area.
      if (eligible.size() < fanout) {
        nearbyStates.putAll(presence.getNearbyActiveHelperStates(
            task.getLat(), task.getLng(), radiusMeters, GEO_CANDIDATE_LIMIT_WIDE));
        eligible = eligibleHelpers(nearbyStates.keySet(), task.getBuyerId(), fanout);
      }
      return new CandidatePool(nearbyStates, eligible);
    }

    // Online sessions that pre-date the GEO index are not in it until their next
    // heartbeat writes GEOADD. Bounded H3 union covers them.
    int resolution = props.matching().h3Resolution();
    double edgeMeters = Math.max(1d, h3.getHexagonEdgeLengthAvg(resolution, com.uber.h3core.LengthUnit.m));
    int radiusKRing = (int) Math.ceil(radiusMeters / (edgeMeters * 1.5d)) + 1;
    int kRing = Math.min(props.matching().maxKRing(), radiusKRing);
    long taskCell = h3.latLngToCell(task.getLat(), task.getLng(), resolution);
    List<Long> nearbyCells = h3.gridDisk(taskCell, kRing);
    if (nearbyCells.size() > MAX_FALLBACK_H3_CELLS) {
      nearbyCells = nearbyCells.subList(0, MAX_FALLBACK_H3_CELLS);
    }
    Set<UUID> h3Helpers = presence.getOnlineHelpersForCells(nearbyCells);
    // One pipelined read rather than a round trip per helper.
    presence.getHelperStates(h3Helpers).forEach((helperId, state) -> {
      if (isEligibleOnlineHelper(state)) nearbyStates.put(helperId, state);
    });
    log.debug("GEO index cold for task {}; H3 fallback checked {} cells (k={}) and found {} active helpers",
        task.getId(), nearbyCells.size(), kRing, nearbyStates.size());
    return new CandidatePool(
        nearbyStates, eligibleHelpers(nearbyStates.keySet(), task.getBuyerId(), fanout));
  }

  /**
   * Scores candidates, pulling real travel times in one matrix call.
   *
   * <p>Coordinates come from {@code nearbyStates}, which the caller already read in
   * one pipeline — re-reading presence per candidate here would be an N+1 against a
   * network-remote Redis on the hottest path in the system.
   *
   * <p>The matrix call is skipped for a single candidate: one HTTP round trip inside
   * a transaction holding a row lock buys nothing when there is no ordering decision
   * to make.
   */
  private List<CandidateScorer.ScoredCandidate> rankCandidates(
      TaskEntity task,
      Map<UUID, Double> distanceByHelper,
      Map<UUID, HelperPresenceService.HelperState> nearbyStates) {
    if (distanceByHelper.isEmpty()) return List.of();

    Map<UUID, Integer> etaByHelper = new HashMap<>();
    if (distanceByHelper.size() > 1) {
      List<UUID> helperIds = distanceByHelper.entrySet().stream()
          .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
          .limit(ETA_MATRIX_CANDIDATE_LIMIT)
          .map(Map.Entry::getKey)
          .toList();
      List<double[]> origins = helperIds.stream()
          .map(id -> {
            var state = nearbyStates.get(id);
            return new double[] {state.lat(), state.lng()};
          })
          .toList();
      List<Integer> etas = geo.etaSecondsToDestination(origins, task.getLat(), task.getLng());
      for (int i = 0; i < helperIds.size() && i < etas.size(); i++) {
        etaByHelper.put(helperIds.get(i), etas.get(i));
      }
    }

    Map<UUID, HelperProfileEntity> profiles = helperProfiles.findAllById(distanceByHelper.keySet())
        .stream()
        .collect(Collectors.toMap(HelperProfileEntity::getUserId, profile -> profile, (a, b) -> a));

    return scorer.rank(distanceByHelper, etaByHelper, profiles, Instant.now());
  }

  /**
   * Partners who must not be offered this task again.
   *
   * <p>Only a live offer, an acceptance or an explicit decline excludes. A partner
   * who simply let an offer lapse is eligible again — before that distinction
   * existed, one ignored offer blacklisted them from the task permanently.
   */
  private Set<UUID> excludedHelperIds(UUID taskId) {
    Instant now = Instant.now();
    return offers.findAllByTaskId(taskId).stream()
        .filter(offer -> offer.getStatus() == TaskOfferStatus.ACCEPTED
            || offer.getStatus() == TaskOfferStatus.DECLINED
            || (offer.getStatus() == TaskOfferStatus.OFFERED
                && offer.getExpiresAt() != null
                && offer.getExpiresAt().isAfter(now)))
        .map(TaskOfferEntity::getHelperId)
        .collect(Collectors.toSet());
  }

  private List<UUID> writeOffers(TaskEntity task, List<CandidateScorer.ScoredCandidate> chosen) {
    if (chosen.isEmpty()) return List.of();

    // Lapsed offers are revived in place: (task_id, helper_id) is unique, so a
    // second row cannot be inserted for the same pair.
    Map<UUID, TaskOfferEntity> revivable = offers.findAllByTaskId(task.getId()).stream()
        .filter(offer -> offer.getStatus() == TaskOfferStatus.EXPIRED)
        .collect(Collectors.toMap(TaskOfferEntity::getHelperId, offer -> offer, (a, b) -> a));

    Instant now = Instant.now();
    Instant expires = now.plusSeconds(props.matching().offerTtlSeconds());
    List<UUID> helperIds = new ArrayList<>();
    List<TaskOfferEntity> offerList = new ArrayList<>();
    for (CandidateScorer.ScoredCandidate candidate : chosen) {
      TaskOfferEntity offer = revivable.get(candidate.helperId());
      if (offer == null) {
        offer = new TaskOfferEntity();
        offer.setTaskId(task.getId());
        offer.setHelperId(candidate.helperId());
      }
      offer.setStatus(TaskOfferStatus.OFFERED);
      offer.setOfferedAt(now);
      offer.setExpiresAt(expires);
      offer.setRespondedAt(null);
      offerList.add(offer);
      helperIds.add(candidate.helperId());
    }
    offers.saveAllAndFlush(offerList);
    publishOffersAfterCommit(task, chosen, expires);
    return helperIds;
  }

  /** Updates the fairness and acceptance-rate counters the scorer reads. */
  private void recordOffered(List<UUID> helperIds) {
    Instant now = Instant.now();
    List<HelperProfileEntity> profiles = helperProfiles.findAllById(helperIds);
    for (HelperProfileEntity profile : profiles) {
      profile.setOffersSeen(profile.getOffersSeen() + 1);
      profile.setLastOfferedAt(now);
    }
    helperProfiles.saveAll(profiles);
  }

  private void publishOffersAfterCommit(
      TaskEntity task, List<CandidateScorer.ScoredCandidate> chosen, Instant expires) {
    Runnable publish = () -> realtimeDispatchExecutor.execute(() -> {
      for (CandidateScorer.ScoredCandidate candidate : chosen) {
        realtime.publish(
            "task.offered",
            java.util.Map.ofEntries(
                java.util.Map.entry("helperId", candidate.helperId().toString()),
                java.util.Map.entry("taskId", task.getId().toString()),
                java.util.Map.entry("title", task.getTitle() == null ? "Task" : task.getTitle()),
                java.util.Map.entry("description", task.getDescription()),
                java.util.Map.entry("urgency", task.getUrgency().name()),
                java.util.Map.entry("timeMinutes", task.getTimeMinutes()),
                java.util.Map.entry("budgetPaise", task.getBudgetPaise()),
                java.util.Map.entry("lat", task.getLat()),
                java.util.Map.entry("lng", task.getLng()),
                java.util.Map.entry("distanceMeters", candidate.distanceMeters()),
                // The app drives its countdown from expiresAt rather than a
                // hardcoded number, so the two can no longer disagree.
                java.util.Map.entry("etaSeconds", candidate.etaSeconds()),
                java.util.Map.entry("expiresAt", expires.toString())));
      }
    });
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          publish.run();
        }
      });
    } else {
      publish.run();
    }
  }

  private boolean isEligibleOnlineHelper(HelperPresenceService.HelperState state) {
    if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) {
      return false;
    }
    long staleMs = Math.max(10, props.matching().helperStaleAfterSeconds()) * 1000L;
    long ageMs = Math.max(0L, Instant.now().toEpochMilli() - state.lastSeenEpochMs());
    return ageMs <= staleMs;
  }

  /**
   * Filters candidates down to partners who can actually take this job.
   *
   * <p>Four exclusions: the buyer themselves, unapproved KYC, an active task in
   * progress, and — new — already holding the maximum number of live offers.
   */
  private Set<UUID> eligibleHelpers(Set<UUID> helperIds, UUID buyerId, int fanout) {
    if (helperIds == null || helperIds.isEmpty()) {
      return Set.of();
    }
    Set<UUID> candidates = helperIds.stream()
        .filter(id -> !id.equals(buyerId))
        .collect(Collectors.toSet());
    if (candidates.isEmpty()) {
      return Set.of();
    }
    Set<UUID> approved = helperProfiles.findAllById(candidates).stream()
        .filter(profile -> profile.getKycStatus() == HelperKycStatus.APPROVED)
        .map(HelperProfileEntity::getUserId)
        .collect(Collectors.toSet());
    if (approved.isEmpty()) {
      return Set.of();
    }
    Set<UUID> busy = new HashSet<>(tasks.findAssignedHelperIdsWithStatuses(approved, HELPER_ACTIVE_TASK_STATUSES));
    approved.removeAll(busy);
    if (approved.isEmpty()) {
      return Set.of();
    }
    Set<UUID> atOfferCap = new HashSet<>(offers.findHelperIdsAtLiveOfferCap(
        approved, Instant.now(), props.matching().maxLiveOffersPerHelper()));
    approved.removeAll(atOfferCap);
    return approved;
  }
}
