package com.helpinminutes.api.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.uber.h3core.H3Core;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {
  @Mock private H3Core h3;
  @Mock private HelperPresenceService presence;
  @Mock private TaskOfferRepository offers;
  @Mock private TaskRepository tasks;
  @Mock private RealtimePublisher realtime;
  @Mock private NotificationQueueService notifications;
  @Mock private HelperProfileRepository helperProfiles;

  private MatchingService matching;

  @BeforeEach
  void setUp() {
    AppProperties properties = new AppProperties(
        "test",
        new AppProperties.Jwt(
            "test-access-secret-0123456789abcdef0123456789abcdef",
            "test-refresh-secret-0123456789abcdef0123456789abcdef",
            900, 3600),
        new AppProperties.Otp(300, true),
        new AppProperties.Matching(9, 3, 5, 120, 120),
        new AppProperties.Realtime("him:rt:events", "", "", 500));
    matching = new MatchingService(
        properties, h3, presence, offers, tasks, realtime, notifications, helperProfiles, Runnable::run);
  }

  @Test
  void skipsTasksThatAreNoLongerSearchable() {
    TaskEntity task = task(TaskStatus.ASSIGNED);
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));

    assertEquals(List.of(), matching.dispatchOffers(task));

    verify(presence, never()).getNearbyActiveHelperStates(any(Double.class), any(Double.class), any(Double.class), any(Integer.class));
    verify(offers, never()).saveAllAndFlush(any());
  }

  @Test
  void createsOneOfferAndQueuesRealtimeAndPushForEligibleNearbyHelper() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID helperId = UUID.randomUUID();
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(helperId);
    profile.setKycStatus(HelperKycStatus.APPROVED);
    HelperPresenceService.HelperState state = new HelperPresenceService.HelperState(
        17.3851, 78.4868, "cell", "1", Instant.now().toEpochMilli());

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(task.getLat(), task.getLng(), 3000d, 100))
        .thenReturn(Map.of(helperId, state));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(profile));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findAllByTaskId(task.getId())).thenReturn(List.of());
    when(offers.saveAllAndFlush(anyList())).thenAnswer(call -> call.getArgument(0));

    assertEquals(List.of(helperId), matching.dispatchOffers(task));

    verify(offers).saveAllAndFlush(anyList());
    verify(notifications).enqueueTaskOffered(eq(List.of(helperId)), eq(task));
    verify(realtime).publish(eq("task.offered"), any());
  }

  private static TaskEntity task(TaskStatus status) {
    TaskEntity task = new TaskEntity();
    task.setBuyerId(UUID.randomUUID());
    task.setTitle("Shopping help");
    task.setDescription("Collect groceries");
    task.setUrgency(TaskUrgency.NORMAL);
    task.setTimeMinutes(30);
    task.setBudgetPaise(20_000L);
    task.setLat(17.3850);
    task.setLng(78.4867);
    task.setStatus(status);
    task.prePersist();
    return task;
  }
}
