package com.helpinminutes.api.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One commission rate, in force for a window of time.
 *
 * <p>Append-only and effective-dated, for the same reason the ledger is: changing
 * a rate must not retroactively change what past tasks were booked at. A change
 * closes the live row ({@code effectiveTo = now}) and inserts a new one.
 */
@Entity
@Table(name = "commission_settings")
public class CommissionSettingEntity {

  public static final String SCOPE_GLOBAL = "GLOBAL";
  public static final String SCOPE_CATEGORY = "CATEGORY";
  public static final String SCOPE_HELPER = "HELPER";

  @Id private UUID id;

  @Column(nullable = false, length = 16) private String scope;
  /** Null for GLOBAL; the category name or the helper's user id otherwise. */
  @Column(name = "scope_ref", length = 128) private String scopeRef;
  @Column(name = "commission_bps", nullable = false) private int commissionBps;
  @Column(name = "effective_from", nullable = false) private Instant effectiveFrom;
  /** Null means still in force. */
  @Column(name = "effective_to") private Instant effectiveTo;
  @Column(name = "created_by") private UUID createdBy;
  @Column private String note;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

  @PrePersist void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
    if (effectiveFrom == null) effectiveFrom = createdAt;
  }

  public UUID getId() { return id; }
  public String getScope() { return scope; }
  public void setScope(String value) { scope = value; }
  public String getScopeRef() { return scopeRef; }
  public void setScopeRef(String value) { scopeRef = value; }
  public int getCommissionBps() { return commissionBps; }
  public void setCommissionBps(int value) { commissionBps = value; }
  public Instant getEffectiveFrom() { return effectiveFrom; }
  public void setEffectiveFrom(Instant value) { effectiveFrom = value; }
  public Instant getEffectiveTo() { return effectiveTo; }
  public void setEffectiveTo(Instant value) { effectiveTo = value; }
  public UUID getCreatedBy() { return createdBy; }
  public void setCreatedBy(UUID value) { createdBy = value; }
  public String getNote() { return note; }
  public void setNote(String value) { note = value; }
  public Instant getCreatedAt() { return createdAt; }
}
