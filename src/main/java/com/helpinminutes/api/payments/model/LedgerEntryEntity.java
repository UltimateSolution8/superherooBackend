package com.helpinminutes.api.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable line in a partner's account.
 *
 * <p>No setters for correction on purpose: rows are appended, never edited. A
 * mistake is fixed with an {@link LedgerEntryType#ADJUSTMENT} that says so, which
 * leaves the original visible. That is the property that makes the history worth
 * having when a partner disputes a payment.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {
  @Id private UUID id;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, length = 32) private LedgerEntryType entryType;
  /** Signed, in paise. Positive is owed to the partner. Money is integers (rule 4). */
  @Column(name = "amount_paise", nullable = false) private long amountPaise;
  @Column(name = "task_id") private UUID taskId;
  @Column(name = "batch_id") private UUID batchId;
  @Column(name = "payout_item_id") private UUID payoutItemId;
  @Column(length = 120) private String reference;
  @Column(length = 300) private String description;
  /**
   * The rate this entry was booked at, in basis points. Set on COMMISSION rows so
   * a historical entry is self-describing — rates change, and re-deriving one from
   * the settings table as it stands today would misreport what was actually taken.
   */
  @Column(name = "commission_bps") private Integer commissionBps;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

  @PrePersist void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID value) { userId = value; }
  public LedgerEntryType getEntryType() { return entryType; }
  public void setEntryType(LedgerEntryType value) { entryType = value; }
  public long getAmountPaise() { return amountPaise; }
  public void setAmountPaise(long value) { amountPaise = value; }
  public UUID getTaskId() { return taskId; }
  public void setTaskId(UUID value) { taskId = value; }
  public UUID getBatchId() { return batchId; }
  public void setBatchId(UUID value) { batchId = value; }
  public UUID getPayoutItemId() { return payoutItemId; }
  public void setPayoutItemId(UUID value) { payoutItemId = value; }
  public String getReference() { return reference; }
  public void setReference(String value) { reference = value; }
  public String getDescription() { return description; }
  public void setDescription(String value) { description = value; }
  public Integer getCommissionBps() { return commissionBps; }
  public void setCommissionBps(Integer value) { commissionBps = value; }
  public Instant getCreatedAt() { return createdAt; }
}
