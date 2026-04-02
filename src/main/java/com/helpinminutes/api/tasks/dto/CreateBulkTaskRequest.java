package com.helpinminutes.api.tasks.dto;

import com.helpinminutes.api.tasks.model.TaskUrgency;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateBulkTaskRequest(
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Size(min = 10, max = 2000) String description,
    @NotNull TaskUrgency urgency,
    @NotNull @Min(1) @Max(480) Integer timeMinutes,
    @NotNull @Min(0) Long budgetPaise,
    @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
    @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
    @Size(max = 500) String addressText,
    Instant scheduledAt,
    @NotNull @Min(1) @Max(25) Integer helperCount
) {
}
