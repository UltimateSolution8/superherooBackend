package com.helpinminutes.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record AdminBulkBuyerCsvAccessRequest(
    List<UUID> userIds,
    @NotNull Boolean enabled
) {}
