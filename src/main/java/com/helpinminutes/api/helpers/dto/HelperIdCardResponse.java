package com.helpinminutes.api.helpers.dto;

import java.time.Instant;
import java.util.UUID;

public record HelperIdCardResponse(
    UUID helperId,
    String badgeId,
    String fullName,
    String phone,
    String kycStatus,
    String idNumberMasked,
    String selfieUrl,
    String idFrontUrl,
    String idBackUrl,
    Instant issuedAt
) {
}
