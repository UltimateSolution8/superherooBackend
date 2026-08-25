package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.moderation.dto.AdminModerationDetailDto;
import com.helpinminutes.api.moderation.dto.AdminModerationTaskDto;
import com.helpinminutes.api.moderation.service.AdminModerationService;
import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import com.helpinminutes.api.tasks.model.TaskAuditLogEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskAiReviewRepository;
import com.helpinminutes.api.tasks.repo.TaskAuditLogRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class AdminModerationServiceTest {

  private TaskRepository taskRepository;
  private TaskAiReviewRepository aiReviewRepository;
  private TaskAuditLogRepository auditLogRepository;
  private UserRepository userRepository;
  private NotificationQueueService matchingQueue;
  private ObjectMapper objectMapper;
  private AdminModerationService adminModerationService;

  @BeforeEach
  void setUp() {
    taskRepository = mock(TaskRepository.class);
    aiReviewRepository = mock(TaskAiReviewRepository.class);
    auditLogRepository = mock(TaskAuditLogRepository.class);
    userRepository = mock(UserRepository.class);
    matchingQueue = mock(NotificationQueueService.class);
    objectMapper = new ObjectMapper();

    adminModerationService = new AdminModerationService(
        taskRepository,
        aiReviewRepository,
        auditLogRepository,
        userRepository,
        matchingQueue,
        objectMapper
    );
  }

  @Test
  void getModerationQueueReturnsPendingReviewTasks() {
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();

    TaskEntity task = new TaskEntity();
    task.setId(taskId);
    task.setBuyerId(buyerId);
    task.setTitle("Flagged task");
    task.setDescription("Call 9999999999 for cash deal");
    task.setStatus(TaskStatus.ADMIN_REVIEW);
    task.setBudgetPaise(50000L);

    UserEntity buyer = new UserEntity();
    buyer.setId(buyerId);
    buyer.setDisplayName("Test Buyer");
    buyer.setPhone("+919876543210");

    TaskAiReviewEntity aiReview = new TaskAiReviewEntity();
    aiReview.setTaskId(taskId);
    aiReview.setStatus("REVIEW");
    aiReview.setConfidence(60);
    aiReview.setRiskScore(85);
    aiReview.setQualityScore(30);
    aiReview.setFlags("[\"CONTACT_LEAK\"]");
    aiReview.setReasons("[\"Phone number detected in description\"]");
    aiReview.setModel("moonshotai/kimi-k3-free");

    when(taskRepository.findByStatus(eq(TaskStatus.ADMIN_REVIEW), any())).thenReturn(new PageImpl<>(List.of(task)));
    // The queue batches both lookups across the whole page rather than issuing
    // two queries per row.
    when(userRepository.findAllById(anyIterable())).thenReturn(List.of(buyer));
    when(aiReviewRepository.findByTaskIdInOrderByCreatedAtDesc(anyCollection()))
        .thenReturn(List.of(aiReview));

    Page<AdminModerationTaskDto> page = adminModerationService.getModerationQueue("ADMIN_REVIEW", PageRequest.of(0, 10));

    assertNotNull(page);
    assertEquals(1, page.getTotalElements());
    AdminModerationTaskDto dto = page.getContent().get(0);
    assertEquals(taskId, dto.taskId());
    assertEquals("Test Buyer", dto.customerName());
    assertEquals("Flagged task", dto.title());
    assertEquals("ADMIN_REVIEW", dto.status());
    assertEquals(85, dto.riskScore());
    assertTrue(dto.flags().contains("CONTACT_LEAK"));
  }

  @Test
  void getTaskDetailReturnsFullReviewBreakdown() {
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();

    TaskEntity task = new TaskEntity();
    task.setId(taskId);
    task.setBuyerId(buyerId);
    task.setTitle("Queue standing task");
    task.setDescription("Stand in line at electricity office");
    task.setStatus(TaskStatus.ADMIN_REVIEW);

    UserEntity buyer = new UserEntity();
    buyer.setId(buyerId);
    buyer.setDisplayName("Queue Customer");

    TaskAiReviewEntity aiReview = new TaskAiReviewEntity();
    aiReview.setTaskId(taskId);
    aiReview.setStatus("REVIEW");
    aiReview.setRawResponse("{\"status\":\"REVIEW\"}");

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    // Single-task detail, so these stay per-row lookups — only the paginated
    // queue was batched.
    when(userRepository.findById(buyerId)).thenReturn(Optional.of(buyer));
    when(aiReviewRepository.findTopByTaskIdOrderByCreatedAtDesc(taskId)).thenReturn(Optional.of(aiReview));
    when(auditLogRepository.findByTaskIdOrderByTimestampDesc(taskId)).thenReturn(List.of());

    AdminModerationDetailDto detail = adminModerationService.getTaskDetail(taskId);

    assertNotNull(detail);
    assertEquals(taskId, detail.taskId());
    assertEquals("Queue standing task", detail.title());
    assertEquals("Queue Customer", detail.customerName());
  }

  @Test
  void approveTaskChangesStatusToSearchingAndDispatchesOffers() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    task.setId(taskId);
    task.setTitle("Help move boxes");
    task.setStatus(TaskStatus.ADMIN_REVIEW);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

    AdminModerationTaskDto result = adminModerationService.approveTask(taskId, "admin_user", "Verified safe");

    verify(taskRepository).save(task);
    assertEquals(TaskStatus.SEARCHING, task.getStatus());
    verify(auditLogRepository).save(any(TaskAuditLogEntity.class));
    verify(matchingQueue).enqueueMatchingDispatch(task);
    assertNotNull(result);
  }

  @Test
  void rejectTaskCancelsTaskWithReason() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    task.setId(taskId);
    task.setTitle("Prohibited illegal item delivery");
    task.setStatus(TaskStatus.ADMIN_REVIEW);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

    AdminModerationTaskDto result = adminModerationService.rejectTask(taskId, "admin_user", "Violates platform policy");

    verify(taskRepository).save(task);
    assertEquals(TaskStatus.CANCELLED, task.getStatus());
    assertEquals("Violates platform policy", task.getCancelReason());
    assertEquals("ADMIN", task.getCancelledByRole());
    verify(auditLogRepository).save(any(TaskAuditLogEntity.class));
    assertNotNull(result);
  }

  @Test
  void editAndApproveTaskUpdatesFieldsAndDispatchesOffers() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    task.setId(taskId);
    task.setTitle("Original title with phone 9876543210");
    task.setDescription("Original description");
    task.setStatus(TaskStatus.ADMIN_REVIEW);

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

    AdminModerationTaskDto result = adminModerationService.editAndApproveTask(
        taskId,
        "Cleaned Title",
        "Cleaned Description without phone number",
        "admin_user",
        "Removed phone number"
    );

    verify(taskRepository).save(task);
    assertEquals("Cleaned Title", task.getTitle());
    assertEquals("Cleaned Description without phone number", task.getDescription());
    assertEquals(TaskStatus.SEARCHING, task.getStatus());
    verify(matchingQueue).enqueueMatchingDispatch(task);
    assertNotNull(result);
  }

  @Test
  void approveAndAssignTaskAssignsDirectlyToHelperAndNotifies() {
    UUID taskId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    UUID helperId = UUID.randomUUID();
    TaskEntity task = new TaskEntity();
    task.setId(taskId);
    task.setBuyerId(buyerId);
    task.setTitle("Direct assign task");
    task.setStatus(TaskStatus.ADMIN_REVIEW);

    UserEntity helper = new UserEntity();
    helper.setId(helperId);
    helper.setRole(com.helpinminutes.api.users.model.UserRole.HELPER);

    com.helpinminutes.api.helpers.model.HelperProfileEntity profile = new com.helpinminutes.api.helpers.model.HelperProfileEntity();
    profile.setUserId(helperId);
    profile.setKycStatus(com.helpinminutes.api.helpers.model.HelperKycStatus.APPROVED);

    com.helpinminutes.api.helpers.repo.HelperProfileRepository helperProfiles = mock(com.helpinminutes.api.helpers.repo.HelperProfileRepository.class);
    com.helpinminutes.api.realtime.RealtimePublisher realtime = mock(com.helpinminutes.api.realtime.RealtimePublisher.class);

    AdminModerationService serviceWithHelpers = new AdminModerationService(
        taskRepository,
        aiReviewRepository,
        auditLogRepository,
        userRepository,
        matchingQueue,
        objectMapper,
        helperProfiles,
        realtime
    );

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(userRepository.findById(helperId)).thenReturn(Optional.of(helper));
    when(helperProfiles.findById(helperId)).thenReturn(Optional.of(profile));

    AdminModerationTaskDto result = serviceWithHelpers.approveAndAssignTask(
        taskId, helperId, "admin_user", "Assigned directly to helper"
    );

    verify(taskRepository).save(task);
    assertEquals(TaskStatus.ASSIGNED, task.getStatus());
    assertEquals(helperId, task.getAssignedHelperId());
    verify(matchingQueue).enqueueTaskOffered(List.of(helperId), task);
    verify(realtime).publish(eq("task_assigned"), anyMap());
    assertNotNull(result);
  }

  @Test
  void listApprovedHelpersReturnsOnlyKycApprovedHelpers() {
    UUID helperId = UUID.randomUUID();
    com.helpinminutes.api.helpers.model.HelperProfileEntity profile = new com.helpinminutes.api.helpers.model.HelperProfileEntity();
    profile.setUserId(helperId);
    profile.setKycStatus(com.helpinminutes.api.helpers.model.HelperKycStatus.APPROVED);

    UserEntity user = new UserEntity();
    user.setId(helperId);
    user.setDisplayName("Approved Hero");
    user.setPhone("+919999988888");

    com.helpinminutes.api.helpers.repo.HelperProfileRepository helperProfiles = mock(com.helpinminutes.api.helpers.repo.HelperProfileRepository.class);
    when(helperProfiles.findAllByKycStatusOrderByCreatedAtAsc(com.helpinminutes.api.helpers.model.HelperKycStatus.APPROVED))
        .thenReturn(List.of(profile));
    when(userRepository.findAllById(anyCollection())).thenReturn(List.of(user));

    AdminModerationService serviceWithHelpers = new AdminModerationService(
        taskRepository,
        aiReviewRepository,
        auditLogRepository,
        userRepository,
        matchingQueue,
        objectMapper,
        helperProfiles,
        mock(com.helpinminutes.api.realtime.RealtimePublisher.class)
    );

    List<java.util.Map<String, Object>> helpers = serviceWithHelpers.listApprovedHelpers();
    assertEquals(1, helpers.size());
    assertEquals(helperId.toString(), helpers.get(0).get("helperId"));
    assertEquals("Approved Hero", helpers.get(0).get("name"));
    assertEquals("+919999988888", helpers.get(0).get("phone"));
  }
}
