package com.helpinminutes.api.admin.dto;

import java.util.List;

public record AdminBulkOperationResponse(
    int requested,
    int succeeded,
    int failed,
    List<AdminBulkOperationFailure> failures
) {
}
