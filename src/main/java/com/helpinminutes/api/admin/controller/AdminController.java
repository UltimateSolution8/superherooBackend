package com.helpinminutes.api.admin.controller;

import com.helpinminutes.api.admin.dto.AdminCreateUserRequest;
import com.helpinminutes.api.admin.dto.AdminBulkHelperKycActionRequest;
import com.helpinminutes.api.admin.dto.AdminBulkOperationFailure;
import com.helpinminutes.api.admin.dto.AdminBulkOperationResponse;
import com.helpinminutes.api.admin.dto.AdminBulkTaskStatusRequest;
import com.helpinminutes.api.admin.dto.AdminBulkUserUpdateRequest;
import com.helpinminutes.api.admin.dto.AdminManagedUserResponse;
import com.helpinminutes.api.admin.dto.AdminSummaryResponse;
import com.helpinminutes.api.admin.dto.AdminUpdateUserRequest;
import com.helpinminutes.api.admin.dto.PendingHelperResponse;
import com.helpinminutes.api.admin.dto.RejectHelperRequest;
import com.helpinminutes.api.admin.service.AdminService;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.tasks.controller.TaskController;
import com.helpinminutes.api.tasks.dto.TaskResponse;
import com.helpinminutes.api.tasks.dto.UpdateTaskStatusRequest;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.service.TaskService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private final AdminService admin;
  private final TaskService tasks;
  private final UserRepository users;

  public AdminController(AdminService admin, TaskService tasks, UserRepository users) {
    this.admin = admin;
    this.tasks = tasks;
    this.users = users;
  }

  private static void requireAdmin(UserPrincipal principal) {
    if (principal.role() != UserRole.ADMIN) {
      throw new ForbiddenException("Admin only");
    }
  }

  @GetMapping("/helpers/pending")
  public List<PendingHelperResponse> pendingHelpers(@AuthenticationPrincipal UserPrincipal principal) {
    requireAdmin(principal);
    return admin.listPendingHelpers();
  }

  @GetMapping("/summary")
  public AdminSummaryResponse summary(@AuthenticationPrincipal UserPrincipal principal) {
    requireAdmin(principal);
    return admin.summary();
  }

  @PostMapping("/helpers/{helperId}/approve")
  public void approve(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID helperId) {
    requireAdmin(principal);
    admin.approveHelper(helperId);
  }

  @PostMapping("/helpers/{helperId}/verify-kyc")
  public void verifyKyc(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID helperId) {
    requireAdmin(principal);
    admin.verifyHelperKyc(helperId);
  }

  @PostMapping("/helpers/{helperId}/reject")
  public void reject(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID helperId,
      @Valid @RequestBody RejectHelperRequest req) {
    requireAdmin(principal);
    admin.rejectHelper(helperId, req.reason());
  }

  @PostMapping("/helpers/{helperId}/reopen-kyc")
  public void reopenKyc(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID helperId) {
    requireAdmin(principal);
    admin.reopenHelperKyc(helperId);
  }

  @PostMapping("/helpers/pending/bulk-action")
  public AdminBulkOperationResponse bulkPendingKycAction(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminBulkHelperKycActionRequest req) {
    requireAdmin(principal);
    return admin.bulkHelperKycAction(req.helperIds(), req.action(), req.reason());
  }

  @GetMapping("/helpers")
  public List<AdminManagedUserResponse> listHelpers(@AuthenticationPrincipal UserPrincipal principal) {
    requireAdmin(principal);
    return admin.listUsersByRole(UserRole.HELPER);
  }

  @PostMapping("/helpers")
  public AdminManagedUserResponse createHelper(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminCreateUserRequest req) {
    requireAdmin(principal);
    return admin.createUser(UserRole.HELPER, req);
  }

  @PostMapping("/helpers/{helperId}/update")
  public AdminManagedUserResponse updateHelper(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID helperId,
      @Valid @RequestBody AdminUpdateUserRequest req) {
    requireAdmin(principal);
    return admin.updateUser(helperId, UserRole.HELPER, req);
  }

  @PostMapping("/helpers/bulk-update")
  public AdminBulkOperationResponse bulkUpdateHelpers(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminBulkUserUpdateRequest req) {
    requireAdmin(principal);
    return admin.bulkUpdateUsers(UserRole.HELPER, req.userIds(), req.status());
  }

  @PostMapping("/helpers/{helperId}/delete")
  public void deleteHelper(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID helperId) {
    requireAdmin(principal);
    admin.deleteUser(helperId, UserRole.HELPER);
  }

  @GetMapping("/buyers")
  public List<AdminManagedUserResponse> listBuyers(@AuthenticationPrincipal UserPrincipal principal) {
    requireAdmin(principal);
    return admin.listUsersByRole(UserRole.BUYER);
  }

  @PostMapping("/buyers")
  public AdminManagedUserResponse createBuyer(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminCreateUserRequest req) {
    requireAdmin(principal);
    return admin.createUser(UserRole.BUYER, req);
  }

  @PostMapping("/buyers/{buyerId}/update")
  public AdminManagedUserResponse updateBuyer(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID buyerId,
      @Valid @RequestBody AdminUpdateUserRequest req) {
    requireAdmin(principal);
    return admin.updateUser(buyerId, UserRole.BUYER, req);
  }

  @PostMapping("/buyers/bulk-update")
  public AdminBulkOperationResponse bulkUpdateBuyers(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminBulkUserUpdateRequest req) {
    requireAdmin(principal);
    return admin.bulkUpdateUsers(UserRole.BUYER, req.userIds(), req.status());
  }

  @PostMapping("/buyers/{buyerId}/delete")
  public void deleteBuyer(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID buyerId) {
    requireAdmin(principal);
    admin.deleteUser(buyerId, UserRole.BUYER);
  }

  @GetMapping("/tasks")
  public List<TaskResponse> tasks(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false) TaskStatus status) {
    requireAdmin(principal);
    return toTaskResponses(tasks.listTasksForAdmin(status));
  }

  @GetMapping("/tasks/recent")
  public List<TaskResponse> recentTasks(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(defaultValue = "5") int limit) {
    requireAdmin(principal);
    return toTaskResponses(tasks.listRecentTasks(limit));
  }

  @GetMapping("/tasks/{taskId}")
  public TaskResponse task(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID taskId) {
    requireAdmin(principal);
    return toTaskResponses(List.of(tasks.getTask(taskId))).get(0);
  }

  @PostMapping("/tasks/{taskId}/status")
  public TaskResponse updateTaskStatus(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @Valid @RequestBody UpdateTaskStatusRequest req) {
    requireAdmin(principal);
    return toTaskResponses(List.of(tasks.updateStatusAsAdmin(taskId, req.status()))).get(0);
  }

  @PostMapping("/tasks/bulk-status")
  public AdminBulkOperationResponse bulkUpdateTaskStatus(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminBulkTaskStatusRequest req) {
    requireAdmin(principal);
    if (req.taskIds() == null || req.taskIds().isEmpty()) {
      throw new com.helpinminutes.api.errors.BadRequestException("At least one task id is required");
    }
    int success = 0;
    List<AdminBulkOperationFailure> failures = new ArrayList<>();
    for (UUID taskId : req.taskIds()) {
      if (taskId == null) {
        failures.add(new AdminBulkOperationFailure("missing-task-id", "Missing task id"));
        continue;
      }
      try {
        tasks.updateStatusAsAdmin(taskId, req.status());
        success++;
      } catch (Exception ex) {
        failures.add(new AdminBulkOperationFailure(taskId.toString(), sanitizeFailureMessage(ex, "Update failed")));
      }
    }
    return new AdminBulkOperationResponse(req.taskIds().size(), success, failures.size(), failures);
  }

  private List<TaskResponse> toTaskResponses(List<com.helpinminutes.api.tasks.model.TaskEntity> taskList) {
    if (taskList == null || taskList.isEmpty()) return List.of();
    Set<UUID> userIds = taskList.stream()
        .flatMap(t -> java.util.stream.Stream.of(t.getBuyerId(), t.getAssignedHelperId()))
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.toSet());
    Map<UUID, UserEntity> userById = new HashMap<>();
    if (!userIds.isEmpty()) {
      users.findAllById(userIds).forEach(u -> userById.put(u.getId(), u));
    }
    return taskList.stream().map(t -> {
      UserEntity buyer = userById.get(t.getBuyerId());
      UserEntity helper = userById.get(t.getAssignedHelperId());
      String buyerPhone = buyer == null ? null : buyer.getPhone();
      String helperPhone = helper == null ? null : helper.getPhone();
      String buyerName = buyer == null ? null : (buyer.getDisplayName() != null && !buyer.getDisplayName().isBlank() ? buyer.getDisplayName() : buyer.getPhone());
      String helperName = helper == null ? null : (helper.getDisplayName() != null && !helper.getDisplayName().isBlank() ? helper.getDisplayName() : helper.getPhone());
      return new TaskResponse(
          t.getId(),
          t.getBuyerId(),
          buyerPhone,
          buyerName,
          t.getTitle(),
          t.getDescription(),
          t.getUrgency(),
          t.getTimeMinutes(),
          t.getBudgetPaise(),
          t.getLat(),
          t.getLng(),
          t.getAddressText(),
          t.getScheduledAt(),
          t.getStatus(),
          t.getAssignedHelperId(),
          helperPhone,
          helperName,
          t.getArrivalOtp(),
          t.getCompletionOtp(),
          t.getArrivalSelfieUrl(),
          t.getArrivalSelfieLat(),
          t.getArrivalSelfieLng(),
          t.getArrivalSelfieAddress(),
          t.getArrivalSelfieCapturedAt(),
          t.getCompletionSelfieUrl(),
          t.getCompletionSelfieLat(),
          t.getCompletionSelfieLng(),
          t.getCompletionSelfieAddress(),
          t.getCompletionSelfieCapturedAt(),
          t.getBuyerRating() != null ? t.getBuyerRating().doubleValue() : null,
          t.getBuyerRatingComment(),
          t.getBuyerRatedAt(),
          t.getHelperRating() != null ? t.getHelperRating().doubleValue() : null,
          t.getHelperRatingComment(),
          t.getHelperRatedAt(),
          null,
          null,
          null,
          null,
          t.getCancelReason(),
          t.getCancelledByRole(),
          t.getCancelledAt(),
          t.getCreatedAt());
    }).toList();
  }

  private static String sanitizeFailureMessage(Exception ex, String fallback) {
    if (ex == null || ex.getMessage() == null) return fallback;
    String msg = ex.getMessage().trim();
    if (msg.isBlank()) return fallback;
    if (msg.length() > 180) return fallback;
    return msg;
  }
}
