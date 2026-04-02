package com.helpinminutes.api.learn.dto;

import java.time.Instant;
import java.util.UUID;

public record HelperTrainingProgressResponse(
    UUID id,
    UUID materialId,
    String materialTitle,
    UUID helperId,
    String helperName,
    String status,
    int progressPercent,
    int viewedSeconds,
    Instant lastAccessedAt,
    Instant completedAt,
    Instant updatedAt
) {
}
