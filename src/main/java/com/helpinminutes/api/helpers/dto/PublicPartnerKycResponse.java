package com.helpinminutes.api.helpers.dto;

import com.helpinminutes.api.helpers.model.HelperKycStatus;
import java.time.Instant;
import java.util.UUID;

public record PublicPartnerKycResponse(
    UUID id,
    HelperKycStatus status,
    String referenceId,
    Instant submittedAt
) {}
