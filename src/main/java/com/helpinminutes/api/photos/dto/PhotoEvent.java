package com.helpinminutes.api.photos.dto;

import java.time.Instant;
import java.util.UUID;

public class PhotoEvent {
    private UUID photoId;
    private UUID jobId;
    private UUID userId;
    private String storagePath;
    private String contentType;
    private Long size;
    private Instant uploadedAt;

    public PhotoEvent() {
    }

    public PhotoEvent(UUID photoId, UUID jobId, UUID userId, String storagePath, String contentType, Long size,
            Instant uploadedAt) {
        this.photoId = photoId;
        this.jobId = jobId;
        this.userId = userId;
        this.storagePath = storagePath;
        this.contentType = contentType;
        this.size = size;
        this.uploadedAt = uploadedAt;
    }

    public UUID getPhotoId() {
        return photoId;
    }

    public void setPhotoId(UUID photoId) {
        this.photoId = photoId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
