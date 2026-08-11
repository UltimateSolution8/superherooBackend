package com.helpinminutes.api.moderation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.moderation.dto.*;
import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import com.helpinminutes.api.tasks.model.TaskAuditLogEntity;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskAiReviewRepository;
import com.helpinminutes.api.tasks.repo.TaskAuditLogRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminModerationService {

  private static final Logger log = LoggerFactory.getLogger(AdminModerationService.class);

  private final TaskRepository taskRepository;
  private final TaskAiReviewRepository aiReviewRepository;
  private final TaskAuditLogRepository auditLogRepository;
  private final UserRepository userRepository;
  private final MatchingService matchingService;
  private final ObjectMapper objectMapper;

  public AdminModerationService(
      TaskRepository taskRepository,
      TaskAiReviewRepository aiReviewRepository,
      TaskAuditLogRepository auditLogRepository,
      UserRepository userRepository,
      MatchingService matchingService,
      ObjectMapper objectMapper) {
    this.taskRepository = taskRepository;
    this.aiReviewRepository = aiReviewRepository;
    this.auditLogRepository = auditLogRepository;
    this.userRepository = userRepository;
    this.matchingService = matchingService;
    this.objectMapper = objectMapper;
  }

  public Page<AdminModerationTaskDto> getModerationQueue(String statusFilter, Pageable pageable) {
    Page<TaskEntity> tasksPage;

    if (statusFilter == null || statusFilter.isBlank() || "ALL".equalsIgnoreCase(statusFilter)) {
      tasksPage = taskRepository.findAllByOrderByCreatedAtDesc(pageable);
    } else if ("ADMIN_APPROVED".equalsIgnoreCase(statusFilter)) {
      tasksPage = taskRepository.findByStatus(TaskStatus.SEARCHING, pageable);
    } else if ("ADMIN_REJECTED".equalsIgnoreCase(statusFilter)) {
      tasksPage = taskRepository.findByStatus(TaskStatus.CANCELLED, pageable);
    } else {
      try {
        TaskStatus status = TaskStatus.valueOf(statusFilter.toUpperCase());
        tasksPage = taskRepository.findByStatus(status, pageable);
      } catch (IllegalArgumentException e) {
        tasksPage = taskRepository.findAllByOrderByCreatedAtDesc(pageable);
      }
    }

    // Both lookups are batched ahead of the mapping loop. Doing them inside it
    // cost two queries per row — 101 round trips for a 50-row admin page.
    List<TaskEntity> pageTasks = tasksPage.getContent();

    java.util.Set<UUID> buyerIds = pageTasks.stream()
        .map(TaskEntity::getBuyerId)
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.toSet());
    java.util.Map<UUID, UserEntity> buyersById = buyerIds.isEmpty()
        ? java.util.Map.of()
        : userRepository.findAllById(buyerIds).stream()
            .collect(java.util.stream.Collectors.toMap(UserEntity::getId, u -> u));

    List<UUID> taskIds = pageTasks.stream().map(TaskEntity::getId).toList();
    // Ordered newest-first, so the merge function keeps the first (latest) review
    // per task — the same row findTopByTaskIdOrderByCreatedAtDesc would return.
    java.util.Map<UUID, TaskAiReviewEntity> latestReviewByTaskId = taskIds.isEmpty()
        ? java.util.Map.of()
        : aiReviewRepository.findByTaskIdInOrderByCreatedAtDesc(taskIds).stream()
            .collect(java.util.stream.Collectors.toMap(
                TaskAiReviewEntity::getTaskId, r -> r, (first, later) -> first));

    List<AdminModerationTaskDto> dtos = pageTasks.stream().map(task -> {
      UserEntity buyer = buyersById.get(task.getBuyerId());
      TaskAiReviewEntity aiReview = latestReviewByTaskId.get(task.getId());

      List<String> flags = parseJsonList(aiReview != null ? aiReview.getFlags() : null);
      List<String> reasons = parseJsonList(aiReview != null ? aiReview.getReasons() : null);

      return new AdminModerationTaskDto(
          task.getId(),
          task.getBuyerId(),
          buyer != null ? buyer.getDisplayName() : "Customer",
          buyer != null ? buyer.getPhone() : "",
          task.getTitle(),
          task.getDescription(),
          "General",
          task.getBudgetPaise(),
          task.getAddressText(),
          task.getStatus().name(),
          aiReview != null ? aiReview.getStatus() : "NO_AI_REVIEW",
          aiReview != null ? aiReview.getRiskScore() : 0,
          aiReview != null ? aiReview.getConfidence() : 0,
          aiReview != null ? aiReview.getQualityScore() : 0,
          flags,
          reasons,
          aiReview != null ? aiReview.getModel() : "N/A",
          task.getCreatedAt()
      );
    }).toList();

    return new PageImpl<>(dtos, pageable, tasksPage.getTotalElements());
  }

  public AdminModerationDetailDto getTaskDetail(UUID taskId) {
    TaskEntity task = taskRepository.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    UserEntity buyer = userRepository.findById(task.getBuyerId()).orElse(null);
    TaskAiReviewEntity aiReview = aiReviewRepository.findTopByTaskIdOrderByCreatedAtDesc(taskId).orElse(null);
    List<TaskAuditLogEntity> auditLogs = auditLogRepository.findByTaskIdOrderByTimestampDesc(taskId);

    List<String> flags = parseJsonList(aiReview != null ? aiReview.getFlags() : null);
    List<String> reasons = parseJsonList(aiReview != null ? aiReview.getReasons() : null);

    List<AdminModerationDetailDto.AuditLogDto> auditDtos = auditLogs.stream().map(a ->
        new AdminModerationDetailDto.AuditLogDto(
            a.getId(),
            a.getAction(),
            a.getPerformedBy(),
            a.getTimestamp(),
            a.getRemarks()
        )
    ).toList();

    return new AdminModerationDetailDto(
        task.getId(),
        task.getBuyerId(),
        buyer != null ? buyer.getDisplayName() : "Customer",
        buyer != null ? buyer.getPhone() : "",
        buyer != null ? buyer.getEmail() : "",
        task.getTitle(),
        task.getDescription(),
        "General",
        task.getBudgetPaise(),
        task.getAddressText(),
        task.getLandmark(),
        task.getLat(),
        task.getLng(),
        task.getStatus().name(),
        task.getCreatedAt(),
        aiReview != null ? aiReview.getModel() : "N/A",
        aiReview != null ? aiReview.getStatus() : "N/A",
        aiReview != null ? aiReview.getConfidence() : 0,
        aiReview != null ? aiReview.getRiskScore() : 0,
        aiReview != null ? aiReview.getQualityScore() : 0,
        reasons,
        flags,
        aiReview != null ? aiReview.getRawResponse() : "{}",
        aiReview != null ? aiReview.getReviewDurationMs() : 0L,
        aiReview != null ? aiReview.getCreatedAt() : null,
        auditDtos
    );
  }

  @Transactional
  public AdminModerationTaskDto approveTask(UUID taskId, String adminUsername, String remarks) {
    TaskEntity task = taskRepository.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    boolean isFutureScheduled = task.getScheduledAt() != null && task.getScheduledAt().isAfter(java.time.Instant.now().plus(java.time.Duration.ofMinutes(1)));
    // beginSearching restarts the search clock. Setting SEARCHING directly left
    // searchingStartedAt at creation time, so a booking held in review for longer
    // than TASK_SEARCH_TIMEOUT_SECONDS was auto-cancelled on the very next
    // cleanup tick — seconds after the admin released it.
    if (isFutureScheduled) {
      task.setStatus(TaskStatus.SCHEDULED_PENDING);
    } else {
      task.beginSearching(java.time.Instant.now());
    }
    task.setPaymentCollectionMode(PaymentCollectionMode.PAY_AFTER_SERVICE); // Force pay after service for approved tasks
    taskRepository.save(task);

    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(taskId);
    auditLog.setAction("ADMIN_APPROVED");
    auditLog.setPerformedBy("ADMIN:" + (adminUsername != null ? adminUsername : "support"));
    auditLog.setRemarks(remarks != null ? remarks : "Approved by customer support admin");
    auditLogRepository.save(auditLog);

    // Dispatch offers to helpers (only if it's not a future scheduled task)
    if (!isFutureScheduled) {
      try {
        matchingService.dispatchOffers(task, true);
      } catch (Exception e) {
        log.error("Failed to dispatch offers for admin-approved task {}", taskId, e);
      }
    }

    return getModerationQueueItem(task);
  }

  @Transactional
  public AdminModerationTaskDto rejectTask(UUID taskId, String adminUsername, String remarks) {
    TaskEntity task = taskRepository.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    task.setStatus(TaskStatus.CANCELLED);
    task.setCancelReason(remarks != null ? remarks : "Rejected by Admin Moderation");
    task.setCancelledByRole("ADMIN");
    taskRepository.save(task);

    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(taskId);
    auditLog.setAction("ADMIN_REJECTED");
    auditLog.setPerformedBy("ADMIN:" + (adminUsername != null ? adminUsername : "support"));
    auditLog.setRemarks(remarks != null ? remarks : "Task rejected by support admin");
    auditLogRepository.save(auditLog);

    return getModerationQueueItem(task);
  }

  @Transactional
  public AdminModerationTaskDto editAndApproveTask(UUID taskId, String newTitle, String newDescription, String adminUsername, String remarks) {
    TaskEntity task = taskRepository.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    if (newTitle != null && !newTitle.isBlank()) {
      task.setTitle(newTitle.trim());
    }
    if (newDescription != null && !newDescription.isBlank()) {
      task.setDescription(newDescription.trim());
    }

    boolean isFutureScheduled = task.getScheduledAt() != null && task.getScheduledAt().isAfter(java.time.Instant.now().plus(java.time.Duration.ofMinutes(1)));
    if (isFutureScheduled) {
      task.setStatus(TaskStatus.SCHEDULED_PENDING);
    } else {
      task.beginSearching(java.time.Instant.now());
    }
    task.setPaymentCollectionMode(PaymentCollectionMode.PAY_AFTER_SERVICE); // Force pay after service for approved tasks
    taskRepository.save(task);

    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(taskId);
    auditLog.setAction("EDITED_AND_APPROVED");
    auditLog.setPerformedBy("ADMIN:" + (adminUsername != null ? adminUsername : "support"));
    auditLog.setRemarks("Edited title/description & approved. Remarks: " + (remarks != null ? remarks : "N/A"));
    auditLogRepository.save(auditLog);

    if (!isFutureScheduled) {
      try {
        matchingService.dispatchOffers(task, true);
      } catch (Exception e) {
        log.error("Failed to dispatch offers for edited/approved task {}", taskId, e);
      }
    }

    return getModerationQueueItem(task);
  }

  private AdminModerationTaskDto getModerationQueueItem(TaskEntity task) {
    UserEntity buyer = userRepository.findById(task.getBuyerId()).orElse(null);
    TaskAiReviewEntity aiReview = aiReviewRepository.findTopByTaskIdOrderByCreatedAtDesc(task.getId()).orElse(null);

    return new AdminModerationTaskDto(
        task.getId(),
        task.getBuyerId(),
        buyer != null ? buyer.getDisplayName() : "Customer",
        buyer != null ? buyer.getPhone() : "",
        task.getTitle(),
        task.getDescription(),
        "General",
        task.getBudgetPaise(),
        task.getAddressText(),
        task.getStatus().name(),
        aiReview != null ? aiReview.getStatus() : "N/A",
        aiReview != null ? aiReview.getRiskScore() : 0,
        aiReview != null ? aiReview.getConfidence() : 0,
        aiReview != null ? aiReview.getQualityScore() : 0,
        parseJsonList(aiReview != null ? aiReview.getFlags() : null),
        parseJsonList(aiReview != null ? aiReview.getReasons() : null),
        aiReview != null ? aiReview.getModel() : "N/A",
        task.getCreatedAt()
    );
  }

  private List<String> parseJsonList(String json) {
    if (json == null || json.isBlank()) return Collections.emptyList();
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }
}
