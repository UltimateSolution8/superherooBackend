package com.helpinminutes.api.learn.dto;

public record AdminLearningAssetUploadResponse(
    String url,
    String contentType,
    long sizeBytes,
    String fileName
) {
}

