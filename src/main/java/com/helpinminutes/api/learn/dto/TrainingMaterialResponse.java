package com.helpinminutes.api.learn.dto;

import java.time.Instant;
import java.util.UUID;

public record TrainingMaterialResponse(
    UUID id,
    String title,
    String description,
    String contentType,
    String resourceUrl,
    String thumbnailUrl,
    Integer durationSeconds,
    boolean active,
    Instant createdAt,
    Instant updatedAt,
    Integer helperProgressPercent,
    String helperProgressStatus,
    Integer totalLearners,
    Integer completedLearners
) {
}
