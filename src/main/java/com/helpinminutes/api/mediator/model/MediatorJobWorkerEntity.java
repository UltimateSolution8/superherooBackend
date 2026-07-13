package com.helpinminutes.api.mediator.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mediator_job_workers", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"batch_id", "helper_id"})
})
public class MediatorJobWorkerEntity {
  @Id
  private UUID id;

  @Column(name = "batch_id", nullable = false)
  private UUID batchId;

  @Column(name = "helper_id", nullable = false)
  private UUID helperId;

  @Column(name = "added_at", nullable = false)
  private Instant addedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "attendance_status")
  private MediatorAttendanceStatus attendanceStatus;

  @Column(name = "attendance_marked_at")
  private Instant attendanceMarkedAt;

  @Column(name = "task_id")
  private UUID taskId;

  @Column(name = "payment_status")
  private String paymentStatus;

  @Column(name = "payment_amount_paise")
  private Long paymentAmountPaise;

  @PrePersist
  public void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (addedAt == null) {
      addedAt = Instant.now();
    }
    if (attendanceStatus == null) {
      attendanceStatus = MediatorAttendanceStatus.PENDING;
    }
    if (paymentStatus == null) {
      paymentStatus = "PENDING";
    }
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getBatchId() {
    return batchId;
  }

  public void setBatchId(UUID batchId) {
    this.batchId = batchId;
  }

  public UUID getHelperId() {
    return helperId;
  }

  public void setHelperId(UUID helperId) {
    this.helperId = helperId;
  }

  public Instant getAddedAt() {
    return addedAt;
  }

  public void setAddedAt(Instant addedAt) {
    this.addedAt = addedAt;
  }

  public MediatorAttendanceStatus getAttendanceStatus() {
    return attendanceStatus;
  }

  public void setAttendanceStatus(MediatorAttendanceStatus attendanceStatus) {
    this.attendanceStatus = attendanceStatus;
  }

  public Instant getAttendanceMarkedAt() {
    return attendanceMarkedAt;
  }

  public void setAttendanceMarkedAt(Instant attendanceMarkedAt) {
    this.attendanceMarkedAt = attendanceMarkedAt;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public void setTaskId(UUID taskId) {
    this.taskId = taskId;
  }

  public String getPaymentStatus() {
    return paymentStatus;
  }

  public void setPaymentStatus(String paymentStatus) {
    this.paymentStatus = paymentStatus;
  }

  public Long getPaymentAmountPaise() {
    return paymentAmountPaise;
  }

  public void setPaymentAmountPaise(Long paymentAmountPaise) {
    this.paymentAmountPaise = paymentAmountPaise;
  }
}
