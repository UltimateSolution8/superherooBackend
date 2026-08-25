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

  // ─── TEMP: MANUAL_MODERATION_MODE tests ───────────────────────────────────

  @Test
  void routesOrdinaryErrandDirectlyToAdminReviewWithoutCallingTheModel() {
    TaskEntity task = pendingTask("Deliver groceries", "Buy milk, eggs and bread");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    assertEquals(TaskStatus.ADMIN_REVIEW, task.getStatus());
    verify(llmClient, never()).evaluateTask(any());
    verify(aiReviewRepository, never()).save(any(TaskAiReviewEntity.class));
    verify(matchingQueue, never()).enqueueMatchingDispatch(any(), anyBoolean());
    verify(pushNotifications).notifyBuyerTaskUnderReview(task.getBuyerId(), task);
  }

  @Test
  void routesHardPolicyMatchesToAdminReviewInsteadOfAutoCancelling() {
    TaskEntity task = pendingTask("Party", "Get me some ganja for tonight");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    // In manual mode, policy matches are not auto-cancelled; they go to ADMIN_REVIEW for human decision
    assertEquals(TaskStatus.ADMIN_REVIEW, task.getStatus());
    verify(llmClient, never()).evaluateTask(any());
    verify(matchingQueue, never()).enqueueMatchingDispatch(any(), anyBoolean());
    verify(pushNotifications).notifyBuyerTaskUnderReview(task.getBuyerId(), task);
  }

  @Test
  void routesAmbiguousTasksToAdminReviewWithoutCallingModel() {
    TaskEntity task = pendingTask("Pharmacy", "Buy cough syrup with low alcohol content");
    when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

    service.handleTaskCreatedEvent(new TaskCreatedEvent(task.getId(), true));

    verify(llmClient, never()).evaluateTask(any());
    assertEquals(TaskStatus.ADMIN_REVIEW, task.getStatus());
    verify(pushNotifications).notifyBuyerTaskUnderReview(task.getBuyerId(), task);
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
