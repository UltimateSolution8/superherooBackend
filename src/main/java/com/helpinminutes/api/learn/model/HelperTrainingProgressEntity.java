package com.helpinminutes.api.learn.model;

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
@Table(name = "helper_training_progress")
public class HelperTrainingProgressEntity {
  @Id
  private UUID id;

  @Column(name = "material_id", nullable = false)
  private UUID materialId;

  @Column(name = "helper_id", nullable = false)
  private UUID helperId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private HelperTrainingProgressStatus status;

  @Column(name = "progress_percent", nullable = false)
  private int progressPercent;

  @Column(name = "viewed_seconds", nullable = false)
  private int viewedSeconds;

  @Column(name = "last_accessed_at")
  private Instant lastAccessedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (status == null) status = HelperTrainingProgressStatus.NOT_STARTED;
    if (progressPercent < 0) progressPercent = 0;
    if (viewedSeconds < 0) viewedSeconds = 0;
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

  public UUID getMaterialId() {
    return materialId;
  }

  public void setMaterialId(UUID materialId) {
    this.materialId = materialId;
  }

  public UUID getHelperId() {
    return helperId;
  }

  public void setHelperId(UUID helperId) {
    this.helperId = helperId;
  }

  public HelperTrainingProgressStatus getStatus() {
    return status;
  }

  public void setStatus(HelperTrainingProgressStatus status) {
    this.status = status;
  }

  public int getProgressPercent() {
    return progressPercent;
  }

  public void setProgressPercent(int progressPercent) {
    this.progressPercent = progressPercent;
  }

  public int getViewedSeconds() {
    return viewedSeconds;
  }

  public void setViewedSeconds(int viewedSeconds) {
    this.viewedSeconds = viewedSeconds;
  }

  public Instant getLastAccessedAt() {
    return lastAccessedAt;
  }

  public void setLastAccessedAt(Instant lastAccessedAt) {
    this.lastAccessedAt = lastAccessedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
