package com.helpinminutes.api.tasks.dto;

import java.util.List;
import java.util.UUID;

public record CreateBulkTaskResponse(
    UUID batchId,
    int helperCountRequested,
    int createdCount,
    int failedCount,
    List<UUID> taskIds
) {
}
