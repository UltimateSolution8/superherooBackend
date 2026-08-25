package com.helpinminutes.api.moderation.dto;

import java.util.UUID;

// TEMP: MANUAL_MODERATION_MODE
public record AdminApproveAndAssignRequest(
    UUID helperId,
    String remarks
) {}
