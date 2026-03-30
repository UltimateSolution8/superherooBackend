package com.helpinminutes.api.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record AdminBulkHelperKycActionRequest(
    @NotEmpty @Size(max = 500) List<@NotNull UUID> helperIds,
    @NotNull @Size(min = 6, max = 20) String action,
    @Size(max = 250) String reason
) {
}
