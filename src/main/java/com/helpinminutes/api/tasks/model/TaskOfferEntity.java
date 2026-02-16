package com.helpinminutes.api.tasks.model;

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
@Table(name = "task_offers")
public class TaskOfferEntity {
  @Id
  private UUID id;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(name = "helper_id", nullable = false)
  private UUID helperId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskOfferStatus status;

  @Column(name = "offered_at", nullable = false)
  private Instant offeredAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "responded_at")
  private Instant respondedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
    if (status == null) {
      status = TaskOfferStatus.OFFERED;
    }
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public void setTaskId(UUID taskId) {
    this.taskId = taskId;
  }

  public UUID getHelperId() {
    return helperId;
  }

  public void setHelperId(UUID helperId) {
    this.helperId = helperId;
  }

  public TaskOfferStatus getStatus() {
    return status;
  }

  public void setStatus(TaskOfferStatus status) {
    this.status = status;
  }

  public Instant getOfferedAt() {
    return offeredAt;
  }

  public void setOfferedAt(Instant offeredAt) {
    this.offeredAt = offeredAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getRespondedAt() {
    return respondedAt;
  }

  public void setRespondedAt(Instant respondedAt) {
    this.respondedAt = respondedAt;
  }
}
