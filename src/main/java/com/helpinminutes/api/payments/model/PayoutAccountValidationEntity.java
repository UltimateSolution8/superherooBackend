package com.helpinminutes.api.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One penny drop against a partner's bank account.
 *
 * <p>Rows are kept after they terminate: a failed or disputed verification is
 * exactly what someone needs to look at later, and the daily cap is counted from
 * this history.
 */
@Entity
@Table(name = "payout_account_validations")
public class PayoutAccountValidationEntity {

  public static final String PENDING = "PENDING";
  public static final String VERIFIED = "VERIFIED";
  public static final String FAILED = "FAILED";
  /** Reached the bank, but the name did not match KYC. A human decides. */
  public static final String MANUAL_REVIEW = "MANUAL_REVIEW";

  @Id private UUID id;

  @Column(name = "payout_account_id", nullable = false) private UUID payoutAccountId;
  @Column(name = "helper_id", nullable = false) private UUID helperId;
  @Column(nullable = false, length = 32) private String provider = "RAZORPAYX";
  @Column(name = "provider_validation_id", length = 64) private String providerValidationId;
  @Column(nullable = false, length = 24) private String status = PENDING;
  @Column(name = "amount_paise", nullable = false) private long amountPaise = 100L;
  @Column(name = "registered_name", length = 200) private String registeredName;
  @Column(name = "name_match_score") private Integer nameMatchScore;
  @Column(length = 64) private String utr;
  @Column(name = "failure_reason", length = 300) private String failureReason;
  @Column(nullable = false) private int attempts;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "completed_at") private Instant completedAt;

  @PrePersist void prePersist() {
    if (id == null) id = UUID.randomUUID();
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID value) { id = value; }
  public UUID getPayoutAccountId() { return payoutAccountId; }
  public void setPayoutAccountId(UUID value) { payoutAccountId = value; }
  public UUID getHelperId() { return helperId; }
  public void setHelperId(UUID value) { helperId = value; }
  public String getProvider() { return provider; }
  public void setProvider(String value) { provider = value; }
  public String getProviderValidationId() { return providerValidationId; }
  public void setProviderValidationId(String value) { providerValidationId = value; }
  public String getStatus() { return status; }
  public void setStatus(String value) { status = value; }
  public long getAmountPaise() { return amountPaise; }
  public void setAmountPaise(long value) { amountPaise = value; }
  public String getRegisteredName() { return registeredName; }
  public void setRegisteredName(String value) { registeredName = value; }
  public Integer getNameMatchScore() { return nameMatchScore; }
  public void setNameMatchScore(Integer value) { nameMatchScore = value; }
  public String getUtr() { return utr; }
  public void setUtr(String value) { utr = value; }
  public String getFailureReason() { return failureReason; }
  public void setFailureReason(String value) { failureReason = value; }
  public int getAttempts() { return attempts; }
  public void setAttempts(int value) { attempts = value; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public void setCompletedAt(Instant value) { completedAt = value; }
}
