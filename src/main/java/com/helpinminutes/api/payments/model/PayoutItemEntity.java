package com.helpinminutes.api.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One transfer to one partner's bank account. */
@Entity
@Table(name = "payout_items")
public class PayoutItemEntity {
  @Id private UUID id;
  @Column(name = "batch_id") private UUID batchId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(name = "payout_account_id") private UUID payoutAccountId;
  @Column(name = "amount_paise", nullable = false) private long amountPaise;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24) private PayoutStatus status;
  @Column(nullable = false, length = 32) private String provider;
  @Column(name = "provider_payout_id", length = 64) private String providerPayoutId;
  /** The bank's reference for the transfer. What a partner quotes when it is missing. */
  @Column(length = 64) private String utr;
  @Column(name = "failure_code", length = 64) private String failureCode;
  @Column(name = "failure_description", length = 300) private String failureDescription;
  @Column(nullable = false) private int attempts;
  @Column(name = "idempotency_key", nullable = false, length = 120) private String idempotencyKey;
  @Column(name = "requested_at", nullable = false) private Instant requestedAt;
  @Column(name = "settled_at") private Instant settledAt;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @PrePersist void prePersist() {
    if (id == null) id = UUID.randomUUID();
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (requestedAt == null) requestedAt = now;
    if (status == null) status = PayoutStatus.PENDING;
    if (provider == null) provider = "RAZORPAYX";
    updatedAt = now;
  }

  @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

  public UUID getId() { return id; }
  public void setId(UUID value) { id = value; }
  public UUID getBatchId() { return batchId; }
  public void setBatchId(UUID value) { batchId = value; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID value) { userId = value; }
  public UUID getPayoutAccountId() { return payoutAccountId; }
  public void setPayoutAccountId(UUID value) { payoutAccountId = value; }
  public long getAmountPaise() { return amountPaise; }
  public void setAmountPaise(long value) { amountPaise = value; }
  public PayoutStatus getStatus() { return status; }
  public void setStatus(PayoutStatus value) { status = value; }
  public String getProvider() { return provider; }
  public void setProvider(String value) { provider = value; }
  public String getProviderPayoutId() { return providerPayoutId; }
  public void setProviderPayoutId(String value) { providerPayoutId = value; }
  public String getUtr() { return utr; }
  public void setUtr(String value) { utr = value; }
  public String getFailureCode() { return failureCode; }
  public void setFailureCode(String value) { failureCode = value; }
  public String getFailureDescription() { return failureDescription; }
  public void setFailureDescription(String value) { failureDescription = value; }
  public int getAttempts() { return attempts; }
  public void setAttempts(int value) { attempts = value; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public void setIdempotencyKey(String value) { idempotencyKey = value; }
  public Instant getRequestedAt() { return requestedAt; }
  public void setRequestedAt(Instant value) { requestedAt = value; }
  public Instant getSettledAt() { return settledAt; }
  public void setSettledAt(Instant value) { settledAt = value; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
