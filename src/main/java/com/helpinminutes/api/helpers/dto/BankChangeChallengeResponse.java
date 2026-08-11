package com.helpinminutes.api.helpers.dto;

import java.time.Instant;
import java.util.UUID;

public record BankChangeChallengeResponse(
    UUID challengeId,
    String maskedPhone,
    Instant expiresAt,
    int resendAfterSeconds,
    String devOtp
) {}

