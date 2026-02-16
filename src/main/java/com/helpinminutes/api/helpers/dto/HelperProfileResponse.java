package com.helpinminutes.api.helpers.dto;

import com.helpinminutes.api.helpers.model.HelperKycStatus;
import java.time.Instant;

public record HelperProfileResponse(
    HelperKycStatus kycStatus,
    String kycRejectionReason,
    String kycFullName,
    String kycIdNumber,
    String kycDocFrontUrl,
    String kycDocBackUrl,
    String kycSelfieUrl,
    Instant kycSubmittedAt
) {}
