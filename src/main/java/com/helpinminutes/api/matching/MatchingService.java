package com.helpinminutes.api.matching;

import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
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
  private static final List<Double> EXPANSION_RADII_METERS = List.of(100d, 300d, 600d, 1000d, 1500d, 2000d, 2500d, 3000d);

  private final AppProperties props;
  private final H3Core h3;
  private final HelperPresenceService presence;
  private final TaskOfferRepository offers;
  private final RealtimePublisher realtime;

  public MatchingService(
      AppProperties props,
      H3Core h3,
      HelperPresenceService presence,
      TaskOfferRepository offers,
      RealtimePublisher realtime) {
    this.props = props;
    this.h3 = h3;
    this.presence = presence;
    this.offers = offers;
    this.realtime = realtime;
  }

  @Transactional
  public List<UUID> dispatchOffers(TaskEntity task) {
    long taskCell = h3.latLngToCell(task.getLat(), task.getLng(), props.matching().h3Resolution());

    Map<UUID, Double> bestDistanceByHelper = new HashMap<>();
    int maxKRing = Math.max(props.matching().maxKRing(), 24);

    for (int k = 0; k <= maxKRing; k++) {
      List<Long> ringCells = h3.gridDisk(taskCell, k);
      for (UUID helperId : presence.getOnlineHelpersForCells(ringCells)) {
        var state = presence.getHelperState(helperId);
        if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) {
          continue;
        }

        long ageSeconds = (Instant.now().toEpochMilli() - state.lastSeenEpochMs()) / 1000;
        if (ageSeconds > props.matching().helperStaleAfterSeconds()) {
          continue;
        }

        double distMeters = GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng());
        if (distMeters > 3000d) {
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

    Instant now = Instant.now();
    Instant expires = now.plusSeconds(30);

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
          "TASK_OFFERED",
          java.util.Map.of(
              "helperId", c.helperId().toString(),
              "taskId", task.getId().toString(),
              "title", task.getTitle() == null ? "Task" : task.getTitle(),
              "description", task.getDescription(),
              "urgency", task.getUrgency().name(),
              "timeMinutes", task.getTimeMinutes(),
              "budgetPaise", task.getBudgetPaise(),
              "lat", task.getLat(),
              "lng", task.getLng(),
              "distanceMeters", c.distanceMeters()));
    }

    return helperIds;
  }

  private record Candidate(UUID helperId, double distanceMeters) {}
}
