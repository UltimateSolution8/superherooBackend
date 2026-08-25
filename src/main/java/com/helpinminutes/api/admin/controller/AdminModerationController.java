package com.helpinminutes.api.admin.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.moderation.dto.*;
import com.helpinminutes.api.moderation.service.AdminModerationService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/admin/moderation", "/api/admin/moderation"})
public class AdminModerationController {

  private final AdminModerationService moderationService;

  public AdminModerationController(AdminModerationService moderationService) {
    this.moderationService = moderationService;
  }

  private void checkAdmin(UserPrincipal principal) {
    if (principal == null || (principal.role() != UserRole.ADMIN && principal.role() != UserRole.ADMIN_READONLY)) {
      throw new ForbiddenException("Admin access required");
    }
  }

  private void checkFullAdmin(UserPrincipal principal) {
    if (principal == null || principal.role() != UserRole.ADMIN) {
      throw new ForbiddenException("Super admin access required");
    }
  }

  @GetMapping("/queue")
  public Page<AdminModerationTaskDto> getModerationQueue(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false, defaultValue = "ADMIN_REVIEW") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    checkAdmin(principal);
    return moderationService.getModerationQueue(status, PageRequest.of(page, size));
  }

  @GetMapping("/tasks/{id}")
  public AdminModerationDetailDto getTaskDetail(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id) {
    checkAdmin(principal);
    return moderationService.getTaskDetail(id);
  }

  @PostMapping("/tasks/{id}/approve")
  public AdminModerationTaskDto approveTask(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) AdminReviewDecisionRequest request) {
    checkFullAdmin(principal);
    String remarks = request != null ? request.remarks() : "Approved by Admin";
    return moderationService.approveTask(id, principal.userId().toString(), remarks);
  }

  @PostMapping("/tasks/{id}/reject")
  public AdminModerationTaskDto rejectTask(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) AdminReviewDecisionRequest request) {
    checkFullAdmin(principal);
    String remarks = request != null ? request.remarks() : "Rejected by Admin";
    return moderationService.rejectTask(id, principal.userId().toString(), remarks);
  }

  @PutMapping("/tasks/{id}/edit-approve")
  public AdminModerationTaskDto editAndApproveTask(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @RequestBody AdminEditApproveRequest request) {
    checkFullAdmin(principal);
    return moderationService.editAndApproveTask(
        id,
        request.title(),
        request.description(),
        principal.userId().toString(),
        request.remarks()
    );
  }

  // TEMP: MANUAL_MODERATION_MODE — approve a task and directly assign it to a
  // specific helper, bypassing the automatic matching/dispatch engine.
  @PostMapping("/tasks/{id}/approve-and-assign")
  public AdminModerationTaskDto approveAndAssignTask(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @RequestBody AdminApproveAndAssignRequest request) {
    checkFullAdmin(principal);
    if (request.helperId() == null) {
      throw new com.helpinminutes.api.errors.BadRequestException("helperId is required");
    }
    return moderationService.approveAndAssignTask(
        id,
        request.helperId(),
        principal.userId().toString(),
        request.remarks()
    );
  }

  // TEMP: MANUAL_MODERATION_MODE — returns a lightweight list of active,
  // KYC-approved helpers for the admin's helper picker dropdown in the
  // moderation queue. Only name, phone, and id are returned.
  @GetMapping("/helpers")
  public java.util.List<java.util.Map<String, Object>> getApprovedHelpers(
      @AuthenticationPrincipal UserPrincipal principal) {
    checkAdmin(principal);
    return moderationService.listApprovedHelpers();
  }
  // END TEMP: MANUAL_MODERATION_MODE endpoints
}
