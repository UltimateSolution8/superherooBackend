package com.helpinminutes.api.support.model;

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
@Table(name = "support_messages")
public class SupportMessageEntity {
  @Id
  private UUID id;

  @Column(name = "ticket_id", nullable = false)
  private UUID ticketId;

  @Enumerated(EnumType.STRING)
  @Column(name = "author_type", nullable = false)
  private SupportAuthorType authorType;

  @Column(name = "author_user_id")
  private UUID authorUserId;

  @Column(nullable = false)
  private String message;

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

  public UUID getTicketId() {
    return ticketId;
  }

  public void setTicketId(UUID ticketId) {
    this.ticketId = ticketId;
  }

  public SupportAuthorType getAuthorType() {
    return authorType;
  }

  public void setAuthorType(SupportAuthorType authorType) {
    this.authorType = authorType;
  }

  public UUID getAuthorUserId() {
    return authorUserId;
  }

  public void setAuthorUserId(UUID authorUserId) {
    this.authorUserId = authorUserId;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

