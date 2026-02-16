package com.helpinminutes.api.support.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
    UUID id,
    String category,
    String subject,
    String status,
    String priority,
    UUID relatedTaskId,
    Instant lastMessageAt,
    Instant createdAt,
    Instant updatedAt
) {}

