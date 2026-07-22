package com.helpinminutes.api.moderation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    staticModerationService = mock(TaskModerationService.class);
    decisionEngine = new ModerationDecisionEngine(staticModerationService);
  }

  @Test
  void autoApprovesHighConfidenceLowRiskTask() {
    AIReviewResult result = new AIReviewResult(
        "APPROVED", 98, 10, 90,
        List.of("Clear task"), Collections.emptyList(), false,
        "{}", "z-ai/glm-4.7-flash-free", 150L
    );

    TaskStatus status = decisionEngine.determineStatus(result, Collections.emptyList());
    assertEquals(TaskStatus.AI_APPROVED, status);
  }

  @Test
  void routesHighRiskTaskToAdminReview() {
    AIReviewResult result = new AIReviewResult(
        "REVIEW", 90, 75, 50,
        List.of("Suspicious activity"), List.of("HIGH_RISK"), true,
        "{}", "z-ai/glm-4.7-flash-free", 200L
    );

    TaskStatus status = decisionEngine.determineStatus(result, Collections.emptyList());
    assertEquals(TaskStatus.ADMIN_REVIEW, status);
  }

  @Test
  void routesLowConfidenceTaskToAdminReview() {
    AIReviewResult result = new AIReviewResult(
        "APPROVED", 70, 20, 70,
        List.of("Uncertain evaluation"), Collections.emptyList(), false,
        "{}", "moonshotai/kimi-k3-free", 180L
    );

    TaskStatus status = decisionEngine.determineStatus(result, Collections.emptyList());
    assertEquals(TaskStatus.ADMIN_REVIEW, status);
  }

  @Test
  void routesLocalPreCheckFlagsToAdminReview() {
    AIReviewResult result = new AIReviewResult(
        "APPROVED", 99, 5, 95,
        List.of("Clear task"), Collections.emptyList(), false,
        "{}", "z-ai/glm-4.7-flash-free", 100L
    );

    List<String> localFlags = List.of("STATIC_CHECK_FLAG: Prohibited word detected");
    TaskStatus status = decisionEngine.determineStatus(result, localFlags);
    assertEquals(TaskStatus.ADMIN_REVIEW, status);
  }
}
