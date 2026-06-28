package com.helpinminutes.api.tasks.dto;

import jakarta.validation.constraints.Min;

public record ExtendTaskRequest(
    @Min(1) int additionalTimeMinutes,
    @Min(0) long additionalBudgetPaise
) {}
