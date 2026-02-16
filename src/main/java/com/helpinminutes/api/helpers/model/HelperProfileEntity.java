package com.helpinminutes.api.helpers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "helper_profiles")
public class HelperProfileEntity {
  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kyc_status", nullable = false)
  private HelperKycStatus kycStatus;

  @Column(name = "kyc_rejection_reason")
  private String kycRejectionReason;

  @Column(name = "kyc_full_name")
  private String kycFullName;

  @Column(name = "kyc_id_number")
  private String kycIdNumber;

  @Column(name = "kyc_doc_front_url")
  private String kycDocFrontUrl;

  @Column(name = "kyc_doc_back_url")
  private String kycDocBackUrl;

  @Column(name = "kyc_selfie_url")
  private String kycSelfieUrl;

  @Column(name = "kyc_submitted_at")
  private Instant kycSubmittedAt;

  @Column(nullable = false)
  private BigDecimal rating;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
    if (kycStatus == null) {
      kycStatus = HelperKycStatus.PENDING;
    }
    if (rating == null) {
      rating = BigDecimal.ZERO;
    }
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public HelperKycStatus getKycStatus() {
    return kycStatus;
  }

  public void setKycStatus(HelperKycStatus kycStatus) {
    this.kycStatus = kycStatus;
  }

  public String getKycRejectionReason() {
    return kycRejectionReason;
  }

  public void setKycRejectionReason(String kycRejectionReason) {
    this.kycRejectionReason = kycRejectionReason;
  }

  public String getKycFullName() {
    return kycFullName;
  }

  public void setKycFullName(String kycFullName) {
    this.kycFullName = kycFullName;
  }

  public String getKycIdNumber() {
    return kycIdNumber;
  }

  public void setKycIdNumber(String kycIdNumber) {
    this.kycIdNumber = kycIdNumber;
  }

  public String getKycDocFrontUrl() {
    return kycDocFrontUrl;
  }

  public void setKycDocFrontUrl(String kycDocFrontUrl) {
    this.kycDocFrontUrl = kycDocFrontUrl;
  }

  public String getKycDocBackUrl() {
    return kycDocBackUrl;
  }

  public void setKycDocBackUrl(String kycDocBackUrl) {
    this.kycDocBackUrl = kycDocBackUrl;
  }

  public String getKycSelfieUrl() {
    return kycSelfieUrl;
  }

  public void setKycSelfieUrl(String kycSelfieUrl) {
    this.kycSelfieUrl = kycSelfieUrl;
  }

  public Instant getKycSubmittedAt() {
    return kycSubmittedAt;
  }

  public void setKycSubmittedAt(Instant kycSubmittedAt) {
    this.kycSubmittedAt = kycSubmittedAt;
  }

  public BigDecimal getRating() {
    return rating;
  }

  public void setRating(BigDecimal rating) {
    this.rating = rating;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
