package com.helpinminutes.api.learn.dto;

import java.time.Instant;
import java.util.UUID;

public record HelperAssessmentStartResponse(
    UUID attemptId,
    UUID assessmentId,
    int attemptNo,
    int maxAttempts,
    Integer timeLimitMinutes,
    Instant startedAt
) {
}
