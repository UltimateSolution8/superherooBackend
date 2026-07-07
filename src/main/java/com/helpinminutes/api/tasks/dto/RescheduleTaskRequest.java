package com.helpinminutes.api.tasks.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record RescheduleTaskRequest(
    @NotNull Instant scheduledAt
) {}
