package com.helpinminutes.api.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.uber.h3core.H3Core;
import java.math.BigDecimal;
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
  @Mock private GeoProviderChain geo;

  /** Wave tiers used by every test here; deliberately small so counts are readable. */
  private static final List<Integer> WAVE_RADII = List.of(3000, 6000, 10000);
  private static final List<Integer> WAVE_FANOUTS = List.of(3, 5, 8);

  private MatchingService matching;

  @BeforeEach
  void setUp() {
    AppProperties properties = new AppProperties(
        "test",
        new AppProperties.Jwt(
            "test-access-secret-0123456789abcdef0123456789abcdef",
            "test-refresh-secret-0123456789abcdef0123456789abcdef",
            900, 3600),
        new AppProperties.Otp(300),
        new AppProperties.Matching(9, 6, 8, 120, 45, WAVE_RADII, WAVE_FANOUTS, 15000, 2),
        new AppProperties.Realtime("him:rt:events", "", "", 500),
        new AppProperties.Payments(false));
    // A real scorer, not a mock: ranking is the behaviour under test in several of
    // these cases, and a stubbed comparator would prove nothing.
    matching = new MatchingService(
        properties, h3, presence, offers, tasks, realtime, notifications, helperProfiles,
        geo, new CandidateScorer());
  }

  @Test
  void skipsTasksThatAreNoLongerSearchable() {
    TaskEntity task = task(TaskStatus.ASSIGNED);

    assertEquals(List.of(), matching.dispatchOffers(task));

    verify(presence, never()).getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt());
    verify(offers, never()).saveAllAndFlush(any());
  }

  @Test
  void createsOneOfferAndQueuesRealtimeAndPushForEligibleNearbyHelper() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID helperId = UUID.randomUUID();

    stubEligibleHelpers(task, Map.of(helperId, nearbyState()));
    stubNoExistingOffers(task);
    stubOfferPersistence();

    assertEquals(List.of(helperId), matching.dispatchOffers(task));

    verify(offers).saveAllAndFlush(anyList());
    verify(notifications).enqueueTaskOffered(eq(List.of(helperId)), eq(task));
    verify(realtime).publish(eq("task.offered"), any());
  }

  // ─── wave escalation ──────────────────────────────────────────────────────

  @Test
  void staleDurableJobCannotAdvanceANewerWave() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    task.setDispatchWave(2);

    assertEquals(List.of(), matching.dispatchOffers(task, true, 1));

    verify(presence, never()).getNearbyActiveHelperStates(
        anyDouble(), anyDouble(), anyDouble(), anyInt());
    verify(tasks, never()).findByIdForUpdate(any());
  }

  /**
   * The reach fix. A single 3km pass with a fanout of 5 meant a job's entire
   * lifetime reached at most a handful of partners, and in a thin-supply area it
   * was simply cancelled. Each unanswered window must now search wider.
   */
  @Test
  void eachWaveSearchesAWiderRadiusWithABiggerFanout() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    task.setDispatchWave(1);
    UUID helperId = UUID.randomUUID();

    stubEligibleHelpers(task, Map.of(helperId, nearbyState()));
    stubNoExistingOffers(task);
    stubOfferPersistence();

    matching.dispatchOffers(task);

    // Wave 1 is the second tier: 6km, not the 3km of wave 0.
    verify(presence).getNearbyActiveHelperStates(task.getLat(), task.getLng(), 6000d, 120);
  }

  @Test
  void waveBeyondTheLastTierReusesTheWidestTier() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    task.setDispatchWave(99);
    UUID helperId = UUID.randomUUID();

    stubEligibleHelpers(task, Map.of(helperId, nearbyState()));
    stubNoExistingOffers(task);
    stubOfferPersistence();

    matching.dispatchOffers(task);

    // Clamped to the widest configured tier rather than running off the end.
    verify(presence).getNearbyActiveHelperStates(task.getLat(), task.getLng(), 10000d, 120);
  }

  /** The wave counter has to advance even on a miss, or the search never widens. */
  @Test
  void advancesTheWaveEvenWhenNobodyWasFound() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(Map.of());
    when(presence.getOnlineHelpersForCells(anyList())).thenReturn(java.util.Set.of());
    when(h3.getHexagonEdgeLengthAvg(anyInt(), any())).thenReturn(174d);
    when(h3.latLngToCell(anyDouble(), anyDouble(), anyInt())).thenReturn(1L);
    when(h3.gridDisk(anyLong(), anyInt())).thenReturn(List.of(1L));

    assertEquals(List.of(), matching.dispatchOffers(task));

    assertEquals(1, task.getDispatchWave());
    assertTrue(task.getLastDispatchedAt() != null, "lastDispatchedAt drives the re-dispatch backoff");
  }

  // ─── ranking ──────────────────────────────────────────────────────────────

  /**
   * The ranking fix. Straight-line distance is the wrong proxy in a city: 400m
   * across a flyover with no turn is a longer trip than 900m down a through road.
   */
  @Test
  void ranksByTravelTimeNotStraightLineDistance() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID nearButSlow = UUID.randomUUID();
    UUID farButFast = UUID.randomUUID();

    // nearButSlow is ~110m away, farButFast ~550m — distance order would pick the
    // first. Routing says otherwise.
    Map<UUID, HelperPresenceService.HelperState> states = new java.util.LinkedHashMap<>();
    states.put(nearButSlow, stateAt(17.3860, 78.4867));
    states.put(farButFast, stateAt(17.3900, 78.4867));

    stubEligibleHelpers(task, states);
    stubNoExistingOffers(task);
    stubOfferPersistence();
    when(geo.etaSecondsToDestination(anyList(), anyDouble(), anyDouble()))
        .thenAnswer(call -> {
          List<double[]> origins = call.getArgument(0);
          // Map each origin back to an ETA: the nearer partner is stuck behind a
          // 15-minute detour, the further one is 2 minutes away.
          return origins.stream()
              .map(origin -> Math.abs(origin[0] - 17.3860) < 0.0001 ? 900 : 120)
              .toList();
        });

    List<UUID> offered = matching.dispatchOffers(task);

    assertEquals(farButFast, offered.get(0), "lower ETA must win over shorter distance");
  }

  /**
   * Anti-starvation. Ranking was deterministic on distance, so in a cluster the
   * same nearest partners were re-offered every wave and the rest never heard from
   * us at all.
   */
  @Test
  void prefersThePartnerWhoHasWaitedLongerWhenEtaIsEqual() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID justOffered = UUID.randomUUID();
    UUID waitingAllMorning = UUID.randomUUID();

    Map<UUID, HelperPresenceService.HelperState> states = new java.util.LinkedHashMap<>();
    states.put(justOffered, stateAt(17.3860, 78.4867));
    states.put(waitingAllMorning, stateAt(17.3860, 78.4867));

    HelperProfileEntity recent = approvedProfile(justOffered);
    recent.setLastOfferedAt(Instant.now());
    HelperProfileEntity stale = approvedProfile(waitingAllMorning);
    stale.setLastOfferedAt(Instant.now().minusSeconds(3600));

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(states);
    when(helperProfiles.findAllById(any())).thenReturn(List.of(recent, stale));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findHelperIdsAtLiveOfferCap(any(), any(), anyLong())).thenReturn(List.of());
    stubNoExistingOffers(task);
    stubOfferPersistence();
    when(geo.etaSecondsToDestination(anyList(), anyDouble(), anyDouble()))
        .thenReturn(List.of(300, 300));

    List<UUID> offered = matching.dispatchOffers(task);

    assertEquals(waitingAllMorning, offered.get(0));
  }

  @Test
  void fallsBackToDistanceRankingWhenRoutingIsUnavailable() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID near = UUID.randomUUID();
    UUID far = UUID.randomUUID();

    Map<UUID, HelperPresenceService.HelperState> states = new java.util.LinkedHashMap<>();
    states.put(far, stateAt(17.4000, 78.4867));
    states.put(near, stateAt(17.3860, 78.4867));

    stubEligibleHelpers(task, states);
    stubNoExistingOffers(task);
    stubOfferPersistence();
    // Every routing provider down: the chain returns nothing rather than throwing.
    when(geo.etaSecondsToDestination(anyList(), anyDouble(), anyDouble())).thenReturn(List.of());

    List<UUID> offered = matching.dispatchOffers(task);

    assertEquals(near, offered.get(0), "a routing outage degrades ranking, it does not fail dispatch");
  }

  @Test
  void sendsOnlyTheClosestFiftyCandidatesToTheEtaMatrix() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    Map<UUID, HelperPresenceService.HelperState> states = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 51; i++) {
      states.put(UUID.randomUUID(), stateAt(17.3850 + i * 0.0001d, 78.4867));
    }
    stubEligibleHelpers(task, states);
    stubNoExistingOffers(task);
    stubOfferPersistence();
    when(geo.etaSecondsToDestination(anyList(), anyDouble(), anyDouble()))
        .thenAnswer(call -> ((List<double[]>) call.getArgument(0)).stream().map(origin -> 120).toList());

    matching.dispatchOffers(task);

    ArgumentCaptor<List<double[]>> origins = ArgumentCaptor.forClass(List.class);
    verify(geo).etaSecondsToDestination(origins.capture(), eq(task.getLat()), eq(task.getLng()));
    assertEquals(50, origins.getValue().size());
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

    stubEligibleHelpers(task, Map.of(helperId, nearbyState()));
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

    stubEligibleHelpers(task, Map.of(helperId, nearbyState()));
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

    stubEligibleHelpers(task, Map.of(helperId, nearbyState()));
    when(offers.findAllByTaskId(task.getId())).thenReturn(List.of(declined));

    // A partner who said no should not be pestered again for the same job.
    assertEquals(List.of(), matching.dispatchOffers(task));
    verify(offers, never()).saveAllAndFlush(anyList());
  }

  @Test
  void offerExpiryMatchesTheConfiguredTtl() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID helperId = UUID.randomUUID();

    stubEligibleHelpers(task, Map.of(helperId, nearbyState()));
    stubNoExistingOffers(task);
    stubOfferPersistence();

    Instant before = Instant.now();
    matching.dispatchOffers(task);

    ArgumentCaptor<List<TaskOfferEntity>> saved = ArgumentCaptor.forClass(List.class);
    verify(offers).saveAllAndFlush(saved.capture());
    Instant expires = saved.getValue().get(0).getExpiresAt();
    // 45s, which is what the partner app's countdown shows. The two used to
    // disagree (30s on screen, 120s on the server), so the slot stayed locked for
    // 90s after the modal had vanished.
    assertTrue(expires.isAfter(before.plusSeconds(40)) && expires.isBefore(before.plusSeconds(50)),
        "expected ~45s TTL, got " + java.time.Duration.between(before, expires).getSeconds() + "s");
  }

  // ─── eligibility filters ──────────────────────────────────────────────────

  @Test
  void excludesTheBuyerFromTheirOwnTask() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID buyerAsHelper = task.getBuyerId();

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
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
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(Map.of(helperId, nearbyState()));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(pending));

    assertEquals(List.of(), matching.dispatchOffers(task));
  }

  @Test
  void excludesHelpersAlreadyOnAnActiveTask() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID busyHelper = UUID.randomUUID();

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(Map.of(busyHelper, nearbyState()));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(approvedProfile(busyHelper)));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of(busyHelper));

    assertEquals(List.of(), matching.dispatchOffers(task));
  }

  /**
   * Per-partner offer cap. The app shows one offer modal at a time, so a partner
   * holding several live offers lost every alert but the newest while those tasks
   * sat out their full TTL with a locked slot.
   */
  @Test
  void excludesHelpersAlreadyHoldingTheMaximumLiveOffers() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID saturatedHelper = UUID.randomUUID();

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(Map.of(saturatedHelper, nearbyState()));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(approvedProfile(saturatedHelper)));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findHelperIdsAtLiveOfferCap(any(), any(), eq(2L))).thenReturn(List.of(saturatedHelper));

    assertEquals(List.of(), matching.dispatchOffers(task));
  }

  @Test
  void neverOffersToMoreHelpersThanTheWaveFanout() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    Map<UUID, HelperPresenceService.HelperState> states = new java.util.LinkedHashMap<>();
    List<HelperProfileEntity> profiles = new java.util.ArrayList<>();
    for (int i = 0; i < 12; i++) {
      UUID id = UUID.randomUUID();
      states.put(id, nearbyState());
      profiles.add(approvedProfile(id));
    }

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(states);
    when(helperProfiles.findAllById(any())).thenReturn(profiles);
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findHelperIdsAtLiveOfferCap(any(), any(), anyLong())).thenReturn(List.of());
    when(geo.etaSecondsToDestination(anyList(), anyDouble(), anyDouble())).thenReturn(List.of());
    stubNoExistingOffers(task);
    stubOfferPersistence();

    // Wave 0 fanout in the test properties.
    assertEquals(WAVE_FANOUTS.get(0).intValue(), matching.dispatchOffers(task).size());
  }

  @Test
  void skipsHelpersBeyondTheWaveRadius() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID farHelper = UUID.randomUUID();
    // ~10km north of the task — outside wave 0's 3km tier.
    HelperPresenceService.HelperState far = stateAt(17.4750, 78.4867);

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(Map.of(farHelper, far));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(approvedProfile(farHelper)));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findHelperIdsAtLiveOfferCap(any(), any(), anyLong())).thenReturn(List.of());
    stubNoExistingOffers(task);

    assertEquals(List.of(), matching.dispatchOffers(task));
  }

  /** Offer counters feed the acceptance-rate and fairness terms in ranking. */
  @Test
  void recordsThatEachOfferedPartnerWasOffered() {
    TaskEntity task = task(TaskStatus.SEARCHING);
    UUID helperId = UUID.randomUUID();
    HelperProfileEntity profile = approvedProfile(helperId);
    profile.setOffersSeen(4);

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(Map.of(helperId, nearbyState()));
    when(helperProfiles.findAllById(any())).thenReturn(List.of(profile));
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findHelperIdsAtLiveOfferCap(any(), any(), anyLong())).thenReturn(List.of());
    stubNoExistingOffers(task);
    stubOfferPersistence();

    matching.dispatchOffers(task);

    assertEquals(5, profile.getOffersSeen());
    assertTrue(profile.getLastOfferedAt() != null);
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private static HelperPresenceService.HelperState nearbyState() {
    return stateAt(17.3851, 78.4868);
  }

  private static HelperPresenceService.HelperState stateAt(double lat, double lng) {
    return new HelperPresenceService.HelperState(
        lat, lng, "cell", "1", Instant.now().toEpochMilli());
  }

  private static HelperProfileEntity approvedProfile(UUID helperId) {
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(helperId);
    profile.setKycStatus(HelperKycStatus.APPROVED);
    profile.setRating(BigDecimal.valueOf(4.5));
    return profile;
  }

  /** Wires up KYC-approved, free, uncapped nearby helpers for the given task. */
  private void stubEligibleHelpers(
      TaskEntity task, Map<UUID, HelperPresenceService.HelperState> states) {
    List<HelperProfileEntity> profiles = states.keySet().stream()
        .map(MatchingServiceTest::approvedProfile)
        .toList();

    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(presence.getNearbyActiveHelperStates(anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(states);
    when(helperProfiles.findAllById(any())).thenReturn(profiles);
    when(tasks.findAssignedHelperIdsWithStatuses(any(), any())).thenReturn(List.of());
    when(offers.findHelperIdsAtLiveOfferCap(any(), any(), anyLong())).thenReturn(List.of());
  }

  private void stubNoExistingOffers(TaskEntity task) {
    when(offers.findAllByTaskId(task.getId())).thenReturn(List.of());
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
