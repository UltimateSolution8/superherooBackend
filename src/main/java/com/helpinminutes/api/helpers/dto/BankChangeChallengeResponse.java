package com.helpinminutes.api.helpers.dto;

import java.time.Instant;
import java.util.UUID;

/** The challenge code is delivered by SMS only — changing payout details is exactly
 * the operation that must not be confirmable from the API response alone. */
public record BankChangeChallengeResponse(
    UUID challengeId,
    String maskedPhone,
    Instant expiresAt,
    int resendAfterSeconds
) {}

