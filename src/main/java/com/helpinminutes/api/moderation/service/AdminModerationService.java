package com.helpinminutes.api.moderation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.moderation.dto.*;
import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import com.helpinminutes.api.tasks.model.TaskAuditLogEntity;
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

    List<AdminModerationTaskDto> dtos = tasksPage.getContent().stream().map(task -> {
      UserEntity buyer = userRepository.findById(task.getBuyerId()).orElse(null);
      TaskAiReviewEntity aiReview = aiReviewRepository.findTopByTaskIdOrderByCreatedAtDesc(task.getId()).orElse(null);

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

    task.setStatus(TaskStatus.SEARCHING); // Mark searching to dispatch to nearby helpers
    taskRepository.save(task);

    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(taskId);
    auditLog.setAction("ADMIN_APPROVED");
    auditLog.setPerformedBy("ADMIN:" + (adminUsername != null ? adminUsername : "support"));
    auditLog.setRemarks(remarks != null ? remarks : "Approved by customer support admin");
    auditLogRepository.save(auditLog);

    // Dispatch offers to helpers
    try {
      matchingService.dispatchOffers(task, true);
    } catch (Exception e) {
      log.error("Failed to dispatch offers for admin-approved task {}", taskId, e);
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

    task.setStatus(TaskStatus.SEARCHING);
    taskRepository.save(task);

    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(taskId);
    auditLog.setAction("EDITED_AND_APPROVED");
    auditLog.setPerformedBy("ADMIN:" + (adminUsername != null ? adminUsername : "support"));
    auditLog.setRemarks("Edited title/description & approved. Remarks: " + (remarks != null ? remarks : "N/A"));
    auditLogRepository.save(auditLog);

    try {
      matchingService.dispatchOffers(task, true);
    } catch (Exception e) {
      log.error("Failed to dispatch offers for edited/approved task {}", taskId, e);
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
