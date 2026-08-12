package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.common.SchedulerLock;
import com.helpinminutes.api.config.TestAppProperties;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.moderation.service.AdminModerationService;
import com.helpinminutes.api.payments.service.PaymentLifecycleService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.support.service.SupportService;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskAiReviewRepository;
import com.helpinminutes.api.tasks.repo.TaskAuditLogRepository;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression suite for the search clock.
 *
 * <p>The bug: {@code TaskStaleCleanupJob} measured the search timeout from
 * {@code createdAt}, and no other clock existed. A booking held in ADMIN_REVIEW for
 * longer than {@code TASK_SEARCH_TIMEOUT_SECONDS} was already past a
 * created_at-based cutoff the instant an admin released it, so the next cleanup tick
 * — up to 30 seconds later — cancelled it with "No helper accepted your task in
 * time", requested a refund and filed a support ticket. Because re-dispatch ran
 * before the cancel pass in the same transaction, five partners also received a push
 * for a task that no longer existed.
 *
 * <p>Every test here fails if that reopens.
 */
class SearchingClockTest {

  // ─── the entity contract ──────────────────────────────────────────────────

  @Test
  void beginSearchingStartsAFreshClockAndResetsTheWave() {
    TaskEntity task = new TaskEntity();
    task.setStatus(TaskStatus.ADMIN_REVIEW);
    task.setDispatchWave(4);
    task.setLastDispatchedAt(Instant.now().minusSeconds(600));

    Instant releasedAt = Instant.now();
    task.beginSearching(releasedAt);

    assertEquals(TaskStatus.SEARCHING, task.getStatus());
    assertEquals(releasedAt, task.getSearchingStartedAt());
    // The wave has to restart too: a released booking deserves the near tier
    // first, not whatever radius the pre-hold attempts had climbed to.
    assertEquals(0, task.getDispatchWave());
    assertNull(task.getLastDispatchedAt());
  }

  @Test
  void newSearchingTaskGetsAClockOnPersist() {
    TaskEntity task = new TaskEntity();
    task.setStatus(TaskStatus.SEARCHING);
    task.prePersist();

    assertNotNull(task.getSearchingStartedAt(),
        "a task created straight into SEARCHING must carry a clock");
  }

  // ─── the actual regression ────────────────────────────────────────────────

  /**
   * A booking created two hours ago and only now approved must start its search
   * window from the approval, not from creation.
   */
  @Test
  void adminApprovalOfAnOldBookingRestartsTheSearchWindow() {
    TaskRepository tasks = mock(TaskRepository.class);
    AdminModerationService admin = new AdminModerationService(
        tasks,
        mock(TaskAiReviewRepository.class),
        mock(TaskAuditLogRepository.class),
        mock(UserRepository.class),
        mock(NotificationQueueService.class),
        new ObjectMapper());

    UUID taskId = UUID.randomUUID();
    Instant bookedAt = Instant.now().minusSeconds(7200);
    TaskEntity heldForReview = new TaskEntity();
    heldForReview.setId(taskId);
    heldForReview.setBuyerId(UUID.randomUUID());
    heldForReview.setTitle("Collect a parcel");
    heldForReview.setDescription("Pick up from the courier office");
    heldForReview.setStatus(TaskStatus.ADMIN_REVIEW);
    heldForReview.setBudgetPaise(20_000L);
    heldForReview.setSearchingStartedAt(bookedAt);
    when(tasks.findById(taskId)).thenReturn(Optional.of(heldForReview));

    admin.approveTask(taskId, "support", "looks fine");

    assertEquals(TaskStatus.SEARCHING, heldForReview.getStatus());
    assertTrue(heldForReview.getSearchingStartedAt().isAfter(bookedAt.plusSeconds(3600)),
        "search clock must be reset on release, otherwise the next cleanup tick "
            + "cancels the task within 30 seconds");
  }

  // ─── cleanup ordering and backoff ─────────────────────────────────────────

  /**
   * Cancelling must run before re-dispatch. The reverse order pushed a fresh wave
   * of offers and then cancelled the task in the same commit.
   */
  @Test
  void cleanupCancelsBeforeRedispatching() {
    TaskRepository tasks = mock(TaskRepository.class);
    TaskOfferRepository offers = mock(TaskOfferRepository.class);
    NotificationQueueService matchingQueue = mock(NotificationQueueService.class);
    TaskStaleCleanupJob job = cleanupJob(tasks, offers, matchingQueue);

    when(tasks.findTimedOutSearchingTasks(eq(TaskStatus.SEARCHING), any(), any()))
        .thenReturn(List.of());
    when(tasks.findTop100ByStatusAndUpdatedAtBefore(eq(TaskStatus.ASSIGNED), any()))
        .thenReturn(List.of());

    job.runCleanup();

    // expireLapsedOffers, then the cancel pass (200-row page), then re-dispatch
    // (50-row page). Verified through the page sizes, which differ per pass.
    var order = inOrder(offers, tasks);
    order.verify(offers).expireLapsedOffers(any());
    order.verify(tasks).findTimedOutSearchingTasks(
        eq(TaskStatus.SEARCHING), any(), eq(org.springframework.data.domain.PageRequest.of(0, 200)));
    order.verify(tasks).findTimedOutSearchingTasks(
        eq(TaskStatus.SEARCHING), any(), eq(org.springframework.data.domain.PageRequest.of(0, 50)));
  }

  /**
   * A task nobody can serve has no live offers, so without a backoff it was
   * re-dispatched on every 30-second tick for its whole search window — roughly a
   * hundred Redis commands each time.
   */
  @Test
  void doesNotRedispatchATaskInsideItsBackoffWindow() {
    TaskRepository tasks = mock(TaskRepository.class);
    TaskOfferRepository offers = mock(TaskOfferRepository.class);
    NotificationQueueService matchingQueue = mock(NotificationQueueService.class);
    TaskStaleCleanupJob job = cleanupJob(tasks, offers, matchingQueue);

    TaskEntity justDispatched = searchingTask();
    justDispatched.setDispatchWave(1);
    justDispatched.setLastDispatchedAt(Instant.now().minusSeconds(5));

    when(tasks.findTimedOutSearchingTasks(
            eq(TaskStatus.SEARCHING), any(), eq(org.springframework.data.domain.PageRequest.of(0, 200))))
        .thenReturn(List.of());
    when(tasks.findTimedOutSearchingTasks(
            eq(TaskStatus.SEARCHING), any(), eq(org.springframework.data.domain.PageRequest.of(0, 50))))
        .thenReturn(List.of(justDispatched));
    when(offers.findTaskIdsWithLiveOffers(anyList(), any())).thenReturn(List.of());
    when(tasks.findTop100ByStatusAndUpdatedAtBefore(eq(TaskStatus.ASSIGNED), any()))
        .thenReturn(List.of());

    job.runCleanup();

    verify(matchingQueue, never()).enqueueMatchingDispatch(any());
  }

  @Test
  void redispatchesOnceTheBackoffWindowHasElapsed() {
    TaskRepository tasks = mock(TaskRepository.class);
    TaskOfferRepository offers = mock(TaskOfferRepository.class);
    NotificationQueueService matchingQueue = mock(NotificationQueueService.class);
    TaskStaleCleanupJob job = cleanupJob(tasks, offers, matchingQueue);

    TaskEntity waiting = searchingTask();
    waiting.setDispatchWave(0);
    // Backoff at wave 0 is one offer window (45s in the test properties).
    waiting.setLastDispatchedAt(Instant.now().minusSeconds(120));

    when(tasks.findTimedOutSearchingTasks(
            eq(TaskStatus.SEARCHING), any(), eq(org.springframework.data.domain.PageRequest.of(0, 200))))
        .thenReturn(List.of());
    when(tasks.findTimedOutSearchingTasks(
            eq(TaskStatus.SEARCHING), any(), eq(org.springframework.data.domain.PageRequest.of(0, 50))))
        .thenReturn(List.of(waiting));
    when(offers.findTaskIdsWithLiveOffers(anyList(), any())).thenReturn(List.of());
    when(tasks.findTop100ByStatusAndUpdatedAtBefore(eq(TaskStatus.ASSIGNED), any()))
        .thenReturn(List.of());

    job.runCleanup();

    verify(matchingQueue).enqueueMatchingDispatch(waiting);
  }

  // ─── fixtures ─────────────────────────────────────────────────────────────

  private static TaskEntity searchingTask() {
    TaskEntity task = new TaskEntity();
    task.setId(UUID.randomUUID());
    task.setBuyerId(UUID.randomUUID());
    task.setTitle("Queue at the electricity office");
    task.setDescription("Pay the bill and bring the receipt");
    task.setBudgetPaise(15_000L);
    task.setLat(17.3850);
    task.setLng(78.4867);
    task.setStatus(TaskStatus.SEARCHING);
    return task;
  }

  private static TaskStaleCleanupJob cleanupJob(
      TaskRepository tasks, TaskOfferRepository offers, NotificationQueueService matchingQueue) {
    SchedulerLock lock = mock(SchedulerLock.class);
    return new TaskStaleCleanupJob(
        lock,
        tasks,
        offers,
        matchingQueue,
        mock(SupportService.class),
        mock(RealtimePublisher.class),
        mock(PaymentLifecycleService.class),
        TestAppProperties.defaults(),
        20L,
        720L);
  }
}
