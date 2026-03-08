package com.helpinminutes.api.kyc.model;

import com.helpinminutes.api.users.model.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_requests")
public class KycRequestEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycRequestStatus status;

    @Column(name = "video_path")
    private String videoPath;

    @Column(name = "doc_front_path")
    private String docFrontPath;

    @Column(name = "doc_back_path")
    private String docBackPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ocr_extracted_data")
    private String ocrExtractedData; // JSON string

    @Column(name = "face_match_score")
    private Double faceMatchScore;

    @Column(name = "liveness_score")
    private Double livenessScore;

    @Column(name = "recommended_action")
    private String recommendedAction;

    @Column(name = "processed_at")
    private Instant processedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_admin_id")
    private UserEntity reviewerAdmin;

    @Column(name = "reviewer_notes")
    private String reviewerNotes;

    @Column(name = "retention_expires_at")
    private Instant retentionExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_result")
    private String rawResult; // JSON string

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = KycRequestStatus.SUBMITTED;
        }
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UserEntity getUser() {
        return user;
    }

    public void setUser(UserEntity user) {
        this.user = user;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public KycRequestStatus getStatus() {
        return status;
    }

    public void setStatus(KycRequestStatus status) {
        this.status = status;
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getDocFrontPath() {
        return docFrontPath;
    }

    public void setDocFrontPath(String docFrontPath) {
        this.docFrontPath = docFrontPath;
    }

    public String getDocBackPath() {
        return docBackPath;
    }

    public void setDocBackPath(String docBackPath) {
        this.docBackPath = docBackPath;
    }

    public String getOcrExtractedData() {
        return ocrExtractedData;
    }

    public void setOcrExtractedData(String ocrExtractedData) {
        this.ocrExtractedData = ocrExtractedData;
    }

    public Double getFaceMatchScore() {
        return faceMatchScore;
    }

    public void setFaceMatchScore(Double faceMatchScore) {
        this.faceMatchScore = faceMatchScore;
    }

    public Double getLivenessScore() {
        return livenessScore;
    }

    public void setLivenessScore(Double livenessScore) {
        this.livenessScore = livenessScore;
    }

    public String getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(String recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public UserEntity getReviewerAdmin() {
        return reviewerAdmin;
    }

    public void setReviewerAdmin(UserEntity reviewerAdmin) {
        this.reviewerAdmin = reviewerAdmin;
    }

    public String getReviewerNotes() {
        return reviewerNotes;
    }

    public void setReviewerNotes(String reviewerNotes) {
        this.reviewerNotes = reviewerNotes;
    }

    public Instant getRetentionExpiresAt() {
        return retentionExpiresAt;
    }

    public void setRetentionExpiresAt(Instant retentionExpiresAt) {
        this.retentionExpiresAt = retentionExpiresAt;
    }

    public String getRawResult() {
        return rawResult;
    }

    public void setRawResult(String rawResult) {
        this.rawResult = rawResult;
    }
}
