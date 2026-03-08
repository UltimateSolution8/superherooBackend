package com.helpinminutes.api.kyc.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record KycStartResponse(
        UUID id,
        UploadUrls uploadUrls,
        String status) {
    public record UploadUrls(
            UploadUrlInfo video,
            UploadUrlInfo docFront,
            UploadUrlInfo docBack) {
    }

    public record UploadUrlInfo(
            String url,
            String method,
            int expiresIn,
            String key) {
    }
}
