package com.helpinminutes.api.admin.controller;

import com.helpinminutes.api.admin.dto.AdminCreateUserRequest;
import com.helpinminutes.api.admin.dto.AdminManagedUserResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private final AdminService admin;
  private final TaskService tasks;

  public AdminController(AdminService admin, TaskService tasks) {
    this.admin = admin;
    this.tasks = tasks;
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
    return tasks.listTasksForAdmin(status).stream().map(AdminController::toTaskResponse).toList();
  }

  @GetMapping("/tasks/{taskId}")
  public TaskResponse task(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID taskId) {
    requireAdmin(principal);
    return toTaskResponse(tasks.getTask(taskId));
  }

  @PostMapping("/tasks/{taskId}/status")
  public TaskResponse updateTaskStatus(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @Valid @RequestBody UpdateTaskStatusRequest req) {
    requireAdmin(principal);
    return toTaskResponse(tasks.updateStatusAsAdmin(taskId, req.status()));
  }

  private static TaskResponse toTaskResponse(com.helpinminutes.api.tasks.model.TaskEntity t) {
    return TaskController.toResponse(t, true);
  }
}
