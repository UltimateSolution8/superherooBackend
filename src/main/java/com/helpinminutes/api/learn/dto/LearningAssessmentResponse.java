package com.helpinminutes.api.learn.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record LearningAssessmentResponse(
    UUID id,
    String title,
    String description,
    String instructions,
    int maxAttempts,
    Integer timeLimitMinutes,
    int passPercentage,
    JsonNode questionSchema,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {
}
