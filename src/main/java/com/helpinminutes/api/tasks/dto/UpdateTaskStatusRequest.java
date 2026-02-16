package com.helpinminutes.api.tasks.dto;

import com.helpinminutes.api.tasks.model.TaskStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateTaskStatusRequest(
    @NotNull TaskStatus status
) {}
