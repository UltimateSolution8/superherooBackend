package com.helpinminutes.api.tasks.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_audit_logs")
public class TaskAuditLogEntity {

  @Id
  private UUID id;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(name = "action", nullable = false)
  private String action;

  @Column(name = "performed_by", nullable = false)
  private String performedBy;

  @Column(name = "timestamp", nullable = false)
  private Instant timestamp;

  @Column(name = "remarks")
  private String remarks;

  @PrePersist
  public void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (timestamp == null) {
      timestamp = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public void setTaskId(UUID taskId) {
    this.taskId = taskId;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getPerformedBy() {
    return performedBy;
  }

  public void setPerformedBy(String performedBy) {
    this.performedBy = performedBy;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public String getRemarks() {
    return remarks;
  }

  public void setRemarks(String remarks) {
    this.remarks = remarks;
  }
}
