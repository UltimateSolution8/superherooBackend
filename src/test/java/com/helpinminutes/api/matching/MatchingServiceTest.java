package com.helpinminutes.api.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
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
import org.mockito.ArgumentCaptor;
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
        new AppProperties.Realtime("him:rt:events", "", "", 500),
        new AppProperties.Payments(false));
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

  // ─── offer lifecycle ──────────────────────────────────────────────────────

  /**
   * The core regression. Dispatch used to exclude any helper with an existing
   * offer row regardless of status, so a partner who let one offer lapse was
   * permanently blacklisted from that task and could never be re-offered it.
   */
  @Test
  void reOffersToAHelperWhoseEarlierOfferExpired() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID helperId = UUID.randomUUID();

    TaskOfferEntity lapsed = new TaskOfferEntity();
    lapsed.setTaskId(task.getId());
    lapsed.setHelperId(helperId);
    lapsed.setStatus(TaskOfferStatus.EXPIRED);
    lapsed.setOfferedAt(Instant.now().minusSeconds(600));
    lapsed.setExpiresAt(Instant.now().minusSeconds(480));

    stubEligibleHelper(task, helperId);
    stubOfferPersistence();
    when(offers.findAllByTaskId(task.getId())).thenReturn(List.of(lapsed));

    assertEquals(List.of(helperId), matching.dispatchOffers(task));

    ArgumentCaptor<List<TaskOfferEntity>> saved = ArgumentCaptor.forClass(List.class);
    verify(offers).saveAllAndFlush(saved.capture());
    TaskOfferEntity revived = saved.getValue().get(0);
    // Revived in place: (task_id, helper_id) is unique, so a second row would
    // violate the constraint.
    assertSame(lapsed, revived);
    assertEquals(TaskOfferStatus.OFFERED, revived.getStatus());
    assertTrue(revived.getExpiresAt().isAfter(Instant.now()));
  }

  @Test
  void doesNotReOfferWhileAnOfferIsStillLive() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID helperId = UUID.randomUUID();

    TaskOfferEntity live = new TaskOfferEntity();
    live.setTaskId(task.getId());
    live.setHelperId(helperId);
    live.setStatus(TaskOfferStatus.OFFERED);
    live.setOfferedAt(Instant.now());
    live.setExpiresAt(Instant.now().plusSeconds(90));

    stubEligibleHelper(task, helperId);
    when(offers.findAllByTaskId(task.getId())).thenReturn(List.of(live));

    assertEquals(List.of(), matching.dispatchOffers(task));
    verify(offers, never()).saveAllAndFlush(anyList());
  }

  @Test
  void doesNotReOfferToAHelperWhoDeclined() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID helperId = UUID.randomUUID();

    TaskOfferEntity declined = new TaskOfferEntity();
    declined.setTaskId(task.getId());
    declined.setHelperId(helperId);
    declined.setStatus(TaskOfferStatus.DECLINED);
    declined.setOfferedAt(Instant.now().minusSeconds(120));
    declined.setExpiresAt(Instant.now().minusSeconds(10));

    stubEligibleHelper(task, helperId);
    when(offers.findAllByTaskId(task.getId())).thenReturn(List.of(declined));

    // A partner who said no should not be pestered again for the same job.
    assertEquals(List.of(), matching.dispatchOffers(task));
    verify(offers, never()).saveAllAndFlush(anyList());
  }

  // ─── eligibility filters ──────────────────────────────────────────────────

  @Test
  void excludesTheBuyerFromTheirOwnTask() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID buyerAsHelper = task.getBuyerId();

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(task.getLat(), task.getLng(), 3000d, 100))
        .thenReturn(Map.of(buyerAsHelper, nearbyState()));

    assertEquals(List.of(), matching.dispatchOffers(task));
    verify(offers, never()).saveAllAndFlush(anyList());
  }

  @Test
  void excludesHelpersWhoseKycIsNotApproved() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID helperId = UUID.randomUUID();
    HelperProfileEntity pending = new HelperProfileEntity();
    pending.setUserId(helperId);
    pending.setKycStatus(HelperKycStatus.PENDING);

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(task.getLat(), task.getLng(), 3000d, 100))
        .thenReturn(Map.of(helperId, nearbyState()));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(pending));

    assertEquals(List.of(), matching.dispatchOffers(task));
  }

  @Test
  void excludesHelpersAlreadyOnAnActiveTask() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID busyHelper = UUID.randomUUID();
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(busyHelper);
    profile.setKycStatus(HelperKycStatus.APPROVED);

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(task.getLat(), task.getLng(), 3000d, 100))
        .thenReturn(Map.of(busyHelper, nearbyState()));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(profile));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of(busyHelper));

    assertEquals(List.of(), matching.dispatchOffers(task));
  }

  @Test
  void neverOffersToMoreHelpersThanTheConfiguredFanout() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    Map<UUID, HelperPresenceService.HelperState> states = new java.util.HashMap<>();
    List<HelperProfileEntity> profiles = new java.util.ArrayList<>();
    for (int i = 0; i < 12; i++) {
      UUID id = UUID.randomUUID();
      states.put(id, nearbyState());
      HelperProfileEntity profile = new HelperProfileEntity();
      profile.setUserId(id);
      profile.setKycStatus(HelperKycStatus.APPROVED);
      profiles.add(profile);
    }

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(task.getLat(), task.getLng(), 3000d, 100)).thenReturn(states);
    when(helperProfiles.findAllById(any())).thenReturn(profiles);
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findAllByTaskId(task.getId())).thenReturn(List.of());
    when(offers.saveAllAndFlush(anyList())).thenAnswer(call -> call.getArgument(0));

    // offerFanout is 5 in the test properties.
    assertEquals(5, matching.dispatchOffers(task).size());
  }

  @Test
  void skipsHelpersBeyondTheMaximumOfferRadius() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID farHelper = UUID.randomUUID();
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(farHelper);
    profile.setKycStatus(HelperKycStatus.APPROVED);
    // ~10km north of the task.
    HelperPresenceService.HelperState far = new HelperPresenceService.HelperState(
        17.4750, 78.4867, "cell", "1", Instant.now().toEpochMilli());

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(task.getLat(), task.getLng(), 3000d, 100))
        .thenReturn(Map.of(farHelper, far));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(profile));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findAllByTaskId(task.getId())).thenReturn(List.of());

    assertEquals(List.of(), matching.dispatchOffers(task));
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private static HelperPresenceService.HelperState nearbyState() {
    return new HelperPresenceService.HelperState(
        17.3851, 78.4868, "cell", "1", Instant.now().toEpochMilli());
  }

  /** Wires up one KYC-approved, free, nearby helper for the given task. */
  private void stubEligibleHelper(TaskEntity task, UUID helperId) {
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(helperId);
    profile.setKycStatus(HelperKycStatus.APPROVED);

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(task.getLat(), task.getLng(), 3000d, 100))
        .thenReturn(Map.of(helperId, nearbyState()));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(profile));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
  }

  /** Only needed by tests that expect an offer to actually be written. */
  private void stubOfferPersistence() {
    when(offers.saveAllAndFlush(anyList())).thenAnswer(call -> call.getArgument(0));
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
