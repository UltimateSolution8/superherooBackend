package com.helpinminutes.api.tasks.dto;

import com.helpinminutes.api.tasks.model.TaskUrgency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateTaskRequest(
    @NotBlank String title,
    @NotBlank String description,
    @NotNull TaskUrgency urgency,
    @NotNull @Min(1) @Max(1440) Integer timeMinutes,
    @NotNull @Min(0) Long budgetPaise,
    @Min(-90) @Max(90) double lat,
    @Min(-180) @Max(180) double lng,
    String addressText
) {}
