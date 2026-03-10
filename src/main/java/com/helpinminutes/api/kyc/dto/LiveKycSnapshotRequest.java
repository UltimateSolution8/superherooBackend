package com.helpinminutes.api.kyc.dto;

import jakarta.validation.constraints.NotBlank;

public record LiveKycSnapshotRequest(
    @NotBlank String kind,
    @NotBlank String key
) {}
