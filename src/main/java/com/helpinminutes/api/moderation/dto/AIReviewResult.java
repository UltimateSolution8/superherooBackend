package com.helpinminutes.api.moderation.dto;

import java.util.List;

public record AIReviewResult(
    String status, // "APPROVED" or "REVIEW"
    int confidence, // 0 - 100
    int riskScore, // 0 - 100
    int qualityScore, // 0 - 100
    List<String> reasons,
    List<String> flags,
    boolean requiresAdminReview,
    String rawResponse,
    String modelUsed,
    long durationMs
) {}
