package com.helpinminutes.api.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A payout webhook we have already handled.
 *
 * Providers redeliver on any doubt about our response, and a replayed
 * "payout.reversed" that reverses a ledger entry twice hands the partner money
 * they were never owed. The primary key is the provider's event id, so the second
 * delivery fails to insert and is dropped.
 */
@Entity
@Table(name = "payout_webhook_events")
public class PayoutWebhookEventEntity {
  @Id
  @Column(name = "event_id", nullable = false, length = 120) private String eventId;
  @Column(name = "event_type", nullable = false, length = 64) private String eventType;
  @Column(name = "received_at", nullable = false) private Instant receivedAt;

  @PrePersist void prePersist() {
    if (receivedAt == null) receivedAt = Instant.now();
  }

  public String getEventId() { return eventId; }
  public void setEventId(String value) { eventId = value; }
  public String getEventType() { return eventType; }
  public void setEventType(String value) { eventType = value; }
  public Instant getReceivedAt() { return receivedAt; }
}
