package com.helpinminutes.api.helpers.model;

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
@Table(name = "public_partner_kyc_submissions")
public class PublicPartnerKycSubmissionEntity {
  public static final String SOURCE_WEB_PUBLIC_KYC = "WEB_PUBLIC_KYC";

  @Id
  private UUID id;

  @Column(nullable = false, length = 40)
  private String source;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private HelperKycStatus status;

  @Column(name = "full_name", nullable = false, length = 120)
  private String fullName;

  @Column(nullable = false, length = 20)
  private String phone;

  @Column(nullable = false, length = 254)
  private String email;

  @Column(name = "doc_type", nullable = false, length = 40)
  private String docType;

  @Column(name = "id_number", nullable = false, length = 64)
  private String idNumber;

  @Column(name = "doc_front_url", nullable = false)
  private String docFrontUrl;

  @Column(name = "doc_back_url")
  private String docBackUrl;

  @Column(name = "selfie_url", nullable = false)
  private String selfieUrl;

  @Column(name = "account_holder_name")
  private String accountHolderName;

  @Column(name = "bank_name")
  private String bankName;

  @Column(name = "bank_account_last4", length = 4)
  private String bankAccountLast4;

  @Column(name = "ifsc_code", length = 20)
  private String ifscCode;

  @Column(name = "upi_id_masked")
  private String upiIdMasked;

  @Column(name = "rejection_reason")
  private String rejectionReason;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "reviewed_by_admin_id")
  private UUID reviewedByAdminId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (source == null || source.isBlank()) source = SOURCE_WEB_PUBLIC_KYC;
    if (status == null) status = HelperKycStatus.PENDING;
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
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public HelperKycStatus getStatus() { return status; }
  public void setStatus(HelperKycStatus status) { this.status = status; }
  public String getFullName() { return fullName; }
  public void setFullName(String fullName) { this.fullName = fullName; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getDocType() { return docType; }
  public void setDocType(String docType) { this.docType = docType; }
  public String getIdNumber() { return idNumber; }
  public void setIdNumber(String idNumber) { this.idNumber = idNumber; }
  public String getDocFrontUrl() { return docFrontUrl; }
  public void setDocFrontUrl(String docFrontUrl) { this.docFrontUrl = docFrontUrl; }
  public String getDocBackUrl() { return docBackUrl; }
  public void setDocBackUrl(String docBackUrl) { this.docBackUrl = docBackUrl; }
  public String getSelfieUrl() { return selfieUrl; }
  public void setSelfieUrl(String selfieUrl) { this.selfieUrl = selfieUrl; }
  public String getAccountHolderName() { return accountHolderName; }
  public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
  public String getBankName() { return bankName; }
  public void setBankName(String bankName) { this.bankName = bankName; }
  public String getBankAccountLast4() { return bankAccountLast4; }
  public void setBankAccountLast4(String bankAccountLast4) { this.bankAccountLast4 = bankAccountLast4; }
  public String getIfscCode() { return ifscCode; }
  public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
  public String getUpiIdMasked() { return upiIdMasked; }
  public void setUpiIdMasked(String upiIdMasked) { this.upiIdMasked = upiIdMasked; }
  public String getRejectionReason() { return rejectionReason; }
  public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
  public Instant getReviewedAt() { return reviewedAt; }
  public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
  public UUID getReviewedByAdminId() { return reviewedByAdminId; }
  public void setReviewedByAdminId(UUID reviewedByAdminId) { this.reviewedByAdminId = reviewedByAdminId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
