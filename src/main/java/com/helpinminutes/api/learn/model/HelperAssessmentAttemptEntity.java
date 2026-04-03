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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "helper_assessment_attempts")
public class HelperAssessmentAttemptEntity {
  @Id
  private UUID id;

  @Column(name = "assessment_id", nullable = false)
  private UUID assessmentId;

  @Column(name = "helper_id", nullable = false)
  private UUID helperId;

  @Column(name = "attempt_no", nullable = false)
  private int attemptNo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private HelperAssessmentAttemptStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "answers_json", columnDefinition = "jsonb")
  private String answersJson;

  @Column(name = "score_percentage")
  private Integer scorePercentage;

  @Column(name = "correct_count")
  private Integer correctCount;

  @Column(name = "total_count")
  private Integer totalCount;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "submitted_at")
  private Instant submittedAt;

  @Column(name = "duration_seconds")
  private Integer durationSeconds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata_json", columnDefinition = "jsonb")
  private String metadataJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (status == null) status = HelperAssessmentAttemptStatus.IN_PROGRESS;
    Instant now = Instant.now();
    if (startedAt == null) startedAt = now;
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

  public UUID getAssessmentId() {
    return assessmentId;
  }

  public void setAssessmentId(UUID assessmentId) {
    this.assessmentId = assessmentId;
  }

  public UUID getHelperId() {
    return helperId;
  }

  public void setHelperId(UUID helperId) {
    this.helperId = helperId;
  }

  public int getAttemptNo() {
    return attemptNo;
  }

  public void setAttemptNo(int attemptNo) {
    this.attemptNo = attemptNo;
  }

  public HelperAssessmentAttemptStatus getStatus() {
    return status;
  }

  public void setStatus(HelperAssessmentAttemptStatus status) {
    this.status = status;
  }

  public String getAnswersJson() {
    return answersJson;
  }

  public void setAnswersJson(String answersJson) {
    this.answersJson = answersJson;
  }

  public Integer getScorePercentage() {
    return scorePercentage;
  }

  public void setScorePercentage(Integer scorePercentage) {
    this.scorePercentage = scorePercentage;
  }

  public Integer getCorrectCount() {
    return correctCount;
  }

  public void setCorrectCount(Integer correctCount) {
    this.correctCount = correctCount;
  }

  public Integer getTotalCount() {
    return totalCount;
  }

  public void setTotalCount(Integer totalCount) {
    this.totalCount = totalCount;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(Instant submittedAt) {
    this.submittedAt = submittedAt;
  }

  public Integer getDurationSeconds() {
    return durationSeconds;
  }

  public void setDurationSeconds(Integer durationSeconds) {
    this.durationSeconds = durationSeconds;
  }

  public String getMetadataJson() {
    return metadataJson;
  }

  public void setMetadataJson(String metadataJson) {
    this.metadataJson = metadataJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
