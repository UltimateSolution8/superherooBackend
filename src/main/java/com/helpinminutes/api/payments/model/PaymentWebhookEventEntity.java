package com.helpinminutes.api.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEventEntity {
  @Id
  private UUID id;

  @Column(name = "provider_event_id", nullable = false, unique = true)
  private String providerEventId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "payload_sha256", nullable = false, length = 64)
  private String payloadSha256;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "error_message", length = 500)
  private String errorMessage;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Column(name = "processed_at")
  private Instant processedAt;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (receivedAt == null) receivedAt = Instant.now();
    if (status == null) status = "RECEIVED";
  }

  public UUID getId() { return id; }
  public String getProviderEventId() { return providerEventId; }
  public void setProviderEventId(String providerEventId) { this.providerEventId = providerEventId; }
  public String getEventType() { return eventType; }
  public void setEventType(String eventType) { this.eventType = eventType; }
  public String getPayloadSha256() { return payloadSha256; }
  public void setPayloadSha256(String payloadSha256) { this.payloadSha256 = payloadSha256; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
  public Instant getProcessedAt() { return processedAt; }
  public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
