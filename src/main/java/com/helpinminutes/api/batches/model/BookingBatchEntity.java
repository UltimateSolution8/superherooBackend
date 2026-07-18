package com.helpinminutes.api.batches.model;

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
import com.helpinminutes.api.payments.model.PaymentCollectionMode;

@Entity
@Table(name = "booking_batches")
public class BookingBatchEntity {
  @Id
  private UUID id;

  @Column(name = "created_by_user_id", nullable = false)
  private UUID createdByUserId;

  @Column(nullable = false)
  private String title;

  @Column
  private String notes;

  @Column(name = "scheduled_window_start")
  private Instant scheduledWindowStart;

  @Column(name = "scheduled_window_end")
  private Instant scheduledWindowEnd;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private BookingBatchStatus status;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "source_recurring_task_id")
  private UUID sourceRecurringTaskId;

  @Column(name = "mediator_id")
  private UUID mediatorId;

  @Column(name = "requested_helper_count")
  private Integer requestedHelperCount;

  @Column(name = "mediator_accepted_at")
  private Instant mediatorAcceptedAt;

  @Column(name = "scheduled_dispatch_at")
  private Instant scheduledDispatchAt;

  @Column(name = "mediator_notes")
  private String mediatorNotes;

  @Column(name = "mediator_commission_paise")
  private Long mediatorCommissionPaise;

  @Column(name = "task_template_json")
  private String taskTemplateJson;

  @Column(name = "audit_notes")
  private String auditNotes;

  @Column(name = "audited_by_user_id")
  private UUID auditedByUserId;

  @Column(name = "audited_at")
  private Instant auditedAt;

  @Column(name = "batch_start_otp")
  private String batchStartOtp;

  @Column(name = "batch_completion_otp")
  private String batchCompletionOtp;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_mode", length = 30)
  private BatchPaymentMode paymentMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_collection_mode", nullable = false, length = 30)
  private PaymentCollectionMode paymentCollectionMode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (status == null) status = BookingBatchStatus.CREATED;
    if (paymentCollectionMode == null) paymentCollectionMode = PaymentCollectionMode.PAY_AFTER_SERVICE;
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getCreatedByUserId() {
    return createdByUserId;
  }

  public void setCreatedByUserId(UUID createdByUserId) {
    this.createdByUserId = createdByUserId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public Instant getScheduledWindowStart() {
    return scheduledWindowStart;
  }

  public void setScheduledWindowStart(Instant scheduledWindowStart) {
    this.scheduledWindowStart = scheduledWindowStart;
  }

  public Instant getScheduledWindowEnd() {
    return scheduledWindowEnd;
  }

  public void setScheduledWindowEnd(Instant scheduledWindowEnd) {
    this.scheduledWindowEnd = scheduledWindowEnd;
  }

  public BookingBatchStatus getStatus() {
    return status;
  }

  public void setStatus(BookingBatchStatus status) {
    this.status = status;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public UUID getSourceRecurringTaskId() {
    return sourceRecurringTaskId;
  }

  public void setSourceRecurringTaskId(UUID sourceRecurringTaskId) {
    this.sourceRecurringTaskId = sourceRecurringTaskId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getMediatorId() {
    return mediatorId;
  }

  public void setMediatorId(UUID mediatorId) {
    this.mediatorId = mediatorId;
  }

  public Integer getRequestedHelperCount() {
    return requestedHelperCount;
  }

  public void setRequestedHelperCount(Integer requestedHelperCount) {
    this.requestedHelperCount = requestedHelperCount;
  }

  public Instant getMediatorAcceptedAt() {
    return mediatorAcceptedAt;
  }

  public void setMediatorAcceptedAt(Instant mediatorAcceptedAt) {
    this.mediatorAcceptedAt = mediatorAcceptedAt;
  }

  public Instant getScheduledDispatchAt() {
    return scheduledDispatchAt;
  }

  public void setScheduledDispatchAt(Instant scheduledDispatchAt) {
    this.scheduledDispatchAt = scheduledDispatchAt;
  }

  public String getMediatorNotes() {
    return mediatorNotes;
  }

  public void setMediatorNotes(String mediatorNotes) {
    this.mediatorNotes = mediatorNotes;
  }

  public Long getMediatorCommissionPaise() {
    return mediatorCommissionPaise;
  }

  public void setMediatorCommissionPaise(Long mediatorCommissionPaise) {
    this.mediatorCommissionPaise = mediatorCommissionPaise;
  }

  public String getTaskTemplateJson() {
    return taskTemplateJson;
  }

  public void setTaskTemplateJson(String taskTemplateJson) {
    this.taskTemplateJson = taskTemplateJson;
  }

  public String getAuditNotes() {
    return auditNotes;
  }

  public void setAuditNotes(String auditNotes) {
    this.auditNotes = auditNotes;
  }

  public UUID getAuditedByUserId() {
    return auditedByUserId;
  }

  public void setAuditedByUserId(UUID auditedByUserId) {
    this.auditedByUserId = auditedByUserId;
  }

  public Instant getAuditedAt() {
    return auditedAt;
  }

  public void setAuditedAt(Instant auditedAt) {
    this.auditedAt = auditedAt;
  }

  public String getBatchStartOtp() {
    return batchStartOtp;
  }

  public void setBatchStartOtp(String batchStartOtp) {
    this.batchStartOtp = batchStartOtp;
  }

  public String getBatchCompletionOtp() {
    return batchCompletionOtp;
  }

  public void setBatchCompletionOtp(String batchCompletionOtp) {
    this.batchCompletionOtp = batchCompletionOtp;
  }

  public BatchPaymentMode getPaymentMode() {
    return paymentMode;
  }

  public void setPaymentMode(BatchPaymentMode paymentMode) {
    this.paymentMode = paymentMode;
  }

  public PaymentCollectionMode getPaymentCollectionMode() {
    return paymentCollectionMode;
  }

  public void setPaymentCollectionMode(PaymentCollectionMode paymentCollectionMode) {
    this.paymentCollectionMode = paymentCollectionMode;
  }
}
