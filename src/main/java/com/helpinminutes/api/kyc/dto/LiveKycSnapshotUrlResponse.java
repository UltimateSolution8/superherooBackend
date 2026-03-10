package com.helpinminutes.api.kyc.dto;

public record LiveKycSnapshotUrlResponse(
    String uploadUrl,
    String key,
    long expiresInSeconds
) {}
