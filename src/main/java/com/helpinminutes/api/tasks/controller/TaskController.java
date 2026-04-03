package com.helpinminutes.api.tasks.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.helpers.dto.HelperIdCardResponse;
import com.helpinminutes.api.helpers.service.HelperService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.batches.dto.BatchDtos;
import com.helpinminutes.api.batches.service.BookingBatchService;
import com.helpinminutes.api.tasks.dto.CreateTaskRequest;
import com.helpinminutes.api.tasks.dto.CreateTaskResponse;
import com.helpinminutes.api.tasks.dto.CreateBulkTaskRequest;
import com.helpinminutes.api.tasks.dto.CreateBulkTaskResponse;
import com.helpinminutes.api.tasks.dto.CancelTaskRequest;
import com.helpinminutes.api.tasks.dto.TaskRatingRequest;
import com.helpinminutes.api.tasks.dto.TaskResponse;
import com.helpinminutes.api.tasks.dto.UpdateTaskStatusRequest;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskSelfieStage;
import com.helpinminutes.api.tasks.service.TaskMapper;
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
  private final TaskMapper taskMapper;
  private final BookingBatchService batches;
  private final HelperService helpers;

  public TaskController(TaskService tasks, TaskMapper taskMapper, BookingBatchService batches, HelperService helpers) {
    this.tasks = tasks;
    this.taskMapper = taskMapper;
    this.batches = batches;
    this.helpers = helpers;
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

  @PostMapping("/bulk")
  public CreateBulkTaskResponse createBulk(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody CreateBulkTaskRequest req) {
    if (principal.role() != UserRole.BUYER) {
      throw new ForbiddenException("Only buyers can create bulk tasks");
    }
    int helperCount = req.helperCount() == null ? 1 : req.helperCount();
    if (helperCount <= 1) {
      var single = tasks.createTask(
          principal.userId(),
          new CreateTaskRequest(
              req.title(),
              req.description(),
              req.urgency(),
              req.timeMinutes(),
              req.budgetPaise(),
              req.lat(),
              req.lng(),
              req.addressText(),
              req.scheduledAt()));
      return new CreateBulkTaskResponse(
          null,
          1,
          1,
          0,
          java.util.List.of(single.taskId()));
    }

    java.util.List<BatchDtos.CreateItem> lines = new java.util.ArrayList<>();
    for (int i = 0; i < helperCount; i++) {
      lines.add(new BatchDtos.CreateItem(
          req.title(),
          req.description(),
          req.urgency(),
          req.timeMinutes(),
          req.budgetPaise(),
          req.lat(),
          req.lng(),
          req.addressText(),
          req.scheduledAt(),
          "bulk-" + (i + 1),
          3));
    }

    String safeTitle = req.title() == null || req.title().isBlank() ? "Bulk Request" : req.title().trim();
    BatchDtos.CreateResponse created = batches.create(
        principal.userId(),
        UserRole.BUYER,
        new BatchDtos.CreateRequest(
            safeTitle + " (Bulk x" + helperCount + ")",
            "Created from buyer app bulk request",
            req.scheduledAt(),
            null,
            null,
            "buyer-bulk-" + principal.userId() + "-" + System.currentTimeMillis(),
            lines));

    java.util.List<UUID> taskIds = batches.getItems(principal.userId(), UserRole.BUYER, created.batchId()).stream()
        .map(BatchDtos.BatchItemResponse::taskId)
        .filter(java.util.Objects::nonNull)
        .toList();

    return new CreateBulkTaskResponse(
        created.batchId(),
        helperCount,
        created.createdCount(),
        created.failedCount(),
        taskIds);
  }

  @PostMapping("/{taskId}/accept")
  public TaskResponse accept(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId) {
    if (principal.role() != UserRole.HELPER) {
      throw new ForbiddenException("Only helpers can accept tasks");
    }
    return tasks.acceptTask(principal.userId(), taskId);
  }

  @PostMapping("/{taskId}/status")
  public TaskResponse updateStatus(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @Valid @RequestBody UpdateTaskStatusRequest req) {
    if (principal.role() != UserRole.HELPER) {
      throw new ForbiddenException("Only helpers can update task status");
    }
    return tasks.updateStatusAsHelper(principal.userId(), taskId, req.status(), req.otp());
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

    return tasks.uploadTaskSelfie(
        principal.userId(),
        taskId,
        stage,
        selfie,
        lat,
        lng,
        addressText,
        capturedAt);
  }

  @GetMapping("/{taskId}")
  public TaskResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID taskId) {
    TaskEntity task = tasks.getTask(taskId);

    boolean canSee = (principal.role() == UserRole.BUYER && principal.userId().equals(task.getBuyerId()))
        || (principal.role() == UserRole.HELPER && principal.userId().equals(task.getAssignedHelperId()))
        || principal.role() == UserRole.ADMIN;

    if (!canSee) {
      throw new ForbiddenException("Not allowed");
    }

    boolean includeOtp = principal.role() == UserRole.BUYER || principal.role() == UserRole.ADMIN;
    return taskMapper.toResponse(task, includeOtp);
  }

  @GetMapping("/{taskId}/helper-id-card")
  public HelperIdCardResponse helperIdCard(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId) {
    TaskEntity task = tasks.getTask(taskId);
    boolean canSee = (principal.role() == UserRole.BUYER && principal.userId().equals(task.getBuyerId()))
        || (principal.role() == UserRole.HELPER && principal.userId().equals(task.getAssignedHelperId()))
        || principal.role() == UserRole.ADMIN;
    if (!canSee) {
      throw new ForbiddenException("Not allowed");
    }
    if (task.getAssignedHelperId() == null) {
      throw new com.helpinminutes.api.errors.BadRequestException("No helper assigned yet");
    }
    return helpers.getIdCard(task.getAssignedHelperId());
  }

  @GetMapping("/available")
  public java.util.List<TaskResponse> available(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal.role() != UserRole.HELPER) {
      throw new ForbiddenException("Only helpers can view available tasks");
    }
    return taskMapper.toResponseList(tasks.listAvailableTasks(principal.userId()), false);
  }

  @GetMapping("/mine")
  public java.util.List<TaskResponse> mine(@AuthenticationPrincipal UserPrincipal principal) {
    UserRole role = principal.role();
    boolean includeOtp = role == UserRole.BUYER || role == UserRole.ADMIN;
    return taskMapper.toResponseList(tasks.listTasksForUser(principal.userId(), role), includeOtp);
  }

  @GetMapping("/my")
  public java.util.List<TaskResponse> my(@AuthenticationPrincipal UserPrincipal principal) {
    return mine(principal);
  }

  @PostMapping("/{taskId}/rating")
  public TaskResponse rateTask(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @Valid @RequestBody TaskRatingRequest req) {
    return tasks.rateTask(principal.userId(), principal.role(), taskId, req);
  }

  @PostMapping("/{taskId}/cancel")
  public TaskResponse cancelTask(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @Valid @RequestBody CancelTaskRequest req) {
    return tasks.cancelTask(principal.userId(), principal.role(), taskId, req.reason());
  }
}
