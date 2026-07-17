package com.helpinminutes.api.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttemptEntity {
  @Id
  private UUID id;

  @Column(name = "payment_id", nullable = false)
  private UUID paymentId;

  @Column(name = "provider_payment_id", nullable = false, unique = true, length = 128)
  private String providerPaymentId;

  @Column(nullable = false, length = 30)
  private String status;

  @Column(length = 30)
  private String method;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(name = "amount_refunded_paise", nullable = false)
  private long amountRefundedPaise;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  @Column(name = "failure_description", length = 500)
  private String failureDescription;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  private long version;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getPaymentId() { return paymentId; }
  public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }
  public String getProviderPaymentId() { return providerPaymentId; }
  public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getMethod() { return method; }
  public void setMethod(String method) { this.method = method; }
  public long getAmountPaise() { return amountPaise; }
  public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }
  public long getAmountRefundedPaise() { return amountRefundedPaise; }
  public void setAmountRefundedPaise(long amountRefundedPaise) { this.amountRefundedPaise = amountRefundedPaise; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public String getFailureCode() { return failureCode; }
  public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
  public String getFailureDescription() { return failureDescription; }
  public void setFailureDescription(String failureDescription) { this.failureDescription = failureDescription; }
}
