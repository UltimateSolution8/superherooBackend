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

  /**
   * Lifetime offers pushed to this partner, and how many they accepted.
   *
   * <p>Ranking inputs. A partner who reliably accepts is worth offering to ahead
   * of one who lets offers lapse, because a lapsed offer costs the citizen a full
   * offer window. Lifetime counts rather than a rolling window: cheap to maintain,
   * and the ratio stabilises quickly enough at our volumes.
   */
  @Column(name = "offers_seen", nullable = false)
  private long offersSeen;

  @Column(name = "offers_accepted", nullable = false)
  private long offersAccepted;

  /**
   * Last time this partner was offered any task.
   *
   * <p>Drives the anti-starvation term. Ranking was pure distance, so in a
   * cluster the same nearest partners were re-offered every wave while others
   * online in the same area were never contacted at all.
   */
  @Column(name = "last_offered_at")
  private Instant lastOfferedAt;

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

  public long getOffersSeen() {
    return offersSeen;
  }

  public void setOffersSeen(long offersSeen) {
    this.offersSeen = offersSeen;
  }

  public long getOffersAccepted() {
    return offersAccepted;
  }

  public void setOffersAccepted(long offersAccepted) {
    this.offersAccepted = offersAccepted;
  }

  public Instant getLastOfferedAt() {
    return lastOfferedAt;
  }

  public void setLastOfferedAt(Instant lastOfferedAt) {
    this.lastOfferedAt = lastOfferedAt;
  }

  /**
   * Share of offers this partner accepted, or {@code null} until there is enough
   * history to mean anything.
   *
   * <p>A brand-new partner must not be penalised for having no record, so the
   * caller substitutes a neutral value rather than treating 0/0 as a zero rate.
   */
  public Double acceptanceRate() {
    if (offersSeen < MIN_OFFERS_FOR_ACCEPTANCE_RATE) return null;
    return (double) offersAccepted / (double) offersSeen;
  }

  /** Below this many offers the acceptance ratio is noise, not signal. */
  private static final long MIN_OFFERS_FOR_ACCEPTANCE_RATE = 5;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
