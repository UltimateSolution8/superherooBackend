package com.helpinminutes.api.matching;

import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.uber.h3core.H3Core;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

@Service
public class MatchingService {
  /** Hard ceiling on how far a partner may be offered a job. */
  private static final double MAX_OFFER_RADIUS_METERS = 3000d;

  private static final List<Double> EXPANSION_RADII_METERS = List.of(100d, 300d, 600d, 1000d, 1500d, 2000d, 2500d,
      MAX_OFFER_RADIUS_METERS);
  private static final List<TaskStatus> HELPER_ACTIVE_TASK_STATUSES = List.of(
      TaskStatus.ASSIGNED,
      TaskStatus.ARRIVED,
      TaskStatus.STARTED);

  private final AppProperties props;
  private final H3Core h3;
  private final HelperPresenceService presence;
  private final TaskOfferRepository offers;
  private final TaskRepository tasks;
  private final RealtimePublisher realtime;
  private final NotificationQueueService notificationQueue;
  private final HelperProfileRepository helperProfiles;
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
      @Qualifier("realtimeDispatchExecutor") Executor realtimeDispatchExecutor) {
    this.props = props;
    this.h3 = h3;
    this.presence = presence;
    this.offers = offers;
    this.tasks = tasks;
    this.realtime = realtime;
    this.notificationQueue = notificationQueue;
    this.helperProfiles = helperProfiles;
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
    Map<UUID, Double> bestDistanceByHelper = new HashMap<>();
    int geoCandidateLimit = 100;
    Map<UUID, HelperPresenceService.HelperState> nearbyStates = new HashMap<>(presence.getNearbyActiveHelperStates(
        task.getLat(), task.getLng(), MAX_OFFER_RADIUS_METERS, geoCandidateLimit));
    Set<UUID> eligibleNearbyHelpers = eligibleHelpers(nearbyStates.keySet(), task.getBuyerId());
    if (!nearbyStates.isEmpty() && eligibleNearbyHelpers.size() < props.matching().offerFanout()) {
      // Expand only when the nearest window was mostly busy or ineligible. This
      // keeps normal matching cheap while preventing a small top-N cutoff from
      // hiding available partners in dense areas.
      nearbyStates.putAll(presence.getNearbyActiveHelperStates(task.getLat(), task.getLng(), MAX_OFFER_RADIUS_METERS, 500));
      eligibleNearbyHelpers = eligibleHelpers(nearbyStates.keySet(), task.getBuyerId());
    }

    // Existing online sessions may pre-date the GEO index. Keep a bounded H3
    // fallback until their next heartbeat writes GEOADD.
    if (nearbyStates.isEmpty()) {
      int resolution = props.matching().h3Resolution();
      double edgeMeters = Math.max(1d, h3.getHexagonEdgeLengthAvg(resolution, com.uber.h3core.LengthUnit.m));
      int radiusKRing = (int) Math.ceil(MAX_OFFER_RADIUS_METERS / (edgeMeters * 1.5d)) + 1;
      // Honour the configured ceiling. Taking the max ignored maxKRing entirely:
      // at resolution 9 that computed k≈13, a gridDisk of ~547 cells and a
      // SUNION over 547 Redis keys on a single cold dispatch.
      int kRing = Math.min(props.matching().maxKRing(), radiusKRing);
      long taskCell = h3.latLngToCell(task.getLat(), task.getLng(), resolution);
      List<Long> nearbyCells = h3.gridDisk(taskCell, kRing);
      Set<UUID> h3Helpers = presence.getOnlineHelpersForCells(nearbyCells);
      // One pipelined read rather than a round trip per helper.
      presence.getHelperStates(h3Helpers).forEach((helperId, state) -> {
        if (isEligibleOnlineHelper(state)) nearbyStates.put(helperId, state);
      });
      eligibleNearbyHelpers = eligibleHelpers(nearbyStates.keySet(), task.getBuyerId());
      log.debug("GEO index cold for task {}; H3 fallback checked {} cells and found {} active helpers",
          task.getId(), nearbyCells.size(), nearbyStates.size());
    }

    Instant dispatchAt = Instant.now();
    List<TaskOfferEntity> existingOffers = offers.findAllByTaskId(task.getId());

    // Only a *live* offer, an acceptance, or an explicit decline should exclude
    // a helper. Previously any existing row did, so a helper who simply let one
    // offer lapse was blacklisted from that task forever.
    Set<UUID> excludedHelperIds = existingOffers.stream()
        .filter(o -> o.getStatus() == TaskOfferStatus.ACCEPTED
            || o.getStatus() == TaskOfferStatus.DECLINED
            || (o.getStatus() == TaskOfferStatus.OFFERED
                && o.getExpiresAt() != null
                && o.getExpiresAt().isAfter(dispatchAt)))
        .map(TaskOfferEntity::getHelperId)
        .collect(java.util.stream.Collectors.toSet());

    // Lapsed offers are revived in place: (task_id, helper_id) is unique, so a
    // second row cannot be inserted for the same pair.
    Map<UUID, TaskOfferEntity> revivableOffers = existingOffers.stream()
        .filter(o -> o.getStatus() == TaskOfferStatus.EXPIRED)
        .collect(java.util.stream.Collectors.toMap(
            TaskOfferEntity::getHelperId, o -> o, (a, b) -> a));

    for (UUID helperId : eligibleNearbyHelpers) {
      if (excludedHelperIds.contains(helperId)) {
        continue;
      }
      var state = nearbyStates.get(helperId);
      if (!isEligibleOnlineHelper(state)) {
        continue;
      }
      double distMeters = GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng());
      if (distMeters <= MAX_OFFER_RADIUS_METERS) {
        bestDistanceByHelper.merge(helperId, distMeters, Math::min);
      }
    }

    List<Candidate> candidates = bestDistanceByHelper.entrySet().stream()
        .map(e -> new Candidate(e.getKey(), e.getValue()))
        .sorted(Comparator.comparingDouble(Candidate::distanceMeters))
        .toList();

    List<Candidate> staged = new ArrayList<>();
    for (double radius : EXPANSION_RADII_METERS) {
      for (Candidate c : candidates) {
        if (c.distanceMeters() <= radius && staged.stream().noneMatch(s -> s.helperId().equals(c.helperId()))) {
          staged.add(c);
        }
      }
      if (staged.size() >= props.matching().offerFanout()) {
        break;
      }
    }

    List<Candidate> chosen = staged.stream().limit(props.matching().offerFanout()).toList();

    log.info("Matching summary - Candidates: {}, Staged: {}, Fanout limit: {}, Chosen: {}",
        candidates.size(), staged.size(), props.matching().offerFanout(), chosen.size());

    Instant now = Instant.now();
    Instant expires = now.plusSeconds(props.matching().offerTtlSeconds());

    List<UUID> helperIds = new ArrayList<>();
    List<TaskOfferEntity> offerList = new ArrayList<>();
    for (Candidate c : chosen) {
      TaskOfferEntity offer = revivableOffers.get(c.helperId());
      if (offer == null) {
        offer = new TaskOfferEntity();
        offer.setTaskId(task.getId());
        offer.setHelperId(c.helperId());
      }
      offer.setStatus(TaskOfferStatus.OFFERED);
      offer.setOfferedAt(now);
      offer.setExpiresAt(expires);
      offer.setRespondedAt(null);
      offerList.add(offer);

      helperIds.add(c.helperId());
    }
    if (!offerList.isEmpty()) {
      offers.saveAllAndFlush(offerList);
      publishOffersAfterCommit(task, chosen, expires);
    }

    if (sendPushNotifications) {
      notificationQueue.enqueueTaskOffered(helperIds, task);
    }

    return helperIds;
  }

  private void publishOffersAfterCommit(TaskEntity task, List<Candidate> chosen, Instant expires) {
    Runnable publish = () -> realtimeDispatchExecutor.execute(() -> {
      for (Candidate candidate : chosen) {
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

  private Set<UUID> eligibleHelpers(Set<UUID> helperIds, UUID buyerId) {
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
        .map(profile -> profile.getUserId())
        .collect(Collectors.toSet());
    if (approved.isEmpty()) {
      return Set.of();
    }
    Set<UUID> busy = new HashSet<>(tasks.findAssignedHelperIdsWithStatuses(approved, HELPER_ACTIVE_TASK_STATUSES));
    approved.removeAll(busy);
    return approved;
  }

  private record Candidate(UUID helperId, double distanceMeters) {
  }
}
