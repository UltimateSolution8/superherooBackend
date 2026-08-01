package com.helpinminutes.api.helpers.dto;

import java.time.Instant;

public record HelperBankDetailsResponse(
    String accountHolderName,
    String bankName,
    String bankAccountLast4,
    String ifscCode,
    String upiIdMasked,
    Instant savedAt
) {}
