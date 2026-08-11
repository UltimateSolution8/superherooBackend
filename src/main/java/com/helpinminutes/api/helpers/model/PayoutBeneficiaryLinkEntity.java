package com.helpinminutes.api.helpers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payout_beneficiary_links")
public class PayoutBeneficiaryLinkEntity {
  @Id private UUID id;
  @Column(name = "payout_account_id", nullable = false) private UUID payoutAccountId;
  @Column(nullable = false, length = 40) private String provider;
  @Column(name = "external_contact_id") private String externalContactId;
  @Column(name = "external_fund_account_id") private String externalFundAccountId;
  @Column(name = "external_linked_account_id") private String externalLinkedAccountId;
  @Column(nullable = false, length = 40) private String status;
  @Column(name = "last_error_code") private String lastErrorCode;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @PrePersist void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (status == null || status.isBlank()) status = "NOT_ONBOARDED";
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }
  @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

  public UUID getId() { return id; }
  public UUID getPayoutAccountId() { return payoutAccountId; }
  public void setPayoutAccountId(UUID payoutAccountId) { this.payoutAccountId = payoutAccountId; }
  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }
  public String getExternalContactId() { return externalContactId; }
  public void setExternalContactId(String externalContactId) { this.externalContactId = externalContactId; }
  public String getExternalFundAccountId() { return externalFundAccountId; }
  public void setExternalFundAccountId(String externalFundAccountId) { this.externalFundAccountId = externalFundAccountId; }
  public String getExternalLinkedAccountId() { return externalLinkedAccountId; }
  public void setExternalLinkedAccountId(String externalLinkedAccountId) { this.externalLinkedAccountId = externalLinkedAccountId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getLastErrorCode() { return lastErrorCode; }
  public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
