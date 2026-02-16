package com.helpinminutes.api.tasks.dto;

import java.util.List;
import java.util.UUID;

public record CreateTaskResponse(
    UUID taskId,
    List<UUID> offeredHelperIds
) {}
