package com.helpinminutes.api.kyc.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminKycResponse(
    UUID id,
    UUID helperId,
    String helperName,
    String status,
    Instant createdAt,
    String videoUrl,
    String docFrontUrl,
    String docBackUrl,
    String selfieUrl,
    String liveRoomId,
    String liveRecordingUrl,
    Instant liveStartedAt,
    Instant liveEndedAt,
    String recommendation,
    Double faceMatchScore,
    Double livenessScore,
    String reviewerNotes) {
}
