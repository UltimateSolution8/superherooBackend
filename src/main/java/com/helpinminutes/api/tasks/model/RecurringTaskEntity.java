package com.helpinminutes.api.tasks.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurring_tasks")
public class RecurringTaskEntity {
  @Id
  private UUID id;

  @Column(name = "buyer_id", nullable = false)
  private UUID buyerId;

  @Column(nullable = false)
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

  @Column(nullable = false)
  private String frequency;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

  @Column(name = "time_slot", nullable = false)
  private String timeSlot;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RecurringTaskStatus status = RecurringTaskStatus.ACTIVE;

  @Column(name = "recurrence_interval", nullable = false)
  private Integer recurrenceInterval = 1;

  @Column(name = "by_day")
  private int[] byDay;

  @Column(name = "by_month_day")
  private Integer byMonthDay;

  @Column(nullable = false)
  private String timezone = "Asia/Kolkata";

  @Version
  @Column(nullable = false)
  private Long version = 0L;

  @PrePersist
  public void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (status == null) {
      status = RecurringTaskStatus.ACTIVE;
    }
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
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

  public String getFrequency() {
    return frequency;
  }

  public void setFrequency(String frequency) {
    this.frequency = frequency;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public void setEndDate(LocalDate endDate) {
    this.endDate = endDate;
  }

  public String getTimeSlot() {
    return timeSlot;
  }

  public void setTimeSlot(String timeSlot) {
    this.timeSlot = timeSlot;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public RecurringTaskStatus getStatus() {
    return status;
  }

  public void setStatus(RecurringTaskStatus status) {
    this.status = status;
  }

  public Integer getRecurrenceInterval() {
    return recurrenceInterval;
  }

  public void setRecurrenceInterval(Integer recurrenceInterval) {
    this.recurrenceInterval = recurrenceInterval;
  }

  public int[] getByDay() {
    return byDay;
  }

  public void setByDay(int[] byDay) {
    this.byDay = byDay;
  }

  public Integer getByMonthDay() {
    return byMonthDay;
  }

  public void setByMonthDay(Integer byMonthDay) {
    this.byMonthDay = byMonthDay;
  }

  public String getTimezone() {
    return timezone;
  }

  public void setTimezone(String timezone) {
    this.timezone = timezone;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }
}
