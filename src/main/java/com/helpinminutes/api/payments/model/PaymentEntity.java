package com.helpinminutes.api.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class PaymentEntity {
  @Id
  private UUID id;

  @Column(name = "task_id")
  private UUID taskId;

  @Column(name = "batch_id")
  private UUID batchId;

  @Column(name = "mediator_id")
  private UUID mediatorId;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_scope", nullable = false, length = 30)
  private PaymentScope paymentScope;

  @Column(name = "buyer_id", nullable = false)
  private UUID buyerId;

  @Column(name = "helper_id")
  private UUID helperId;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false, length = 20)
  private String provider;

  @Column(length = 30)
  private String method;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private PaymentStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "fulfillment_status", length = 30)
  private PaymentFulfillmentStatus fulfillmentStatus;

  @Column(nullable = false, length = 40, unique = true)
  private String receipt;

  @Column(name = "idempotency_key", nullable = false, length = 100)
  private String idempotencyKey;

  @Column(name = "provider_order_id", unique = true)
  private String providerOrderId;

  @Column(name = "provider_payment_id", unique = true)
  private String providerPaymentId;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  @Column(name = "failure_description", length = 500)
  private String failureDescription;

  @Column(name = "amount_refunded_paise", nullable = false)
  private long amountRefundedPaise;

  @Column(name = "paid_at")
  private Instant paidAt;

  @Column(name = "captured_at")
  private Instant capturedAt;

  @Column(name = "failed_at")
  private Instant failedAt;

  @Column(name = "refunded_at")
  private Instant refundedAt;

  @Column(name = "earning_released_at")
  private Instant earningReleasedAt;

  @Column(name = "refund_requested_at")
  private Instant refundRequestedAt;

  @Column(name = "refund_requested_amount_paise")
  private Long refundRequestedAmountPaise;

  @Column(name = "refund_attempts", nullable = false)
  private int refundAttempts;

  @Column(name = "refund_last_error", length = 500)
  private String refundLastError;

  @Column(name = "provider_refund_id", length = 128)
  private String providerRefundId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  private long version;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (currency == null) currency = "INR";
    if (provider == null) provider = "RAZORPAY";
    if (paymentScope == null) paymentScope = PaymentScope.TASK;
    if (status == null) status = PaymentStatus.CREATING;
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
  public UUID getTaskId() { return taskId; }
  public void setTaskId(UUID taskId) { this.taskId = taskId; }
  public UUID getBatchId() { return batchId; }
  public void setBatchId(UUID batchId) { this.batchId = batchId; }
  public UUID getMediatorId() { return mediatorId; }
  public void setMediatorId(UUID mediatorId) { this.mediatorId = mediatorId; }
  public PaymentScope getPaymentScope() { return paymentScope; }
  public void setPaymentScope(PaymentScope paymentScope) { this.paymentScope = paymentScope; }
  public UUID getBuyerId() { return buyerId; }
  public void setBuyerId(UUID buyerId) { this.buyerId = buyerId; }
  public UUID getHelperId() { return helperId; }
  public void setHelperId(UUID helperId) { this.helperId = helperId; }
  public long getAmountPaise() { return amountPaise; }
  public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }
  public String getMethod() { return method; }
  public void setMethod(String method) { this.method = method; }
  public PaymentStatus getStatus() { return status; }
  public void setStatus(PaymentStatus status) { this.status = status; }
  public PaymentFulfillmentStatus getFulfillmentStatus() { return fulfillmentStatus; }
  public void setFulfillmentStatus(PaymentFulfillmentStatus fulfillmentStatus) { this.fulfillmentStatus = fulfillmentStatus; }
  public String getReceipt() { return receipt; }
  public void setReceipt(String receipt) { this.receipt = receipt; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
  public String getProviderOrderId() { return providerOrderId; }
  public void setProviderOrderId(String providerOrderId) { this.providerOrderId = providerOrderId; }
  public String getProviderPaymentId() { return providerPaymentId; }
  public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }
  public String getFailureCode() { return failureCode; }
  public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
  public String getFailureDescription() { return failureDescription; }
  public void setFailureDescription(String failureDescription) { this.failureDescription = failureDescription; }
  public long getAmountRefundedPaise() { return amountRefundedPaise; }
  public void setAmountRefundedPaise(long amountRefundedPaise) { this.amountRefundedPaise = amountRefundedPaise; }
  public Instant getPaidAt() { return paidAt; }
  public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
  public Instant getCapturedAt() { return capturedAt; }
  public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
  public Instant getFailedAt() { return failedAt; }
  public void setFailedAt(Instant failedAt) { this.failedAt = failedAt; }
  public Instant getRefundedAt() { return refundedAt; }
  public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }
  public Instant getEarningReleasedAt() { return earningReleasedAt; }
  public void setEarningReleasedAt(Instant earningReleasedAt) { this.earningReleasedAt = earningReleasedAt; }
  public Instant getRefundRequestedAt() { return refundRequestedAt; }
  public void setRefundRequestedAt(Instant refundRequestedAt) { this.refundRequestedAt = refundRequestedAt; }
  public Long getRefundRequestedAmountPaise() { return refundRequestedAmountPaise; }
  public void setRefundRequestedAmountPaise(Long refundRequestedAmountPaise) { this.refundRequestedAmountPaise = refundRequestedAmountPaise; }
  public int getRefundAttempts() { return refundAttempts; }
  public void setRefundAttempts(int refundAttempts) { this.refundAttempts = refundAttempts; }
  public String getRefundLastError() { return refundLastError; }
  public void setRefundLastError(String refundLastError) { this.refundLastError = refundLastError; }
  public String getProviderRefundId() { return providerRefundId; }
  public void setProviderRefundId(String providerRefundId) { this.providerRefundId = providerRefundId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
