package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import com.helpinminutes.api.moderation.llm.LlmClient;
import com.helpinminutes.api.moderation.service.AiTaskModerationService;
import com.helpinminutes.api.moderation.service.ModerationDecisionEngine;
import com.helpinminutes.api.moderation.service.ModerationResultCache;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.event.TaskCreatedEvent;
import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskAiReviewRepository;
import com.helpinminutes.api.tasks.repo.TaskAuditLogRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.service.TaskModerationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end behaviour of the moderation cascade.
 *
 * <p>Uses a real {@link ModerationDecisionEngine} and {@link TaskModerationService}
 * rather than mocks: the whole point of the change is which tier a given piece of
 * text lands in, and a stubbed decision engine would assert nothing about that.
 */
class AiTaskModerationServiceTest {

  private TaskRepository taskRepository;
  private TaskAiReviewRepository aiReviewRepository;
  private LlmClient llmClient;
  private NotificationQueueService matchingQueue;
  private PushNotificationService pushNotifications;
  private RecordingCache resultCache;
  private AiTaskModerationService service;

  @BeforeEach
  void setUp() {
    taskRepository = mock(TaskRepository.class);
    aiReviewRepository = mock(TaskAiReviewRepository.class);
    llmClient = mock(LlmClient.class);
    matchingQueue = mock(NotificationQueueService.class);
    pushNotifications = mock(PushNotificationService.class);
    resultCache = new RecordingCache();

    service = new AiTaskModerationService(
        taskRepository,
        aiReviewRepository,
        mock(TaskAuditLogRepository.class),
        llmClient,
        new ModerationDecisionEngine(new TaskModerationService()),
        matchingQueue,
        mock(RealtimePublisher.class),
        pushNotifications,
        new ObjectMapper(),
        new SimpleMeterRegistry(),
        resultCache);
  }

  // ─── the fast paths: no model call at all ─────────────────────────────────

  /**
   * The cost fix. An ordinary errand must be approved locally. Every task used to go
   * to the model, on the request thread, with a worst case around 16 seconds.
   */
  @Test
  void approvesAnOrdinaryErrandWithoutCallingTheModel() {
    TaskEntity task = pendingTask("Deliver groceries", "Buy milk, eggs and bread");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    assertEquals(TaskStatus.SEARCHING, task.getStatus());
    verify(llmClient, never()).evaluateTask(any());
    // No verdict row either: there was no verdict to record.
    verify(aiReviewRepository, never()).save(any(TaskAiReviewEntity.class));
    verify(matchingQueue).enqueueMatchingDispatch(task, true);
  }

  @Test
  void doesNotCallTheModelForAHardPolicyMatch() {
    TaskEntity task = pendingTask("Party", "Get me some ganja for tonight");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    // Rejected outright rather than parked in review: there is nothing for a
    // moderator to weigh up, and the citizen should not be left waiting.
    assertEquals(TaskStatus.CANCELLED, task.getStatus());
    verify(llmClient, never()).evaluateTask(any());
    verify(matchingQueue, never()).enqueueMatchingDispatch(any(), anyBoolean());
  }

  /** The citizen must be told why, without being told which word tripped it. */
  @Test
  void aBlockedTaskCarriesAReadableCancelReason() {
    TaskEntity task = pendingTask("Self defence", "Buy a pistol and ammunition");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    assertEquals("SYSTEM", task.getCancelledByRole());
    org.junit.jupiter.api.Assertions.assertNotNull(task.getCancelReason());
    org.junit.jupiter.api.Assertions.assertFalse(
        task.getCancelReason().toLowerCase().contains("pistol"),
        "the reason must not echo the matched term back to the citizen");
  }

  // ─── the escalated minority ───────────────────────────────────────────────

  @Test
  void asksTheModelOnlyWhenTheTextIsAmbiguous() {
    TaskEntity task = pendingTask("Pharmacy", "Buy cough syrup with low alcohol content");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
    when(llmClient.evaluateTask(any(TaskModerationPayload.class))).thenReturn(approved());

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    verify(llmClient).evaluateTask(any(TaskModerationPayload.class));
    assertEquals(TaskStatus.SEARCHING, task.getStatus());
    verify(aiReviewRepository).save(any(TaskAiReviewEntity.class));
  }

  @Test
  void routesAnAmbiguousTaskTheModelDoubtsToAdminReview() {
    TaskEntity task = pendingTask("Errand", "Bring me a bottle of whisky from the shop");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
    when(llmClient.evaluateTask(any(TaskModerationPayload.class))).thenReturn(
        new AIReviewResult("REVIEW", 95, 80, 40,
            List.of("Alcohol delivery is licensed in Telangana"), List.of("EXCISE"), true,
            "{}", "gemini-2.5-flash-lite", 150L));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    assertEquals(TaskStatus.ADMIN_REVIEW, task.getStatus());
    verify(matchingQueue, never()).enqueueMatchingDispatch(any(), anyBoolean());
    // The citizen used to get no signal at all that their booking was held.
    verify(pushNotifications).notifyBuyerTaskUnderReview(task.getBuyerId(), task);
  }

  /** The model's opinion cannot overturn a hard policy match found locally. */
  @Test
  void modelApprovalCannotOverrideAHardPolicyMatch() {
    TaskEntity task = pendingTask("Documents", "Get me a fake Aadhaar card");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    assertEquals(TaskStatus.CANCELLED, task.getStatus());
    verify(llmClient, never()).evaluateTask(any());
  }

  // ─── caching ──────────────────────────────────────────────────────────────

  /**
   * Identical text was re-billed on every booking, every bulk-row retry, and again
   * on prepaid activation, which moderates the same task twice.
   */
  @Test
  void reusesAPreviousVerdictForIdenticalText() {
    when(llmClient.evaluateTask(any(TaskModerationPayload.class))).thenReturn(approved());

    for (int i = 0; i < 3; i++) {
      TaskEntity task = pendingTask("Pharmacy", "Buy cough syrup with low alcohol content");
      when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
      service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));
    }

    verify(llmClient, times(1)).evaluateTask(any(TaskModerationPayload.class));
  }

  /** A synthetic outage verdict must not be pinned in the cache for a month. */
  @Test
  void doesNotCacheAFallbackVerdict() {
    AIReviewResult fallback = new AIReviewResult(
        "APPROVED", 85, 10, 80,
        List.of("providers unreachable"), Collections.emptyList(), false,
        "{}", "fallback-fail-safe", 0L);
    when(llmClient.evaluateTask(any(TaskModerationPayload.class))).thenReturn(fallback);

    for (int i = 0; i < 2; i++) {
      TaskEntity task = pendingTask("Pharmacy", "Buy cough syrup with low alcohol content");
      when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
      service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));
    }

    verify(llmClient, times(2)).evaluateTask(any(TaskModerationPayload.class));
  }

  @Test
  void skipsATaskThatIsNoLongerPending() {
    TaskEntity task = pendingTask("Cleaning", "Clean my kitchen");
    task.setStatus(TaskStatus.SEARCHING);
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    verify(llmClient, never()).evaluateTask(any());
    verify(matchingQueue, never()).enqueueMatchingDispatch(any(), anyBoolean());
  }

  // ─── fixtures ─────────────────────────────────────────────────────────────

  private static AIReviewResult approved() {
    return new AIReviewResult(
        "APPROVED", 98, 5, 95,
        List.of("Legitimate errand"), Collections.emptyList(), false,
        "{}", "gemini-2.5-flash-lite", 120L);
  }

  private static TaskEntity pendingTask(String title, String description) {
    TaskEntity task = new TaskEntity();
    task.setId(UUID.randomUUID());
    task.setBuyerId(UUID.randomUUID());
    task.setTitle(title);
    task.setDescription(description);
    task.setBudgetPaise(20_000L);
    task.setStatus(TaskStatus.AI_PENDING);
    return task;
  }

  /** In-memory stand-in for the Redis-backed cache, with the same keying rules. */
  private static final class RecordingCache extends ModerationResultCache {
    private final Map<String, AIReviewResult> entries = new HashMap<>();

    RecordingCache() {
      super(null, new ObjectMapper());
    }

    @Override
    public Optional<AIReviewResult> get(String title, String description) {
      return Optional.ofNullable(entries.get(key(title, description)));
    }

    @Override
    public void put(String title, String description, AIReviewResult result) {
      if (result == null) return;
      if (result.modelUsed() != null && result.modelUsed().startsWith("fallback")) return;
      entries.put(key(title, description), result);
    }

    private static String key(String title, String description) {
      return (title + "|" + description).toLowerCase();
    }
  }
}
