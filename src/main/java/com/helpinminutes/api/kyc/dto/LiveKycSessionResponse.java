package com.helpinminutes.api.kyc.dto;

import java.time.Instant;
import java.util.UUID;

public record LiveKycSessionResponse(
    UUID id,
    UUID helperId,
    String helperName,
    Long appId,
    String roomId,
    String userId,
    String userName,
    String token,
    String status,
    Instant expiresAt
) {}
