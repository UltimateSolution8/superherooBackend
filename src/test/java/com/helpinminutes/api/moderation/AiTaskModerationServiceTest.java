package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import com.helpinminutes.api.moderation.llm.LlmClient;
import com.helpinminutes.api.moderation.service.AiTaskModerationService;
import com.helpinminutes.api.moderation.service.ModerationDecisionEngine;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.event.TaskCreatedEvent;
import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskAiReviewRepository;
import com.helpinminutes.api.tasks.repo.TaskAuditLogRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiTaskModerationServiceTest {

  private TaskRepository taskRepository;
  private TaskAiReviewRepository aiReviewRepository;
  private TaskAuditLogRepository auditLogRepository;
  private LlmClient llmClient;
  private ModerationDecisionEngine decisionEngine;
  private MatchingService matchingService;
  private RealtimePublisher realtime;
  private PushNotificationService pushNotifications;
  private ObjectMapper objectMapper;
  private SimpleMeterRegistry meterRegistry;
  private AiTaskModerationService service;

  @BeforeEach
  void setUp() {
    taskRepository = mock(TaskRepository.class);
    aiReviewRepository = mock(TaskAiReviewRepository.class);
    auditLogRepository = mock(TaskAuditLogRepository.class);
    llmClient = mock(LlmClient.class);
    decisionEngine = mock(ModerationDecisionEngine.class);
    matchingService = mock(MatchingService.class);
    realtime = mock(RealtimePublisher.class);
    pushNotifications = mock(PushNotificationService.class);
    objectMapper = new ObjectMapper();
    meterRegistry = new SimpleMeterRegistry();

    service = new AiTaskModerationService(
        taskRepository,
        aiReviewRepository,
        auditLogRepository,
        llmClient,
        decisionEngine,
        matchingService,
        realtime,
        pushNotifications,
        objectMapper,
        meterRegistry
    );
  }

  @Test
  void handlesTaskCreatedEventAndApprovesSafeTask() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    task.setBuyerId(UUID.randomUUID());
    task.setTitle("Deliver groceries");
    task.setDescription("Buy milk, eggs and bread");
    task.setStatus(TaskStatus.AI_PENDING);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(decisionEngine.runLocalPreCheck(any(), any())).thenReturn(Collections.emptyList());

    AIReviewResult aiResult = new AIReviewResult(
        "APPROVED", 98, 5, 95,
        List.of("Legitimate task"), Collections.emptyList(), false,
        "{}", "z-ai/glm-4.7-flash-free", 120L
    );
    when(llmClient.evaluateTask(any(TaskModerationPayload.class))).thenReturn(aiResult);
    when(decisionEngine.determineStatus(eq(aiResult), anyList())).thenReturn(TaskStatus.AI_APPROVED);

    TaskCreatedEvent event = new TaskCreatedEvent(taskId, true);
    service.handleTaskCreatedEvent(event);

    verify(aiReviewRepository).save(any(TaskAiReviewEntity.class));
    verify(taskRepository).save(task);
    assertEquals(TaskStatus.SEARCHING, task.getStatus());
    verify(matchingService).dispatchOffers(task, true);
  }

  @Test
  void handlesTaskCreatedEventAndSendsIllegalTaskToAdminReview() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    task.setBuyerId(UUID.randomUUID());
    task.setTitle("Buy whiskey");
    task.setDescription("Liquor delivery");
    task.setStatus(TaskStatus.AI_PENDING);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(decisionEngine.runLocalPreCheck(any(), any())).thenReturn(Collections.emptyList());

    AIReviewResult aiResult = new AIReviewResult(
        "REVIEW", 95, 80, 40,
        List.of("Alcohol delivery prohibited"), List.of("ALCOHOL_DELIVERY"), true,
        "{}", "z-ai/glm-4.7-flash-free", 150L
    );
    when(llmClient.evaluateTask(any(TaskModerationPayload.class))).thenReturn(aiResult);
    when(decisionEngine.determineStatus(eq(aiResult), anyList())).thenReturn(TaskStatus.ADMIN_REVIEW);

    TaskCreatedEvent event = new TaskCreatedEvent(taskId, true);
    service.handleTaskCreatedEvent(event);

    verify(aiReviewRepository).save(any(TaskAiReviewEntity.class));
    verify(taskRepository).save(task);
    assertEquals(TaskStatus.ADMIN_REVIEW, task.getStatus());
    verify(matchingService, never()).dispatchOffers(any(), anyBoolean());
  }
}
