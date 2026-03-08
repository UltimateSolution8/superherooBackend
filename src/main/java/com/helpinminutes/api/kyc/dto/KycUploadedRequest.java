package com.helpinminutes.api.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

public record KycUploadedRequest(
        @NotNull S3Keys s3Keys,
        @Min(0) Integer durationSeconds) {
    public record S3Keys(
            @NotBlank String video,
            @NotBlank String docFront,
            String docBack) {
    }
}
