package com.helpinminutes.api.users.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class SavedAddressDtos {
  private SavedAddressDtos() {}

  public record SavedAddressResponse(
      UUID id,
      String label,
      String addressText,
      double lat,
      double lng,
      String landmark,
      boolean isDefault,
      Instant createdAt,
      Instant updatedAt) {}

  public record SaveAddressRequest(
      @NotBlank @Size(max = 40) String label,
      @NotBlank @Size(max = 400) String addressText,
      @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
      @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
      @Size(max = 200) String landmark,
      Boolean isDefault) {}

  /**
   * A partial update. Every field is optional; null means "leave it alone".
   *
   * Bounds still apply to whatever is supplied — a PATCH is not a way around the
   * checks a POST has to pass.
   */
  public record UpdateAddressRequest(
      @Size(max = 40) String label,
      @Size(max = 400) String addressText,
      @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
      @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
      @Size(max = 200) String landmark,
      Boolean isDefault) {}

  /**
   * The one-time import of whatever the app already had on the device.
   *
   * Idempotent by label: re-running it after a partial failure must not double the
   * list, and two devices importing the same addresses must converge.
   */
  public record ImportAddressesRequest(
      @NotNull @Size(max = 50) java.util.List<@NotNull SaveAddressRequest> addresses) {}
}
