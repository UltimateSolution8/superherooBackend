package com.helpinminutes.api.moderation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminModerationTaskDto(
    UUID taskId,
    UUID buyerId,
    String customerName,
    String customerPhone,
    String title,
    String description,
    String category,
    Long budgetPaise,
    String addressText,
    String status,
    String aiStatus,
    Integer riskScore,
    Integer confidence,
    Integer qualityScore,
    List<String> flags,
    List<String> reasons,
    String modelUsed,
    Instant createdAt
) {}
