package com.helpinminutes.api.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record AdminBulkUserUpdateRequest(
    @NotEmpty @Size(max = 500) List<@NotNull UUID> userIds,
    @NotNull @Size(min = 3, max = 20) String status
) {
}
