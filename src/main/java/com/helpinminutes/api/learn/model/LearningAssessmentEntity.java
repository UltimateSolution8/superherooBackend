package com.helpinminutes.api.learn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "learning_assessments")
public class LearningAssessmentEntity {
  @Id
  private UUID id;

  @Column(nullable = false, length = 180)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(columnDefinition = "TEXT")
  private String instructions;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "time_limit_minutes")
  private Integer timeLimitMinutes;

  @Column(name = "pass_percentage", nullable = false)
  private int passPercentage;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "question_schema", nullable = false, columnDefinition = "jsonb")
  private String questionSchema;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (maxAttempts <= 0) maxAttempts = 1;
    if (passPercentage < 0 || passPercentage > 100) passPercentage = 60;
    if (questionSchema == null || questionSchema.isBlank()) questionSchema = "[]";
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

  public String getInstructions() {
    return instructions;
  }

  public void setInstructions(String instructions) {
    this.instructions = instructions;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Integer getTimeLimitMinutes() {
    return timeLimitMinutes;
  }

  public void setTimeLimitMinutes(Integer timeLimitMinutes) {
    this.timeLimitMinutes = timeLimitMinutes;
  }

  public int getPassPercentage() {
    return passPercentage;
  }

  public void setPassPercentage(int passPercentage) {
    this.passPercentage = passPercentage;
  }

  public String getQuestionSchema() {
    return questionSchema;
  }

  public void setQuestionSchema(String questionSchema) {
    this.questionSchema = questionSchema;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(UUID createdBy) {
    this.createdBy = createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
