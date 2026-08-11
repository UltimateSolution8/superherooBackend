package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.helpinminutes.api.moderation.dto.AIReviewResult;
import com.helpinminutes.api.moderation.service.ModerationDecisionEngine;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.service.TaskModerationService;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModerationDecisionEngineTest {

  private TaskModerationService staticModerationService;
  private ModerationDecisionEngine decisionEngine;

  @BeforeEach
  void setUp() {
    staticModerationService = new TaskModerationService();
    decisionEngine = new ModerationDecisionEngine(staticModerationService);
  }

  @Test
  void autoApprovesHighConfidenceLowRiskTask() {
    AIReviewResult result = approvedResult(98, 10);

    assertEquals(TaskStatus.AI_APPROVED, decisionEngine.determineStatus(result, clean()));
  }

  @Test
  void routesHighRiskTaskToAdminReview() {
    AIReviewResult result = new AIReviewResult(
        "REVIEW", 90, 75, 50,
        List.of("Suspicious activity"), List.of("HIGH_RISK"), true,
        "{}", "gemini-2.5-flash-lite", 200L);

    assertEquals(TaskStatus.ADMIN_REVIEW, decisionEngine.determineStatus(result, clean()));
  }

  @Test
  void routesLowConfidenceTaskToAdminReview() {
    AIReviewResult result = approvedResult(70, 20);

    assertEquals(TaskStatus.ADMIN_REVIEW, decisionEngine.determineStatus(result, clean()));
  }

  /** A hard policy match is decisive; the model's opinion cannot overturn it. */
  @Test
  void rejectsWhenLocalScreeningFoundAHardPolicyMatch() {
    AIReviewResult confidentlyApproved = approvedResult(99, 5);
    var blocked = staticModerationService.screen("Errand", "Get me some ganja tonight");

    assertEquals(TaskStatus.ADMIN_REJECTED,
        decisionEngine.determineStatus(confidentlyApproved, blocked));
  }

  /** The model may also reject outright, which the old prompt forbade it from doing. */
  @Test
  void honoursAnOutrightModelBlock() {
    AIReviewResult blockedByModel = new AIReviewResult(
        "BLOCK", 96, 95, 20,
        List.of("Requests an illegal service"), Collections.emptyList(), false,
        "{}", "gemini-2.5-flash-lite", 210L);

    assertEquals(TaskStatus.ADMIN_REJECTED, decisionEngine.determineStatus(blockedByModel, clean()));
  }

  /**
   * The fast path. A locally clean task is approved without a model verdict at all,
   * which is what keeps the pipeline nearly free — and it must not depend on a
   * provider being reachable.
   */
  @Test
  void approvesALocallyCleanTaskWithNoModelVerdict() {
    var clean = staticModerationService.screen("Cleaning", "Clean my kitchen and wash the dishes");

    assertEquals(TaskStatus.AI_APPROVED, decisionEngine.determineStatus(null, clean));
  }

  /**
   * The fail-open regression. When the model could not be reached for an ambiguous
   * task, the client used to fabricate {@code APPROVED, confidence 85} and that
   * sailed through. An escalated task with no verdict must reach a human.
   */
  @Test
  void neverApprovesAnEscalatedTaskWhenTheModelIsUnreachable() {
    var escalated = staticModerationService.screen(
        "Pharmacy", "Buy cough syrup with low alcohol content");
    assertEquals(TaskModerationService.Verdict.ESCALATE, escalated.verdict());

    assertEquals(TaskStatus.ADMIN_REVIEW, decisionEngine.determineStatus(null, escalated));
  }

  private static AIReviewResult approvedResult(int confidence, int riskScore) {
    return new AIReviewResult(
        "APPROVED", confidence, riskScore, 90,
        List.of("Clear task"), Collections.emptyList(), false,
        "{}", "gemini-2.5-flash-lite", 150L);
  }

  private TaskModerationService.ScreeningResult clean() {
    return staticModerationService.screen("Errand", "Deliver groceries to my home");
  }
}
