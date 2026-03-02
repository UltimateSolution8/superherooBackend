package com.helpinminutes.api.tasks.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelTaskRequest(
    @NotBlank String reason
) {}
