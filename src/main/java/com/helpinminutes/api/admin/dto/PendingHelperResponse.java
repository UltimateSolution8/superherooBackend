package com.helpinminutes.api.admin.dto;

import com.helpinminutes.api.helpers.model.HelperKycStatus;
import java.time.Instant;
import java.util.UUID;

public record PendingHelperResponse(
    UUID helperId,
    String phone,
    HelperKycStatus kycStatus,
    String kycFullName,
    String kycIdNumber,
    String kycDocFrontUrl,
    String kycDocBackUrl,
    String kycSelfieUrl,
    Instant kycSubmittedAt,
    Instant createdAt
) {}
