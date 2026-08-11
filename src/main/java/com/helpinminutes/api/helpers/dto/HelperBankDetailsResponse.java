package com.helpinminutes.api.helpers.dto;

import java.time.Instant;

public record HelperBankDetailsResponse(
    java.util.UUID accountId,
    String accountHolderName,
    String bankName,
    String bankAccountLast4,
    String maskedAccountNumber,
    String ifscCode,
    Instant ifscVerifiedAt,
    String accountVerificationStatus,
    String payoutStatus,
    boolean payoutEligible,
    Instant savedAt
) {}
