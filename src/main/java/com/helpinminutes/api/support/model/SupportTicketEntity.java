package com.helpinminutes.api.support.model;

import com.helpinminutes.api.users.model.UserRole;
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
@Table(name = "support_tickets")
public class SupportTicketEntity {
  @Id
  private UUID id;

  @Column(name = "created_by_user_id", nullable = false)
  private UUID createdByUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private UserRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SupportTicketCategory category;

  @Column
  private String subject;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SupportTicketStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SupportTicketPriority priority;

  @Column(name = "related_task_id")
  private UUID relatedTaskId;

  @Column(name = "assignee_user_id")
  private UUID assigneeUserId;

  @Column(name = "last_message_at", nullable = false)
  private Instant lastMessageAt;

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
      status = SupportTicketStatus.OPEN;
    }
    if (priority == null) {
      priority = SupportTicketPriority.NORMAL;
    }
    if (lastMessageAt == null) {
      lastMessageAt = now;
    }
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getCreatedByUserId() {
    return createdByUserId;
  }

  public void setCreatedByUserId(UUID createdByUserId) {
    this.createdByUserId = createdByUserId;
  }

  public UserRole getRole() {
    return role;
  }

  public void setRole(UserRole role) {
    this.role = role;
  }

  public SupportTicketCategory getCategory() {
    return category;
  }

  public void setCategory(SupportTicketCategory category) {
    this.category = category;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public SupportTicketStatus getStatus() {
    return status;
  }

  public void setStatus(SupportTicketStatus status) {
    this.status = status;
  }

  public SupportTicketPriority getPriority() {
    return priority;
  }

  public void setPriority(SupportTicketPriority priority) {
    this.priority = priority;
  }

  public UUID getRelatedTaskId() {
    return relatedTaskId;
  }

  public void setRelatedTaskId(UUID relatedTaskId) {
    this.relatedTaskId = relatedTaskId;
  }

  public UUID getAssigneeUserId() {
    return assigneeUserId;
  }

  public void setAssigneeUserId(UUID assigneeUserId) {
    this.assigneeUserId = assigneeUserId;
  }

  public Instant getLastMessageAt() {
    return lastMessageAt;
  }

  public void setLastMessageAt(Instant lastMessageAt) {
    this.lastMessageAt = lastMessageAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

