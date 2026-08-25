package com.helpinminutes.api.moderation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.dto.TaskModerationPayload;
import com.helpinminutes.api.moderation.llm.LlmClient;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class AiTaskModerationService {

  private static final Logger log = LoggerFactory.getLogger(AiTaskModerationService.class);

  private final TaskRepository taskRepository;
  private final TaskAiReviewRepository aiReviewRepository;
  private final TaskAuditLogRepository auditLogRepository;
  private final LlmClient llmClient;
  private final ModerationDecisionEngine decisionEngine;
  private final NotificationQueueService notificationQueue;
  private final RealtimePublisher realtime;
  private final PushNotificationService pushNotifications;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final ModerationResultCache resultCache;

  public AiTaskModerationService(
      TaskRepository taskRepository,
      TaskAiReviewRepository aiReviewRepository,
      TaskAuditLogRepository auditLogRepository,
      LlmClient llmClient,
      ModerationDecisionEngine decisionEngine,
      NotificationQueueService notificationQueue,
      RealtimePublisher realtime,
      PushNotificationService pushNotifications,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      ModerationResultCache resultCache) {
    this.taskRepository = taskRepository;
    this.aiReviewRepository = aiReviewRepository;
    this.auditLogRepository = auditLogRepository;
    this.llmClient = llmClient;
    this.decisionEngine = decisionEngine;
    this.notificationQueue = notificationQueue;
    this.realtime = realtime;
    this.pushNotifications = pushNotifications;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.resultCache = resultCache;
  }

  /**
   * Moderates a task off the request thread, then dispatches if it was approved.
   *
   * <p>Delegates to {@link #moderateTaskSynchronously} rather than repeating it: the
   * two used to be near-identical hundred-line copies, which is how the local
   * pre-check ended up handled differently in each.
   *
   * <p>This path exists for callers that do not want to wait on a model call — a
   * bulk CSV import, above all, where a 250-row request would otherwise make 250
   * sequential model calls on one thread.
   */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void handleTaskCreatedEvent(TaskCreatedEvent event) {
    UUID taskId = event.taskId();
    TaskEntity task = taskRepository.findById(taskId).orElse(null);
    if (task == null) {
      log.warn("Task {} not found for AI moderation", taskId);
      return;
    }
    if (task.getStatus() != TaskStatus.AI_PENDING) {
      log.info("Task {} is already {}, skipping async moderation", taskId, task.getStatus());
      return;
    }

    TaskStatus finalStatus = moderateTaskSynchronously(task);
    meterRegistry.counter("ai.review.count").increment();

    if (finalStatus == TaskStatus.SEARCHING) {
      // Commit the moderation decision and expected matching wave together. The
      // dedicated worker performs Redis/routing work only after this transaction.
      notificationQueue.enqueueMatchingDispatch(task, event.sendOfferNotifications());
    }
  }

  /**
   * Screens a task and resolves its status.
   *
   * <h2>The cascade</h2>
   *
   * <pre>
   *   local BLOCK    → ADMIN_REJECTED         no model call
   *   local CLEAN    → SEARCHING / SCHEDULED  no model call  ← the common case
   *   local ESCALATE → cache, else the model
   * </pre>
   *
   * <p>Only the escalated minority costs anything. Every task used to go to the
   * model, on the request thread, with a worst case of ~16s (4s connect + 8s read,
   * twice) against a documented p95 of 450ms.
   */
  // ─── TEMP: MANUAL_MODERATION_MODE ────────────────────────────────────────
  // AI moderation is temporarily disabled. Every task goes directly to
  // ADMIN_REVIEW for manual customer care review regardless of content.
  // To revert to the AI flow, restore the original moderateTaskSynchronously
  // body (search: ORIGINAL_AI_FLOW_PRESERVED at the bottom of this file).
  // ─────────────────────────────────────────────────────────────────────────
  @Transactional
  public TaskStatus moderateTaskSynchronously(TaskEntity task) {
    UUID taskId = task.getId();
    Timer.Sample timerSample = Timer.start(meterRegistry);

    try {
      // TEMP: MANUAL_MODERATION_MODE — Skip all AI/LLM processing.
      // Even hard-policy-blocked content is sent to ADMIN_REVIEW instead of
      // auto-cancelling, so customer care can make the final call.
      // Run the local pre-check only for audit/logging — verdict is not acted on.
      var local = decisionEngine.runLocalPreCheck(task.getTitle(), task.getDescription());
      if (!local.reasons().isEmpty()) {
        log.info("Task {} local pre-check flags (manual mode, not auto-acting): {}",
            taskId, local.reasons());
      }

      meterRegistry.counter("ai.review.reviewed").increment();
      return sendToManualReview(task, local);

    } catch (Exception e) {
      log.error("Error during manual moderation routing for task {}", taskId, e);
      meterRegistry.counter("ai.review.failed").increment();

      task.setStatus(TaskStatus.ADMIN_REVIEW);
      taskRepository.save(task);

      TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
      auditLog.setTaskId(taskId);
      auditLog.setAction("MANUAL_REVIEW_ROUTING_FAILED");
      auditLog.setPerformedBy("SYSTEM");
      auditLog.setRemarks("Error routing to manual review, task held in ADMIN_REVIEW: " + e.getMessage());
      auditLogRepository.save(auditLog);

      return TaskStatus.ADMIN_REVIEW;
    } finally {
      timerSample.stop(meterRegistry.timer("ai.review.duration"));
    }
  }
  // ─── END TEMP: MANUAL_MODERATION_MODE ────────────────────────────────────

  // ─── outcomes ─────────────────────────────────────────────────────────────

  /** Moves the task into its searching state and records who approved it. */
  private TaskStatus approve(TaskEntity task, String approvedBy, String remarks) {
    boolean scheduled = task.getScheduledAt() != null
        && task.getScheduledAt().isAfter(java.time.Instant.now().plus(java.time.Duration.ofMinutes(1)));
    TaskStatus targetStatus = scheduled ? TaskStatus.SCHEDULED_PENDING : TaskStatus.SEARCHING;
    if (scheduled) {
      task.setStatus(TaskStatus.SCHEDULED_PENDING);
    } else {
      task.beginSearching(java.time.Instant.now());
    }
    taskRepository.save(task);

    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(task.getId());
    auditLog.setAction("AI_APPROVED");
    auditLog.setPerformedBy(approvedBy);
    auditLog.setRemarks(remarks + ". Status set to " + targetStatus);
    auditLogRepository.save(auditLog);
    return targetStatus;
  }

  /**
   * Rejects a task that matched hard policy.
   *
   * <p>Cancelled rather than parked in review: there is nothing for a moderator to
   * weigh up about a request for a firearm, and leaving it pending would just make
   * the citizen wait to be told no. The reason is the policy category's own wording,
   * never the matched term — echoing that back told anyone probing the filter
   * exactly which word tripped it.
   */
  private TaskStatus rejectByPolicy(
      TaskEntity task, com.helpinminutes.api.tasks.service.TaskModerationService.ScreeningResult local) {
    task.setStatus(TaskStatus.CANCELLED);
    task.setCancelReason(local.citizenMessage() != null
        ? local.citizenMessage()
        : "This request can't be posted on Superherooo.");
    task.setCancelledByRole("SYSTEM");
    task.setCancelledAt(java.time.Instant.now());
    taskRepository.save(task);

    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(task.getId());
    auditLog.setAction("ADMIN_REJECTED");
    auditLog.setPerformedBy("SYSTEM:POLICY");
    auditLog.setRemarks("Blocked by content policy: " + String.join(", ", local.reasons()));
    auditLogRepository.save(auditLog);
    return TaskStatus.ADMIN_REJECTED;
  }

  private TaskStatus sendToAdminReview(
      TaskEntity task,
      AIReviewResult aiResult,
      com.helpinminutes.api.tasks.service.TaskModerationService.ScreeningResult local) {
    task.setStatus(TaskStatus.ADMIN_REVIEW);
    taskRepository.save(task);

    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(task.getId());
    auditLog.setAction("SENT_TO_ADMIN_REVIEW");
    auditLog.setPerformedBy(aiResult == null ? "SYSTEM" : "AI_AGENT:" + aiResult.modelUsed());
    auditLog.setRemarks("Routed to review. Context terms: " + String.join(", ", local.contextTerms()));
    auditLogRepository.save(auditLog);

    realtime.publish(
        "admin_moderation_required",
        java.util.Map.of(
            "taskId", task.getId().toString(),
            "riskScore", aiResult == null ? 50 : aiResult.riskScore(),
            "flags", local.reasons()));
    // The citizen was never told their booking was held — PushNotificationService was
    // injected here and never called, so a flagged task just sat silent.
    try {
      pushNotifications.notifyBuyerTaskUnderReview(task.getBuyerId(), task);
    } catch (Exception ignored) {
      // Never let a push failure change the moderation outcome.
    }
    return TaskStatus.ADMIN_REVIEW;
  }

  // TEMP: MANUAL_MODERATION_MODE — routes every incoming task to ADMIN_REVIEW
  // for manual customer care review. Fires the same realtime and push signals
  // as the AI-escalation path so the admin panel real-time feed still works.
  private TaskStatus sendToManualReview(
      TaskEntity task,
      com.helpinminutes.api.tasks.service.TaskModerationService.ScreeningResult local) {
    task.setStatus(TaskStatus.ADMIN_REVIEW);
    taskRepository.save(task);

    String flagsSummary = local.reasons().isEmpty() ? "none" : String.join(", ", local.reasons());
    TaskAuditLogEntity auditLog = new TaskAuditLogEntity();
    auditLog.setTaskId(task.getId());
    auditLog.setAction("SENT_TO_MANUAL_REVIEW");
    auditLog.setPerformedBy("SYSTEM:MANUAL_MODE");
    auditLog.setRemarks("Manual moderation mode active. Local pre-check flags: " + flagsSummary);
    auditLogRepository.save(auditLog);

    realtime.publish(
        "admin_moderation_required",
        java.util.Map.of(
            "taskId", task.getId().toString(),
            "riskScore", 0,
            "flags", local.reasons()));
    try {
      pushNotifications.notifyBuyerTaskUnderReview(task.getBuyerId(), task);
    } catch (Exception ignored) {
      // Never let a push failure change the moderation outcome.
    }
    return TaskStatus.ADMIN_REVIEW;
  }
  // END TEMP: MANUAL_MODERATION_MODE

  // ─── model call ───────────────────────────────────────────────────────────

  /**
   * Asks the model, reusing a previous verdict for identical text.
   *
   * <p>Identical text was re-billed on every booking, every bulk-row retry and again
   * on prepaid activation, which moderates the same task twice.
   */
  private AIReviewResult evaluateWithCache(TaskEntity task, List<String> contextTerms) {
    var cached = resultCache.get(task.getTitle(), task.getDescription());
    if (cached.isPresent()) {
      meterRegistry.counter("ai.review.cache.hit").increment();
      return cached.get();
    }
    meterRegistry.counter("ai.review.cache.miss").increment();

    TaskModerationPayload payload = new TaskModerationPayload(
        task.getId(),
        task.getBuyerId(),
        task.getTitle(),
        task.getDescription(),
        // The terms local screening flagged. Passing them lets the prompt stay short
        // and still point the model at what actually needs adjudicating.
        String.join(", ", contextTerms),
        task.getBudgetPaise(),
        task.getAddressText(),
        Collections.emptyList());

    AIReviewResult result = llmClient.evaluateTask(payload);
    resultCache.put(task.getTitle(), task.getDescription(), result);
    return result;
  }

  /** Persists the model's verdict for the admin detail view and for auditing. */
  private void persistReview(
      UUID taskId,
      AIReviewResult aiResult,
      com.helpinminutes.api.tasks.service.TaskModerationService.ScreeningResult local) {
    if (aiResult == null) return;
    try {
      TaskAiReviewEntity reviewEntity = new TaskAiReviewEntity();
      reviewEntity.setTaskId(taskId);
      reviewEntity.setModel(aiResult.modelUsed());
      reviewEntity.setStatus(aiResult.status());
      reviewEntity.setConfidence(aiResult.confidence());
      reviewEntity.setRiskScore(aiResult.riskScore());
      reviewEntity.setQualityScore(aiResult.qualityScore());
      reviewEntity.setReviewDurationMs(aiResult.durationMs());
      reviewEntity.setReasons(objectMapper.writeValueAsString(
          decisionEngine.combinedReasons(aiResult, local)));
      List<String> flags = new ArrayList<>(aiResult.flags());
      flags.addAll(local.contextTerms());
      reviewEntity.setFlags(objectMapper.writeValueAsString(flags));
      reviewEntity.setRawResponse(aiResult.rawResponse());
      aiReviewRepository.save(reviewEntity);
    } catch (Exception e) {
      // An unwritable audit row must not change the moderation outcome.
      log.warn("Could not persist AI review for task {}: {}", taskId, e.getMessage());
    }
  }
}
