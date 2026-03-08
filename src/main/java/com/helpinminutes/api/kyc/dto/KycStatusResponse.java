package com.helpinminutes.api.kyc.dto;

import java.time.Instant;
import java.util.UUID;

public record KycStatusResponse(
        UUID id,
        String status,
        Instant createdAt,
        String recommendation,
        Double faceMatchScore,
        Double livenessScore,
        String reviewerNotes) {
}
