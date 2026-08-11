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
@Table(name = "helper_payout_accounts")
public class HelperPayoutAccountEntity {
  /** Canonical destination; external providers are represented by beneficiary links. */
  public static final String DEFAULT_PROVIDER = "INTERNAL";

  @Id
  private UUID id;

  @Column(name = "helper_id", nullable = false)
  private UUID helperId;

  @Column(nullable = false, length = 32)
  private String provider;

  @Column(name = "provider_linked_account_id")
  private String providerLinkedAccountId;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "account_holder_name")
  private String accountHolderName;

  @Column(name = "bank_name")
  private String bankName;

  @Column(name = "bank_account_last4", length = 4)
  private String bankAccountLast4;

  @Column(name = "account_number_ciphertext", columnDefinition = "TEXT")
  private String accountNumberCiphertext;

  @Column(name = "account_number_key_id", length = 32)
  private String accountNumberKeyId;

  @Column(name = "ifsc_code", length = 20)
  private String ifscCode;

  @Column(name = "upi_id_masked")
  private String upiIdMasked;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(name = "ifsc_verified_at")
  private Instant ifscVerifiedAt;

  @Column(name = "verification_status", nullable = false, length = 40)
  private String verificationStatus;

  @Column(name = "is_current", nullable = false)
  private boolean current;

  @Column(name = "superseded_at")
  private Instant supersededAt;

  @Column(name = "supersedes_account_id")
  private UUID supersedesAccountId;

  @Column(name = "change_source", nullable = false, length = 32)
  private String changeSource;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (provider == null || provider.isBlank()) provider = DEFAULT_PROVIDER;
    if (status == null || status.isBlank()) status = "PENDING_KYC";
    if (verificationStatus == null || verificationStatus.isBlank()) verificationStatus = "DETAILS_INCOMPLETE";
    if (changeSource == null || changeSource.isBlank()) changeSource = "LEGACY";
    current = true;
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getHelperId() { return helperId; }
  public void setHelperId(UUID helperId) { this.helperId = helperId; }
  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }
  public String getProviderLinkedAccountId() { return providerLinkedAccountId; }
  public void setProviderLinkedAccountId(String providerLinkedAccountId) { this.providerLinkedAccountId = providerLinkedAccountId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getAccountHolderName() { return accountHolderName; }
  public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
  public String getBankName() { return bankName; }
  public void setBankName(String bankName) { this.bankName = bankName; }
  public String getBankAccountLast4() { return bankAccountLast4; }
  public void setBankAccountLast4(String bankAccountLast4) { this.bankAccountLast4 = bankAccountLast4; }
  public String getAccountNumberCiphertext() { return accountNumberCiphertext; }
  public void setAccountNumberCiphertext(String accountNumberCiphertext) { this.accountNumberCiphertext = accountNumberCiphertext; }
  public String getAccountNumberKeyId() { return accountNumberKeyId; }
  public void setAccountNumberKeyId(String accountNumberKeyId) { this.accountNumberKeyId = accountNumberKeyId; }
  public String getIfscCode() { return ifscCode; }
  public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
  public String getUpiIdMasked() { return upiIdMasked; }
  public void setUpiIdMasked(String upiIdMasked) { this.upiIdMasked = upiIdMasked; }
  public Instant getVerifiedAt() { return verifiedAt; }
  public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
  public Instant getIfscVerifiedAt() { return ifscVerifiedAt; }
  public void setIfscVerifiedAt(Instant ifscVerifiedAt) { this.ifscVerifiedAt = ifscVerifiedAt; }
  public String getVerificationStatus() { return verificationStatus; }
  public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
  public boolean isCurrent() { return current; }
  public void setCurrent(boolean current) { this.current = current; }
  public Instant getSupersededAt() { return supersededAt; }
  public void setSupersededAt(Instant supersededAt) { this.supersededAt = supersededAt; }
  public UUID getSupersedesAccountId() { return supersedesAccountId; }
  public void setSupersedesAccountId(UUID value) { supersedesAccountId = value; }
  public String getChangeSource() { return changeSource; }
  public void setChangeSource(String value) { changeSource = value; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
