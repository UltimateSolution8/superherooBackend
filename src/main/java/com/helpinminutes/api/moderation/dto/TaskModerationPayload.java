package com.helpinminutes.api.moderation.dto;

import java.util.List;
import java.util.UUID;

public record TaskModerationPayload(
    UUID taskId,
    UUID buyerId,
    String title,
    String description,
    String category,
    Long budgetPaise,
    String addressText,
    List<String> imageUrls
) {}
