package com.helpinminutes.api.matching;

import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.uber.h3core.H3Core;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingService {
  private static final List<Double> EXPANSION_RADII_METERS = List.of(100d, 300d, 600d, 1000d, 1500d, 2000d, 2500d,
      3000d);

  private final AppProperties props;
  private final H3Core h3;
  private final HelperPresenceService presence;
  private final TaskOfferRepository offers;
  private final RealtimePublisher realtime;
  private final NotificationQueueService notificationQueue;

  public MatchingService(
      AppProperties props,
      H3Core h3,
      HelperPresenceService presence,
      TaskOfferRepository offers,
      RealtimePublisher realtime,
      NotificationQueueService notificationQueue) {
    this.props = props;
    this.h3 = h3;
    this.presence = presence;
    this.offers = offers;
    this.realtime = realtime;
    this.notificationQueue = notificationQueue;
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MatchingService.class);

  @Transactional
  public List<UUID> dispatchOffers(TaskEntity task) {
    long taskCell = h3.latLngToCell(task.getLat(), task.getLng(), props.matching().h3Resolution());

    Map<UUID, Double> bestDistanceByHelper = new HashMap<>();
    int maxKRing = Math.max(props.matching().maxKRing(), 0);

    log.info("Matching task {} at {}, {} (cell {}) with maxKRing {}", task.getId(), task.getLat(), task.getLng(),
        taskCell, maxKRing);

    for (int k = 0; k <= maxKRing; k++) {
      List<Long> ringCells = h3.gridDisk(taskCell, k);
      var onlineHelpers = presence.getOnlineHelpersForCells(ringCells);
      log.info("Ring {} has {} cells and {} online helpers", k, ringCells.size(), onlineHelpers.size());
      for (UUID helperId : onlineHelpers) {
        if (helperId.equals(task.getBuyerId())) {
          continue; // Don't offer a task to the buyer who created it
        }
        var state = presence.getHelperState(helperId);

        log.info("Helper {} raw state: {}, online={}, lastSeen={}", helperId, state,
            state != null ? state.online() : "null", state != null ? state.lastSeenEpochMs() : "null");

        if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) {
          log.warn("Helper {} is not fully online. State: {}", helperId, state);
          continue;
        }

        long ageSeconds = (Instant.now().toEpochMilli() - state.lastSeenEpochMs()) / 1000;
        if (ageSeconds > props.matching().helperStaleAfterSeconds()) {
          log.warn("Helper {} is stale. Age seconds: {}", helperId, ageSeconds);
          continue;
        }

        double distMeters = GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng());
        log.info("Helper {} is valid, distance to task: {} meters", helperId, distMeters);

        if (distMeters > 3000d) {
          log.warn("Helper {} is too far ({} meters > 3000)", helperId, distMeters);
          continue;
        }
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
    for (Candidate c : chosen) {
      TaskOfferEntity offer = new TaskOfferEntity();
      offer.setTaskId(task.getId());
      offer.setHelperId(c.helperId());
      offer.setStatus(TaskOfferStatus.OFFERED);
      offer.setOfferedAt(now);
      offer.setExpiresAt(expires);
      offers.save(offer);

      helperIds.add(c.helperId());

      realtime.publish(
          "task.offered",
          java.util.Map.ofEntries(
              java.util.Map.entry("helperId", c.helperId().toString()),
              java.util.Map.entry("taskId", task.getId().toString()),
              java.util.Map.entry("title", task.getTitle() == null ? "Task" : task.getTitle()),
              java.util.Map.entry("description", task.getDescription()),
              java.util.Map.entry("urgency", task.getUrgency().name()),
              java.util.Map.entry("timeMinutes", task.getTimeMinutes()),
              java.util.Map.entry("budgetPaise", task.getBudgetPaise()),
              java.util.Map.entry("lat", task.getLat()),
              java.util.Map.entry("lng", task.getLng()),
              java.util.Map.entry("distanceMeters", c.distanceMeters()),
              java.util.Map.entry("expiresAt", expires.toString())));
    }

    notificationQueue.enqueueTaskOffered(helperIds, task);

    return helperIds;
  }

  private record Candidate(UUID helperId, double distanceMeters) {
  }
}
