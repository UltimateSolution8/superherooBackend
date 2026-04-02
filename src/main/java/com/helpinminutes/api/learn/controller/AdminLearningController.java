package com.helpinminutes.api.learn.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.learn.dto.AdminUpsertAssessmentRequest;
import com.helpinminutes.api.learn.dto.AdminUpsertTrainingMaterialRequest;
import com.helpinminutes.api.learn.dto.HelperAssessmentAttemptResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/learn")
public class AdminLearningController {
  private final LearningService learning;

  public AdminLearningController(LearningService learning) {
    this.learning = learning;
  }

  @GetMapping("/materials")
  public List<TrainingMaterialResponse> listMaterials(@AuthenticationPrincipal UserPrincipal principal) {
    onlyAdmin(principal);
    return learning.listAdminMaterials();
  }

  @PostMapping("/materials")
  public TrainingMaterialResponse createMaterial(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminUpsertTrainingMaterialRequest req) {
    onlyAdmin(principal);
    return learning.createMaterial(principal.userId(), req);
  }

  @PutMapping("/materials/{materialId}")
  public TrainingMaterialResponse updateMaterial(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID materialId,
      @Valid @RequestBody AdminUpsertTrainingMaterialRequest req) {
    onlyAdmin(principal);
    return learning.updateMaterial(materialId, req);
  }

  @GetMapping("/progress")
  public List<HelperTrainingProgressResponse> listProgress(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false) UUID materialId,
      @RequestParam(required = false) UUID helperId) {
    onlyAdmin(principal);
    return learning.listAdminProgress(materialId, helperId);
  }

  @GetMapping("/assessments")
  public List<LearningAssessmentResponse> listAssessments(@AuthenticationPrincipal UserPrincipal principal) {
    onlyAdmin(principal);
    return learning.listAdminAssessments();
  }

  @PostMapping("/assessments")
  public LearningAssessmentResponse createAssessment(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminUpsertAssessmentRequest req) {
    onlyAdmin(principal);
    return learning.createAssessment(principal.userId(), req);
  }

  @PutMapping("/assessments/{assessmentId}")
  public LearningAssessmentResponse updateAssessment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID assessmentId,
      @Valid @RequestBody AdminUpsertAssessmentRequest req) {
    onlyAdmin(principal);
    return learning.updateAssessment(assessmentId, req);
  }

  @GetMapping("/assessments/{assessmentId}/attempts")
  public List<HelperAssessmentAttemptResponse> listAttempts(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID assessmentId) {
    onlyAdmin(principal);
    return learning.listAdminAssessmentAttempts(assessmentId);
  }

  private void onlyAdmin(UserPrincipal principal) {
    if (principal.role() != UserRole.ADMIN) {
      throw new ForbiddenException("Admin only");
    }
  }
}
