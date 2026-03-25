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

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (status == null) status = BookingBatchStatus.CREATED;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

