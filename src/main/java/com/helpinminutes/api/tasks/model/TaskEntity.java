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
@Table(name = "tasks")
public class TaskEntity {
  @Id
  private UUID id;

  @Column(name = "buyer_id", nullable = false)
  private UUID buyerId;


  @Column(name = "title")
  private String title;

  @Column(nullable = false)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskUrgency urgency;

  @Column(name = "time_minutes", nullable = false)
  private Integer timeMinutes;

  @Column(name = "budget_paise", nullable = false)
  private Long budgetPaise;

  @Column(nullable = false)
  private double lat;

  @Column(nullable = false)
  private double lng;

  @Column(name = "address_text")
  private String addressText;

  @Column(name = "arrival_selfie_url")
  private String arrivalSelfieUrl;

  @Column(name = "arrival_selfie_lat")
  private Double arrivalSelfieLat;

  @Column(name = "arrival_selfie_lng")
  private Double arrivalSelfieLng;

  @Column(name = "arrival_selfie_address")
  private String arrivalSelfieAddress;

  @Column(name = "arrival_selfie_captured_at")
  private Instant arrivalSelfieCapturedAt;

  @Column(name = "completion_selfie_url")
  private String completionSelfieUrl;

  @Column(name = "completion_selfie_lat")
  private Double completionSelfieLat;

  @Column(name = "completion_selfie_lng")
  private Double completionSelfieLng;

  @Column(name = "completion_selfie_address")
  private String completionSelfieAddress;

  @Column(name = "completion_selfie_captured_at")
  private Instant completionSelfieCapturedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskStatus status;

  @Column(name = "assigned_helper_id")
  private UUID assignedHelperId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (status == null) {
      status = TaskStatus.SEARCHING;
    }
    if (urgency == null) {
      urgency = TaskUrgency.NORMAL;
    }
    if (timeMinutes == null || timeMinutes <= 0) {
      timeMinutes = 30;
    }
    if (budgetPaise == null || budgetPaise < 0) {
      budgetPaise = 0L;
    }
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public void setBuyerId(UUID buyerId) {
    this.buyerId = buyerId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public TaskUrgency getUrgency() {
    return urgency;
  }

  public void setUrgency(TaskUrgency urgency) {
    this.urgency = urgency;
  }

  public Integer getTimeMinutes() {
    return timeMinutes;
  }

  public void setTimeMinutes(Integer timeMinutes) {
    this.timeMinutes = timeMinutes;
  }

  public Long getBudgetPaise() {
    return budgetPaise;
  }

  public void setBudgetPaise(Long budgetPaise) {
    this.budgetPaise = budgetPaise;
  }

  public double getLat() {
    return lat;
  }

  public void setLat(double lat) {
    this.lat = lat;
  }

  public double getLng() {
    return lng;
  }

  public void setLng(double lng) {
    this.lng = lng;
  }

  public String getAddressText() {
    return addressText;
  }

  public void setAddressText(String addressText) {
    this.addressText = addressText;
  }

  public String getArrivalSelfieUrl() {
    return arrivalSelfieUrl;
  }

  public void setArrivalSelfieUrl(String arrivalSelfieUrl) {
    this.arrivalSelfieUrl = arrivalSelfieUrl;
  }

  public Double getArrivalSelfieLat() {
    return arrivalSelfieLat;
  }

  public void setArrivalSelfieLat(Double arrivalSelfieLat) {
    this.arrivalSelfieLat = arrivalSelfieLat;
  }

  public Double getArrivalSelfieLng() {
    return arrivalSelfieLng;
  }

  public void setArrivalSelfieLng(Double arrivalSelfieLng) {
    this.arrivalSelfieLng = arrivalSelfieLng;
  }

  public String getArrivalSelfieAddress() {
    return arrivalSelfieAddress;
  }

  public void setArrivalSelfieAddress(String arrivalSelfieAddress) {
    this.arrivalSelfieAddress = arrivalSelfieAddress;
  }

  public Instant getArrivalSelfieCapturedAt() {
    return arrivalSelfieCapturedAt;
  }

  public void setArrivalSelfieCapturedAt(Instant arrivalSelfieCapturedAt) {
    this.arrivalSelfieCapturedAt = arrivalSelfieCapturedAt;
  }

  public String getCompletionSelfieUrl() {
    return completionSelfieUrl;
  }

  public void setCompletionSelfieUrl(String completionSelfieUrl) {
    this.completionSelfieUrl = completionSelfieUrl;
  }

  public Double getCompletionSelfieLat() {
    return completionSelfieLat;
  }

  public void setCompletionSelfieLat(Double completionSelfieLat) {
    this.completionSelfieLat = completionSelfieLat;
  }

  public Double getCompletionSelfieLng() {
    return completionSelfieLng;
  }

  public void setCompletionSelfieLng(Double completionSelfieLng) {
    this.completionSelfieLng = completionSelfieLng;
  }

  public String getCompletionSelfieAddress() {
    return completionSelfieAddress;
  }

  public void setCompletionSelfieAddress(String completionSelfieAddress) {
    this.completionSelfieAddress = completionSelfieAddress;
  }

  public Instant getCompletionSelfieCapturedAt() {
    return completionSelfieCapturedAt;
  }

  public void setCompletionSelfieCapturedAt(Instant completionSelfieCapturedAt) {
    this.completionSelfieCapturedAt = completionSelfieCapturedAt;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public void setStatus(TaskStatus status) {
    this.status = status;
  }

  public UUID getAssignedHelperId() {
    return assignedHelperId;
  }

  public void setAssignedHelperId(UUID assignedHelperId) {
    this.assignedHelperId = assignedHelperId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
