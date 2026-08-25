package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.AdminModerationDetailDto;
import com.helpinminutes.api.moderation.dto.AdminModerationTaskDto;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import com.helpinminutes.api.moderation.llm.LlmClient;
import com.helpinminutes.api.moderation.service.AdminModerationService;
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
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class AiModerationQueueIntegrationTest {

  private TaskRepository taskRepository;
  private TaskAiReviewRepository aiReviewRepository;
  private TaskAuditLogRepository auditLogRepository;
  private UserRepository userRepository;
  private LlmClient llmClient;
  private ModerationDecisionEngine decisionEngine;
  private NotificationQueueService matchingQueue;
  private RealtimePublisher realtime;
  private PushNotificationService pushNotifications;
  private ObjectMapper objectMapper;
  private SimpleMeterRegistry meterRegistry;

  private AiTaskModerationService moderationService;
  private AdminModerationService adminModerationService;

  @BeforeEach
  void setUp() {
    taskRepository = mock(TaskRepository.class);
    aiReviewRepository = mock(TaskAiReviewRepository.class);
    auditLogRepository = mock(TaskAuditLogRepository.class);
    userRepository = mock(UserRepository.class);
    llmClient = mock(LlmClient.class);
    // A real decision engine and screener: the point of this test is the whole
    // pipeline from task text to admin queue row, and stubbing the engine would skip
    // the tier decision that determines whether a queue row exists at all.
    decisionEngine = new ModerationDecisionEngine(
        new com.helpinminutes.api.tasks.service.TaskModerationService());
    matchingQueue = mock(NotificationQueueService.class);
    realtime = mock(RealtimePublisher.class);
    pushNotifications = mock(PushNotificationService.class);
    objectMapper = new ObjectMapper();
    meterRegistry = new SimpleMeterRegistry();

    moderationService = new AiTaskModerationService(
        taskRepository,
        aiReviewRepository,
        auditLogRepository,
        llmClient,
        decisionEngine,
        matchingQueue,
        realtime,
        pushNotifications,
        objectMapper,
        meterRegistry,
        new com.helpinminutes.api.moderation.service.ModerationResultCache(null, objectMapper)
    );

    com.helpinminutes.api.helpers.repo.HelperProfileRepository helperProfiles = mock(com.helpinminutes.api.helpers.repo.HelperProfileRepository.class);
    adminModerationService = new AdminModerationService(
        taskRepository,
        aiReviewRepository,
        auditLogRepository,
        userRepository,
        matchingQueue,
        objectMapper,
        helperProfiles,
        realtime
    );
  }

  @Test
  void testEndToEndTaskModerationToAdminQueueRecord() {
    // 1. Create a task with a phone number leak
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    UUID helperId = UUID.randomUUID();

    TaskEntity task = new TaskEntity();
    task.setId(taskId);
    task.setBuyerId(buyerId);
    task.setTitle("Grocery pickup - call 9876543210");
    task.setDescription("Buy groceries and call me at 9876543210");
    task.setBudgetPaise(25000L);
    task.setStatus(TaskStatus.AI_PENDING);

    UserEntity buyer = new UserEntity();
    buyer.setId(buyerId);
    buyer.setDisplayName("John Doe");
    buyer.setPhone("+919876543210");
    buyer.setEmail("john@example.com");

    UserEntity helper = new UserEntity();
    helper.setId(helperId);
    helper.setDisplayName("Super Helper");
    helper.setPhone("+919876543211");
    helper.setRole(com.helpinminutes.api.users.model.UserRole.HELPER);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(userRepository.findById(buyerId)).thenReturn(Optional.of(buyer));
    when(userRepository.findById(helperId)).thenReturn(Optional.of(helper));
    // The moderation queue batches buyer lookups across the page.
    when(userRepository.findAllById(anyIterable())).thenReturn(List.of(buyer));

    // 2. Fire TaskCreatedEvent — routes directly to ADMIN_REVIEW in manual mode
    TaskCreatedEvent event = new TaskCreatedEvent(taskId, true);
    moderationService.handleTaskCreatedEvent(event);

    // 3. Verify task status updated to ADMIN_REVIEW without calling LLM
    verify(taskRepository).save(task);
    assertEquals(TaskStatus.ADMIN_REVIEW, task.getStatus());
    verify(llmClient, never()).evaluateTask(any());

    // 4. Query Admin Moderation Queue
    when(taskRepository.findByStatus(eq(TaskStatus.ADMIN_REVIEW), any())).thenReturn(new PageImpl<>(List.of(task)));
    when(aiReviewRepository.findTopByTaskIdOrderByCreatedAtDesc(taskId)).thenReturn(Optional.empty());
    when(aiReviewRepository.findByTaskIdInOrderByCreatedAtDesc(anyCollection()))
        .thenReturn(List.of());

    Page<AdminModerationTaskDto> queuePage = adminModerationService.getModerationQueue("ADMIN_REVIEW", PageRequest.of(0, 10));

    assertNotNull(queuePage);
    assertEquals(1, queuePage.getTotalElements());

    AdminModerationTaskDto item = queuePage.getContent().get(0);
    assertEquals(taskId, item.taskId());
    assertEquals("John Doe", item.customerName());
    assertEquals("+919876543210", item.customerPhone());
    assertEquals("ADMIN_REVIEW", item.status());

    // 5. Test Admin Task Detail Retrieval
    AdminModerationDetailDto detail = adminModerationService.getTaskDetail(taskId);
    assertNotNull(detail);
    assertEquals(taskId, detail.taskId());
    assertEquals("John Doe", detail.customerName());

    // 6. Test Admin Approval Action -> SEARCHING
    adminModerationService.approveTask(taskId, "support_admin", "Approved by customer care");
    assertEquals(TaskStatus.SEARCHING, task.getStatus());
    verify(matchingQueue).enqueueMatchingDispatch(task);
  }
}

