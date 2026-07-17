package com.helpinminutes.api.mediator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "helper_mediator_links", uniqueConstraints = {
    @UniqueConstraint(name = "uk_helper_mediator_links_pair", columnNames = {"helper_id", "mediator_id"})
})
public class HelperMediatorLinkEntity {
  @Id
  private UUID id;

  @Column(name = "helper_id", nullable = false)
  private UUID helperId;

  @Column(name = "mediator_id", nullable = false)
  private UUID mediatorId;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (status == null || status.isBlank()) status = "ACTIVE";
    if (createdBy == null || createdBy.isBlank()) createdBy = "HELPER";
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getHelperId() { return helperId; }
  public void setHelperId(UUID helperId) { this.helperId = helperId; }
  public UUID getMediatorId() { return mediatorId; }
  public void setMediatorId(UUID mediatorId) { this.mediatorId = mediatorId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
