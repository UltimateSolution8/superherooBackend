package com.helpinminutes.api.learn.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record HelperAssessmentAttemptResponse(
    UUID id,
    UUID assessmentId,
    String assessmentTitle,
    UUID helperId,
    String helperName,
    int attemptNo,
    String status,
    Integer scorePercentage,
    Integer correctCount,
    Integer totalCount,
    Instant startedAt,
    Instant submittedAt,
    Integer durationSeconds,
    JsonNode answers
) {
}
