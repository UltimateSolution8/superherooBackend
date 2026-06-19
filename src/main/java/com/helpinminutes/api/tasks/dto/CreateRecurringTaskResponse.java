package com.helpinminutes.api.tasks.dto;

import java.util.List;
import java.util.UUID;

public record CreateRecurringTaskResponse(
    UUID recurringTaskId,
    List<UUID> taskIds
) {}
