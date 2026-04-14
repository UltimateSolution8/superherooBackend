package com.helpinminutes.api.learn.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.learn.dto.HelperAssessmentAttemptResponse;
import com.helpinminutes.api.learn.dto.HelperAssessmentStartResponse;
import com.helpinminutes.api.learn.dto.HelperAssessmentSubmitRequest;
import com.helpinminutes.api.learn.dto.HelperTrainingProgressRequest;
import com.helpinminutes.api.learn.dto.HelperTrainingProgressResponse;
import com.helpinminutes.api.learn.dto.LearningAssessmentResponse;
import com.helpinminutes.api.learn.dto.TrainingMaterialResponse;
import com.helpinminutes.api.learn.service.LearningService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/helper/learn")
public class HelperLearningController {
  private final LearningService learning;

  public HelperLearningController(LearningService learning) {
    this.learning = learning;
  }

  @GetMapping("/materials")
  public List<TrainingMaterialResponse> listMaterials(@AuthenticationPrincipal UserPrincipal principal) {
    onlyHelper(principal);
    return learning.listHelperMaterials(principal.userId());
  }

  @GetMapping("/progress")
  public List<HelperTrainingProgressResponse> listProgress(@AuthenticationPrincipal UserPrincipal principal) {
    onlyHelper(principal);
    return learning.listHelperProgress(principal.userId());
  }

  @PostMapping("/materials/{materialId}/progress")
  public HelperTrainingProgressResponse upsertProgress(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID materialId,
      @Valid @RequestBody HelperTrainingProgressRequest req) {
    onlyHelper(principal);
    return learning.upsertHelperProgress(principal.userId(), materialId, req);
  }

  @GetMapping("/assessments")
  public List<LearningAssessmentResponse> listAssessments(@AuthenticationPrincipal UserPrincipal principal) {
    onlyHelper(principal);
    return learning.listHelperAssessments(principal.userId());
  }

  @PostMapping("/assessments/{assessmentId}/start")
  public HelperAssessmentStartResponse startAssessment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID assessmentId) {
    onlyHelper(principal);
    return learning.startAssessment(principal.userId(), assessmentId);
  }

  @PostMapping("/assessments/{assessmentId}/submit")
  public HelperAssessmentAttemptResponse submitAssessment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID assessmentId,
      @Valid @RequestBody HelperAssessmentSubmitRequest req) {
    onlyHelper(principal);
    return learning.submitAssessment(principal.userId(), assessmentId, req);
  }

  @GetMapping("/assessments/{assessmentId}/attempts")
  public List<HelperAssessmentAttemptResponse> listAttempts(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID assessmentId) {
    onlyHelper(principal);
    return learning.listHelperAssessmentAttempts(principal.userId(), assessmentId);
  }

  private void onlyHelper(UserPrincipal principal) {
    if (principal.role() != UserRole.HELPER) {
      throw new ForbiddenException("Only helpers can access learning APIs");
    }
  }
}
