package com.helpinminutes.api.moderation.service;

import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.service.TaskModerationService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ModerationDecisionEngine {

  private static final Logger log = LoggerFactory.getLogger(ModerationDecisionEngine.class);
  private final TaskModerationService staticModerationService;

  public ModerationDecisionEngine(TaskModerationService staticModerationService) {
    this.staticModerationService = staticModerationService;
  }

  /**
   * Fast local pre-check using static blacklists and Naive Bayes rules.
   * Returns a list of static violation flags if any occur.
   */
  public List<String> runLocalPreCheck(String title, String description) {
    List<String> violations = new ArrayList<>();
    try {
      staticModerationService.validateTask(title, description);
    } catch (Exception e) {
      log.info("Local pre-check flagged task: {}", e.getMessage());
      violations.add("STATIC_CHECK_FLAG: " + e.getMessage());
    }
    return violations;
  }

  /**
   * Evaluates AI Review Result & local flags to determine final TaskStatus.
   * CRITICAL RULE: Outcome is strictly AI_APPROVED or ADMIN_REVIEW.
   */
  public TaskStatus determineStatus(AIReviewResult aiResult, List<String> localPreCheckFlags) {
    if (localPreCheckFlags != null && !localPreCheckFlags.isEmpty()) {
      log.info("Task flagged by local pre-check -> Routing to ADMIN_REVIEW");
      return TaskStatus.ADMIN_REVIEW;
    }

    if (aiResult == null) {
      return TaskStatus.ADMIN_REVIEW;
    }

    if (aiResult.requiresAdminReview() || "REVIEW".equalsIgnoreCase(aiResult.status())) {
      return TaskStatus.ADMIN_REVIEW;
    }

    if (aiResult.riskScore() >= 50) {
      log.info("High risk score ({}) -> Routing to ADMIN_REVIEW", aiResult.riskScore());
      return TaskStatus.ADMIN_REVIEW;
    }

    if (aiResult.flags() != null && !aiResult.flags().isEmpty()) {
      log.info("Task has flags {} -> Routing to ADMIN_REVIEW", aiResult.flags());
      return TaskStatus.ADMIN_REVIEW;
    }

    // Confidence Rules
    int confidence = aiResult.confidence();
    if (confidence > 95 && aiResult.riskScore() < 30) {
      return TaskStatus.AI_APPROVED;
    } else if (confidence >= 80 && (aiResult.flags() == null || aiResult.flags().isEmpty())) {
      return TaskStatus.AI_APPROVED;
    } else {
      log.info("Confidence {} < 80 or risk condition unmet -> Routing to ADMIN_REVIEW", confidence);
      return TaskStatus.ADMIN_REVIEW;
    }
  }
}
