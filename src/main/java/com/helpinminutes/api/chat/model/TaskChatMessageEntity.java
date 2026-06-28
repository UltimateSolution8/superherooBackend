package com.helpinminutes.api.chat.model;

import com.helpinminutes.api.users.model.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_chat_messages")
public class TaskChatMessageEntity {
  @Id
  private UUID id;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(name = "sender_user_id", nullable = false)
  private UUID senderUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "sender_role", nullable = false)
  private UserRole senderRole;

  @Column(nullable = false)
  private String message;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getTaskId() { return taskId; }
  public void setTaskId(UUID taskId) { this.taskId = taskId; }
  public UUID getSenderUserId() { return senderUserId; }
  public void setSenderUserId(UUID senderUserId) { this.senderUserId = senderUserId; }
  public UserRole getSenderRole() { return senderRole; }
  public void setSenderRole(UserRole senderRole) { this.senderRole = senderRole; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public Instant getCreatedAt() { return createdAt; }
}
