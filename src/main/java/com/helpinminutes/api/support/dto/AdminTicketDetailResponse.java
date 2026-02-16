package com.helpinminutes.api.support.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminTicketDetailResponse(
    UUID id,
    UUID createdByUserId,
    String createdByRole,
    String createdByPhone,
    String category,
    String subject,
    String status,
    String priority,
    UUID relatedTaskId,
    UUID assigneeUserId,
    Instant lastMessageAt,
    Instant createdAt,
    Instant updatedAt,
    List<TicketMessageResponse> messages
) {}

