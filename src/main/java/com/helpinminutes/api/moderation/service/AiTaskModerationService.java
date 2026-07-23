package com.helpinminutes.api.moderation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import com.helpinminutes.api.moderation.llm.LlmClient;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.event.TaskCreatedEvent;
import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import com.helpinminutes.api.tasks.model.TaskAuditLogEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskAiReviewRepository;
import com.helpinminutes.api.tasks.repo.TaskAuditLogRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskModerationService {

  private static final Logger log = LoggerFactory.getLogger(AiTaskModerationService.class);

  private final TaskRepository taskRepository;
  private final TaskAiReviewRepository aiReviewRepository;
  private final TaskAuditLogRepository auditLogRepository;
  private final LlmClient llmClient;
  private final ModerationDecisionEngine decisionEngine;
  private final MatchingService matchingService;
  private final RealtimePublisher realtime;
  private final PushNotificationService pushNotifications;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  public AiTaskModerationService(
      TaskRepository taskRepository,
      TaskAiReviewRepository aiReviewRepository,
      TaskAuditLogRepository auditLogRepository,
      LlmClient llmClient,
      ModerationDecisionEngine decisionEngine,
      MatchingService matchingService,
      RealtimePublisher realtime,
      PushNotificationService pushNotifications,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.taskRepository = taskRepository;
    this.aiReviewRepository = aiReviewRepository;
    this.auditLogRepository = auditLogRepository;
    this.llmClient = llmClient;
    this.decisionEngine = decisionEngine;
    this.matchingService = matchingService;
    this.realtime = realtime;
    this.pushNotifications = pushNotifications;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
  }

  @Async
  @EventListener
  @Transactional
  public void handleTaskCreatedEvent(TaskCreatedEvent event) {
    UUID taskId = event.taskId();
    log.info("Processing async AI moderation for task {}", taskId);
    Timer.Sample timerSample = Timer.start(meterRegistry);

    try {
      TaskEntity task = taskRepository.findById(taskId).orElse(null);
      if (task == null) {
        log.warn("Task {} not found for AI moderation", taskId);
        return;
      }

      // 1. Run local pre-check
      List<String> localFlags = decisionEngine.runLocalPreCheck(task.getTitle(), task.getDescription());

      // 2. Prepare payload and call LLM
      TaskModerationPayload payload = new TaskModerationPayload(
          task.getId(),
          task.getBuyerId(),
          task.getTitle(),
          task.getDescription(),
          null, // Category can be inferred or null
          task.getBudgetPaise(),
          task.getAddressText(),
          Collections.emptyList()
      );

      AIReviewResult aiResult = llmClient.evaluateTask(payload);

      // 3. Determine final status
      TaskStatus finalStatus = decisionEngine.determineStatus(aiResult, localFlags);

      // 4. Save AI Review Entity
      TaskAiReviewEntity reviewEntity = new TaskAiReviewEntity();
      reviewEntity.setTaskId(taskId);
      reviewEntity.setModel(aiResult.modelUsed());
      reviewEntity.setStatus(aiResult.status());
      reviewEntity.setConfidence(aiResult.confidence());
      reviewEntity.setRiskScore(aiResult.riskScore());
      reviewEntity.setQualityScore(aiResult.qualityScore());
      reviewEntity.setReviewDurationMs(aiResult.durationMs());

      List<String> combinedReasons = new ArrayList<>(aiResult.reasons());
      List<String> combinedFlags = new ArrayList<>(aiResult.flags());
      combinedFlags.addAll(localFlags);

      reviewEntity.setReasons(objectMapper.writeValueAsString(combinedReasons));
      reviewEntity.setFlags(objectMapper.writeValueAsString(combinedFlags));
      reviewEntity.setRawResponse(aiResult.rawResponse());

      aiReviewRepository.save(reviewEntity);

      // 5. Save Audit Log
      TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
      auditLog.setTaskId(taskId);

      if (finalStatus == TaskStatus.AI_APPROVED) {
        boolean scheduled = task.getScheduledAt() != null && task.getScheduledAt().isAfter(java.time.Instant.now().plus(java.time.Duration.ofMinutes(1)));
        task.setStatus(scheduled ? TaskStatus.SCHEDULED_PENDING : TaskStatus.SEARCHING);
        taskRepository.save(task);

        auditLog.setAction("AI_APPROVED");
        auditLog.setPerformedBy("AI_AGENT:" + aiResult.modelUsed());
        auditLog.setRemarks("Auto-approved with confidence " + aiResult.confidence() + "%. Status set to " + task.getStatus());
        auditLogRepository.save(auditLog);

        if (!scheduled) {
          // Dispatch offers to helpers
          try {
            matchingService.dispatchOffers(task, event.sendOfferNotifications());
          } catch (Exception e) {
            log.error("Failed to dispatch offers after AI approval for task {}", taskId, e);
          }
        }

        meterRegistry.counter("ai.review.approved").increment();

      } else {
        task.setStatus(TaskStatus.ADMIN_REVIEW);
        taskRepository.save(task);

        auditLog.setAction("SENT_TO_ADMIN_REVIEW");
        auditLog.setPerformedBy("AI_AGENT:" + aiResult.modelUsed());
        auditLog.setRemarks("Routed to Admin Review Queue. Risk score: " + aiResult.riskScore());
        auditLogRepository.save(auditLog);

        meterRegistry.counter("ai.review.reviewed").increment();

        // Notify Admin Dashboard via Realtime Websocket & Push
        try {
          realtime.publish(
              "admin_moderation_required",
              java.util.Map.of(
                  "taskId", taskId.toString(),
                  "riskScore", aiResult.riskScore(),
                  "flags", combinedFlags
              )
          );
        } catch (Exception ignored) {}
      }

      meterRegistry.counter("ai.review.count").increment();

    } catch (Exception e) {
      log.error("Error during AI moderation for task {}", taskId, e);
      meterRegistry.counter("ai.review.failed").increment();
    } finally {
      timerSample.stop(meterRegistry.timer("ai.review.duration"));
    }
  }
}
