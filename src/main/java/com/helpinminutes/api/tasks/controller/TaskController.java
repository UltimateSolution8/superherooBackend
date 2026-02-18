package com.helpinminutes.api.tasks.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.tasks.dto.CreateTaskRequest;
import com.helpinminutes.api.tasks.dto.CreateTaskResponse;
import com.helpinminutes.api.tasks.dto.TaskResponse;
import com.helpinminutes.api.tasks.dto.UpdateTaskStatusRequest;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskSelfieStage;
import com.helpinminutes.api.tasks.service.TaskService;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @PostMapping
  public CreateTaskResponse create(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody CreateTaskRequest req) {
    if (principal.role() != UserRole.BUYER) {
      throw new ForbiddenException("Only buyers can create tasks");
    }
    var result = tasks.createTask(principal.userId(), req);
    return new CreateTaskResponse(result.taskId(), result.offeredTo());
  }

  @PostMapping("/{taskId}/accept")
  public TaskResponse accept(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId) {
    if (principal.role() != UserRole.HELPER) {
      throw new ForbiddenException("Only helpers can accept tasks");
    }
    TaskEntity task = tasks.acceptTask(principal.userId(), taskId);
    return toResponse(task, false);
  }

  @PostMapping("/{taskId}/status")
  public TaskResponse updateStatus(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @Valid @RequestBody UpdateTaskStatusRequest req) {
    if (principal.role() != UserRole.HELPER) {
      throw new ForbiddenException("Only helpers can update task status");
    }
    TaskEntity task = tasks.updateStatusAsHelper(principal.userId(), taskId, req.status(), req.otp());
    return toResponse(task, false);
  }

  @PostMapping(value = "/{taskId}/selfie", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public TaskResponse uploadSelfie(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @RequestParam TaskSelfieStage stage,
      @RequestParam double lat,
      @RequestParam double lng,
      @RequestParam(required = false) String addressText,
      @RequestParam(required = false) String capturedAt,
      @RequestParam("selfie") MultipartFile selfie) {
    if (principal.role() != UserRole.HELPER) {
      throw new ForbiddenException("Only helpers can upload task selfies");
    }

    TaskEntity task = tasks.uploadTaskSelfie(
        principal.userId(),
        taskId,
        stage,
        selfie,
        lat,
        lng,
        addressText,
        capturedAt);

    return toResponse(task, false);
  }

  @GetMapping("/{taskId}")
  public TaskResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID taskId) {
    TaskEntity task = tasks.getTask(taskId);

    boolean canSee =
        (principal.role() == UserRole.BUYER && principal.userId().equals(task.getBuyerId()))
            || (principal.role() == UserRole.HELPER && principal.userId().equals(task.getAssignedHelperId()))
            || principal.role() == UserRole.ADMIN;

    if (!canSee) {
      throw new ForbiddenException("Not allowed");
    }

    boolean includeOtp = principal.role() == UserRole.BUYER || principal.role() == UserRole.ADMIN;
    return toResponse(task, includeOtp);
  }

  @GetMapping("/mine")
  public java.util.List<TaskResponse> mine(@AuthenticationPrincipal UserPrincipal principal) {
    UserRole role = principal.role();
    boolean includeOtp = role == UserRole.BUYER || role == UserRole.ADMIN;
    return tasks.listTasksForUser(principal.userId(), role)
        .stream()
        .map(t -> toResponse(t, includeOtp))
        .toList();
  }

  public static TaskResponse toResponse(TaskEntity t, boolean includeOtp) {
    return new TaskResponse(
        t.getId(),
        t.getBuyerId(),
        t.getTitle(),
        t.getDescription(),
        t.getUrgency(),
        t.getTimeMinutes(),
        t.getBudgetPaise(),
        t.getLat(),
        t.getLng(),
        t.getAddressText(),
        t.getStatus(),
        t.getAssignedHelperId(),
        includeOtp ? t.getArrivalOtp() : null,
        includeOtp ? t.getCompletionOtp() : null,
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
        t.getCreatedAt());
  }
}
