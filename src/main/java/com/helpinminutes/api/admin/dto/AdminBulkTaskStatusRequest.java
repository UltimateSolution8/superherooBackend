package com.helpinminutes.api.admin.dto;

import com.helpinminutes.api.tasks.model.TaskStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record AdminBulkTaskStatusRequest(
    @NotEmpty @Size(max = 500) List<@NotNull UUID> taskIds,
    @NotNull TaskStatus status
) {
}
