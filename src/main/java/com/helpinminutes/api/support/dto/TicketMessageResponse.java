package com.helpinminutes.api.support.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketMessageResponse(
    UUID id,
    String authorType,
    UUID authorUserId,
    String message,
    Instant createdAt
) {}

