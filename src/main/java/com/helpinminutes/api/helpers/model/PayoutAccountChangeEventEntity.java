package com.helpinminutes.api.helpers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payout_account_change_events")
public class PayoutAccountChangeEventEntity {
  @Id private UUID id;
  @Column(name = "beneficiary_user_id", nullable = false) private UUID beneficiaryUserId;
  @Column(name = "actor_user_id", nullable = false) private UUID actorUserId;
  @Column(name = "actor_role", nullable = false, length = 32) private String actorRole;
  @Column(name = "action_type", nullable = false, length = 40) private String actionType;
  @Column(name = "change_source", nullable = false, length = 32) private String changeSource;
  @Column(name = "previous_account_id") private UUID previousAccountId;
  @Column(name = "new_account_id", nullable = false) private UUID newAccountId;
  @Column(name = "previous_last4", length = 4) private String previousLast4;
  @Column(name = "new_last4", nullable = false, length = 4) private String newLast4;
  @Column(name = "ip_address", length = 64) private String ipAddress;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

  @PrePersist void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getBeneficiaryUserId() { return beneficiaryUserId; }
  public void setBeneficiaryUserId(UUID value) { beneficiaryUserId = value; }
  public UUID getActorUserId() { return actorUserId; }
  public void setActorUserId(UUID value) { actorUserId = value; }
  public String getActorRole() { return actorRole; }
  public void setActorRole(String value) { actorRole = value; }
  public String getActionType() { return actionType; }
  public void setActionType(String value) { actionType = value; }
  public String getChangeSource() { return changeSource; }
  public void setChangeSource(String value) { changeSource = value; }
  public UUID getPreviousAccountId() { return previousAccountId; }
  public void setPreviousAccountId(UUID value) { previousAccountId = value; }
  public UUID getNewAccountId() { return newAccountId; }
  public void setNewAccountId(UUID value) { newAccountId = value; }
  public String getPreviousLast4() { return previousLast4; }
  public void setPreviousLast4(String value) { previousLast4 = value; }
  public String getNewLast4() { return newLast4; }
  public void setNewLast4(String value) { newLast4 = value; }
  public String getIpAddress() { return ipAddress; }
  public void setIpAddress(String value) { ipAddress = value; }
  public Instant getCreatedAt() { return createdAt; }
}

