package com.helpinminutes.api.photos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public class PhotoUploadRequest {
    @NotNull
    private UUID jobId;

    @NotBlank
    @Pattern(regexp = "^(arrival|completion)$")
    private String photoType;

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public String getPhotoType() {
        return photoType;
    }

    public void setPhotoType(String photoType) {
        this.photoType = photoType;
    }
}
