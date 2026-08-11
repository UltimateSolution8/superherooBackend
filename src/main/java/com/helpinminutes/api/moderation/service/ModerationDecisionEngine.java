package com.helpinminutes.api.moderation.service;

import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.service.TaskModerationService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns local screening plus a model verdict into a task status.
 *
 * <p>Local screening now returns a typed verdict. It used to be invoked by calling
 * {@code validateTask} inside a try/catch and turning the exception into a flag,
 * which collapsed "this is illegal" and "this needs a closer look" into the same
 * outcome — a human review queue entry — so the queue filled with plumbing jobs.
 */
@Component
public class ModerationDecisionEngine {

  private static final Logger log = LoggerFactory.getLogger(ModerationDecisionEngine.class);

  /**
   * Risk score at or above which a task goes to a human regardless of the model's
   * own recommendation.
   */
  private static final int RISK_SCORE_REVIEW_THRESHOLD = 50;

  /** Minimum model confidence for an automatic approval. */
  private static final int MIN_CONFIDENCE_FOR_AUTO_APPROVE = 80;

  private final TaskModerationService staticModerationService;

  public ModerationDecisionEngine(TaskModerationService staticModerationService) {
    this.staticModerationService = staticModerationService;
  }

  /** Local screening result: BLOCK, ESCALATE or CLEAN. */
  public TaskModerationService.ScreeningResult runLocalPreCheck(String title, String description) {
    return staticModerationService.screen(title, description);
  }

  /**
   * Resolves the final status.
   *
   * @param aiResult the model's verdict, or {@code null} when it was not consulted
   *     (a locally-clean task) or could not be reached
   * @param localResult what local screening concluded
   */
  public TaskStatus determineStatus(
      AIReviewResult aiResult, TaskModerationService.ScreeningResult localResult) {

    // A hard policy match is decisive on its own. There is nothing for a human to
    // weigh up about a request for a firearm.
    if (localResult != null && localResult.isBlocked()) {
      return TaskStatus.ADMIN_REJECTED;
    }

    if (aiResult == null) {
      // Locally clean and never escalated: approve. This is the fast path the whole
      // cascade exists to serve, and it must not depend on a model being reachable.
      if (localResult != null && localResult.verdict() == TaskModerationService.Verdict.CLEAN) {
        return TaskStatus.AI_APPROVED;
      }
      // Escalated but the model could not be reached — a human decides. Never
      // approve an ambiguous task just because the provider was down.
      log.info("No model verdict for an escalated task -> ADMIN_REVIEW");
      return TaskStatus.ADMIN_REVIEW;
    }

    if ("BLOCK".equalsIgnoreCase(aiResult.status())) {
      return TaskStatus.ADMIN_REJECTED;
    }

    if (aiResult.requiresAdminReview() || "REVIEW".equalsIgnoreCase(aiResult.status())) {
      return TaskStatus.ADMIN_REVIEW;
    }

    if (aiResult.riskScore() >= RISK_SCORE_REVIEW_THRESHOLD) {
      log.info("High risk score ({}) -> ADMIN_REVIEW", aiResult.riskScore());
      return TaskStatus.ADMIN_REVIEW;
    }

    if (aiResult.flags() != null && !aiResult.flags().isEmpty()) {
      log.info("Model raised flags {} -> ADMIN_REVIEW", aiResult.flags());
      return TaskStatus.ADMIN_REVIEW;
    }

    if (aiResult.confidence() >= MIN_CONFIDENCE_FOR_AUTO_APPROVE) {
      return TaskStatus.AI_APPROVED;
    }

    log.info("Model confidence {} below {} -> ADMIN_REVIEW",
        aiResult.confidence(), MIN_CONFIDENCE_FOR_AUTO_APPROVE);
    return TaskStatus.ADMIN_REVIEW;
  }

  /** Reason codes for the audit trail, combining local and model findings. */
  public List<String> combinedReasons(
      AIReviewResult aiResult, TaskModerationService.ScreeningResult localResult) {
    List<String> reasons = new ArrayList<>();
    if (localResult != null) {
      reasons.addAll(localResult.reasons());
    }
    if (aiResult != null && aiResult.reasons() != null) {
      reasons.addAll(aiResult.reasons());
    }
    return reasons;
  }
}
