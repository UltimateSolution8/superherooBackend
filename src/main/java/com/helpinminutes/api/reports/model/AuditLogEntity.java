package com.helpinminutes.api.reports.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(name = "actor_email")
  private String actorEmail;

  @Column(name = "actor_role", nullable = false)
  private String actorRole;

  @Column(name = "action_type", nullable = false)
  private String actionType;

  @Column(name = "target_resource")
  private String targetResource;

  @Column(name = "target_id")
  private String targetId;

  @Column(name = "details", columnDefinition = "TEXT")
  private String details;

  @Column(name = "ip_address")
  private String ipAddress;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  public AuditLogEntity() {}

  public AuditLogEntity(
      UUID actorId,
      String actorEmail,
      String actorRole,
      String actionType,
      String targetResource,
      String targetId,
      String details,
      String ipAddress) {
    this.actorId = actorId;
    this.actorEmail = actorEmail;
    this.actorRole = actorRole;
    this.actionType = actionType;
    this.targetResource = targetResource;
    this.targetId = targetId;
    this.details = details;
    this.ipAddress = ipAddress;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getActorId() {
    return actorId;
  }

  public String getActorEmail() {
    return actorEmail;
  }

  public String getActorRole() {
    return actorRole;
  }

  public String getActionType() {
    return actionType;
  }

  public String getTargetResource() {
    return targetResource;
  }

  public String getTargetId() {
    return targetId;
  }

  public String getDetails() {
    return details;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
