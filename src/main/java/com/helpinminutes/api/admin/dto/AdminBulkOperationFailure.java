package com.helpinminutes.api.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminBulkOperationFailure(
    @NotBlank String id,
    @NotBlank String message
) {
}
