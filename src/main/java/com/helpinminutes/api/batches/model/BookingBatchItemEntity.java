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
@Table(name = "booking_batch_items")
public class BookingBatchItemEntity {
  @Id
  private UUID id;

  @Column(name = "batch_id", nullable = false)
  private UUID batchId;

  @Column(name = "task_id")
  private UUID taskId;

  @Column(name = "line_no", nullable = false)
  private Integer lineNo;

  @Column(name = "external_ref")
  private String externalRef;

  @Column(nullable = false)
  private Integer priority;

  @Enumerated(EnumType.STRING)
  @Column(name = "line_status", nullable = false)
  private BookingBatchLineStatus lineStatus;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (priority == null || priority < 1) priority = 3;
    if (lineStatus == null) lineStatus = BookingBatchLineStatus.CREATED;
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

  public UUID getBatchId() {
    return batchId;
  }

  public void setBatchId(UUID batchId) {
    this.batchId = batchId;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public void setTaskId(UUID taskId) {
    this.taskId = taskId;
  }

  public Integer getLineNo() {
    return lineNo;
  }

  public void setLineNo(Integer lineNo) {
    this.lineNo = lineNo;
  }

  public String getExternalRef() {
    return externalRef;
  }

  public void setExternalRef(String externalRef) {
    this.externalRef = externalRef;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public BookingBatchLineStatus getLineStatus() {
    return lineStatus;
  }

  public void setLineStatus(BookingBatchLineStatus lineStatus) {
    this.lineStatus = lineStatus;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

