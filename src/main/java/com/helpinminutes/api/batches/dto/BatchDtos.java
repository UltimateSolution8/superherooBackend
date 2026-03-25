package com.helpinminutes.api.batches.dto;

import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BatchDtos {
  public record PreviewRequest(
      @NotEmpty @Size(max = 250) List<@Valid PreviewItem> items
  ) {}

  public record PreviewItem(
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 2000) String description,
      @NotNull TaskUrgency urgency,
      @NotNull @Min(1) @Max(480) Integer timeMinutes,
      @NotNull @Min(0) Long budgetPaise,
      @NotNull Double lat,
      @NotNull Double lng,
      @Size(max = 250) String addressText,
      Instant scheduledAt
  ) {}

  public record PreviewItemResult(
      int lineNo,
      long recommendedBudgetPaise,
      String confidence,
      List<String> errors
  ) {}

  public record PreviewResponse(
      int total,
      int valid,
      int invalid,
      List<PreviewItemResult> items
  ) {}

  public record CreateRequest(
      @NotBlank @Size(max = 160) String title,
      @Size(max = 2000) String notes,
      Instant scheduledWindowStart,
      Instant scheduledWindowEnd,
      UUID buyerId,
      @Size(max = 120) String idempotencyKey,
      @NotEmpty @Size(max = 250) List<@Valid CreateItem> items
  ) {}

  public record CreateItem(
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 2000) String description,
      @NotNull TaskUrgency urgency,
      @NotNull @Min(1) @Max(480) Integer timeMinutes,
      @NotNull @Min(0) Long budgetPaise,
      @NotNull Double lat,
      @NotNull Double lng,
      @Size(max = 250) String addressText,
      Instant scheduledAt,
      @Size(max = 120) String externalRef,
      @Min(1) @Max(5) Integer priority
  ) {}

  public record CreateResponse(
      UUID batchId,
      int requestedCount,
      int createdCount,
      int failedCount,
      String status
  ) {}

  public record BatchSummaryResponse(
      UUID id,
      String title,
      String notes,
      String status,
      Instant createdAt,
      Instant scheduledWindowStart,
      Instant scheduledWindowEnd,
      int total,
      Map<String, Long> byTaskStatus
  ) {}

  public record BatchItemResponse(
      UUID id,
      int lineNo,
      String externalRef,
      int priority,
      String lineStatus,
      String errorMessage,
      UUID taskId,
      TaskStatus taskStatus,
      String taskTitle,
      String helperId,
      String helperName,
      Double helperDistanceMeters
  ) {}

  public record BatchLiveResponse(
      UUID batchId,
      String room,
      Map<String, Long> counters,
      List<BatchItemResponse> items
  ) {}
}
