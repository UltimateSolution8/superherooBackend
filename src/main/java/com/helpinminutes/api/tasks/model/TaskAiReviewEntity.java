package com.helpinminutes.api.tasks.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_ai_reviews")
public class TaskAiReviewEntity {

  @Id
  private UUID id;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(name = "prompt_version", nullable = false)
  private String promptVersion = "v1.0";

  @Column(name = "model", nullable = false)
  private String model;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "confidence", nullable = false)
  private int confidence;

  @Column(name = "risk_score", nullable = false)
  private int riskScore;

  @Column(name = "quality_score", nullable = false)
  private int qualityScore;

  // @JdbcTypeCode(JSON) is required: with a plain String field Hibernate binds
  // this as varchar, and Postgres refuses the implicit varchar->jsonb cast in
  // extended query mode. It only appeared to work because the production JDBC
  // URL forces preferQueryMode=simple, which inlines the literal. Removing that
  // parameter would have broken every write to this table.
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "reasons", columnDefinition = "jsonb")
  private String reasons;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "flags", columnDefinition = "jsonb")
  private String flags;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "raw_response", columnDefinition = "jsonb")
  private String rawResponse;

  @Column(name = "review_duration_ms", nullable = false)
  private long reviewDurationMs;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
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

  public String getPromptVersion() {
    return promptVersion;
  }

  public void setPromptVersion(String promptVersion) {
    this.promptVersion = promptVersion;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getConfidence() {
    return confidence;
  }

  public void setConfidence(int confidence) {
    this.confidence = confidence;
  }

  public int getRiskScore() {
    return riskScore;
  }

  public void setRiskScore(int riskScore) {
    this.riskScore = riskScore;
  }

  public int getQualityScore() {
    return qualityScore;
  }

  public void setQualityScore(int qualityScore) {
    this.qualityScore = qualityScore;
  }

  public String getReasons() {
    return reasons;
  }

  public void setReasons(String reasons) {
    this.reasons = reasons;
  }

  public String getFlags() {
    return flags;
  }

  public void setFlags(String flags) {
    this.flags = flags;
  }

  public String getRawResponse() {
    return rawResponse;
  }

  public void setRawResponse(String rawResponse) {
    this.rawResponse = rawResponse;
  }

  public long getReviewDurationMs() {
    return reviewDurationMs;
  }

  public void setReviewDurationMs(long reviewDurationMs) {
    this.reviewDurationMs = reviewDurationMs;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
