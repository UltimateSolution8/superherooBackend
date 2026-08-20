package com.helpinminutes.api.users.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An address a citizen chose to keep — "Home", "Work", "Mum's place".
 *
 * Server-side because the app's copy was device-local: reinstalling lost every
 * address, and a second device never saw any of them. The app still keeps a local
 * cache so the picker renders offline, but this row is the source of truth.
 */
@Entity
@Table(name = "saved_addresses")
public class SavedAddressEntity {
  @Id private UUID id;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(nullable = false, length = 40) private String label;
  @Column(name = "address_text", nullable = false, length = 400) private String addressText;
  @Column(nullable = false) private double lat;
  @Column(nullable = false) private double lng;
  @Column(length = 200) private String landmark;
  @Column(name = "is_default", nullable = false) private boolean defaultAddress;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @PrePersist void prePersist() {
    if (id == null) id = UUID.randomUUID();
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

  public UUID getId() { return id; }
  public void setId(UUID value) { id = value; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID value) { userId = value; }
  public String getLabel() { return label; }
  public void setLabel(String value) { label = value; }
  public String getAddressText() { return addressText; }
  public void setAddressText(String value) { addressText = value; }
  public double getLat() { return lat; }
  public void setLat(double value) { lat = value; }
  public double getLng() { return lng; }
  public void setLng(double value) { lng = value; }
  public String getLandmark() { return landmark; }
  public void setLandmark(String value) { landmark = value; }
  public boolean isDefaultAddress() { return defaultAddress; }
  public void setDefaultAddress(boolean value) { defaultAddress = value; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
