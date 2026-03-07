package com.helpinminutes.api.photos.dto;

import java.util.Map;
import java.util.UUID;

public class PresignedUploadResponse {
    private UUID photoId;
    private String presignedUrl;
    private Map<String, String> uploadHeaders;
    private long expiresIn;
    private String uploadKey;

    public PresignedUploadResponse() {
    }

    public PresignedUploadResponse(UUID photoId, String presignedUrl, Map<String, String> uploadHeaders, long expiresIn,
            String uploadKey) {
        this.photoId = photoId;
        this.presignedUrl = presignedUrl;
        this.uploadHeaders = uploadHeaders;
        this.expiresIn = expiresIn;
        this.uploadKey = uploadKey;
    }

    public UUID getPhotoId() {
        return photoId;
    }

    public void setPhotoId(UUID photoId) {
        this.photoId = photoId;
    }

    public String getPresignedUrl() {
        return presignedUrl;
    }

    public void setPresignedUrl(String presignedUrl) {
        this.presignedUrl = presignedUrl;
    }

    public Map<String, String> getUploadHeaders() {
        return uploadHeaders;
    }

    public void setUploadHeaders(Map<String, String> uploadHeaders) {
        this.uploadHeaders = uploadHeaders;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public String getUploadKey() {
        return uploadKey;
    }

    public void setUploadKey(String uploadKey) {
        this.uploadKey = uploadKey;
    }
}
