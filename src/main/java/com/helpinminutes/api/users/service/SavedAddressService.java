package com.helpinminutes.api.users.service;

import com.helpinminutes.api.common.ServiceArea;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.users.dto.SavedAddressDtos.ImportAddressesRequest;
import com.helpinminutes.api.users.dto.SavedAddressDtos.SaveAddressRequest;
import com.helpinminutes.api.users.dto.SavedAddressDtos.SavedAddressResponse;
import com.helpinminutes.api.users.dto.SavedAddressDtos.UpdateAddressRequest;
import com.helpinminutes.api.users.model.SavedAddressEntity;
import com.helpinminutes.api.users.repo.SavedAddressRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The citizen's saved addresses.
 *
 * <p>Ownership is enforced by every query being scoped to the caller's user id
 * ({@code findByIdAndUserId}), not by a check the next edit might forget. A request
 * carrying somebody else's address id finds nothing and gets a 404 — which is also
 * the right answer for privacy: it does not confirm the row exists.
 *
 * <p>The service area is checked here as well as in the app. Hiding the control is
 * presentation; the endpoint has to refuse too.
 */
@Service
public class SavedAddressService {

  /**
   * Enough for home, work, both parents, a gym and a few one-offs.
   *
   * A cap at all is the point: this list is rendered in full inside the address
   * picker, and it is written to from a client we do not control.
   */
  public static final int MAX_ADDRESSES_PER_USER = 25;

  private final SavedAddressRepository addresses;

  public SavedAddressService(SavedAddressRepository addresses) {
    this.addresses = addresses;
  }

  @Transactional(readOnly = true)
  public List<SavedAddressResponse> list(UUID userId) {
    return addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId).stream()
        .map(SavedAddressService::toResponse)
        .toList();
  }

  @Transactional
  public SavedAddressResponse create(UUID userId, SaveAddressRequest request) {
    if (addresses.countByUserId(userId) >= MAX_ADDRESSES_PER_USER) {
      throw new BadRequestException(
          "You can save up to " + MAX_ADDRESSES_PER_USER + " addresses. Delete one first.");
    }
    String label = requireLabel(request.label());
    if (addresses.existsByUserIdAndLabelIgnoreCase(userId, label)) {
      throw new BadRequestException("You already have an address called \"" + label + "\".");
    }
    requireServiceable(request.lat(), request.lng());

    SavedAddressEntity entity = new SavedAddressEntity();
    entity.setUserId(userId);
    entity.setLabel(label);
    entity.setAddressText(requireAddressText(request.addressText()));
    entity.setLat(request.lat());
    entity.setLng(request.lng());
    entity.setLandmark(trimToNull(request.landmark()));
    // The first address a citizen saves is their default whether they said so or
    // not — otherwise the create-task prefill has nothing to reach for.
    entity.setDefaultAddress(
        Boolean.TRUE.equals(request.isDefault()) || addresses.countByUserId(userId) == 0);
    SavedAddressEntity saved = addresses.save(entity);
    if (saved.isDefaultAddress()) {
      addresses.clearDefaultExcept(userId, saved.getId());
    }
    return toResponse(saved);
  }

  @Transactional
  public SavedAddressResponse update(UUID userId, UUID addressId, UpdateAddressRequest request) {
    SavedAddressEntity entity = addresses.findByIdAndUserId(addressId, userId)
        .orElseThrow(() -> new NotFoundException("Saved address not found"));

    if (request.label() != null) {
      String label = requireLabel(request.label());
      addresses.findByUserIdAndLabelIgnoreCase(userId, label).ifPresent(other -> {
        if (!other.getId().equals(addressId)) {
          throw new BadRequestException("You already have an address called \"" + label + "\".");
        }
      });
      entity.setLabel(label);
    }
    if (request.addressText() != null) entity.setAddressText(requireAddressText(request.addressText()));
    // Coordinates move together or not at all — half an update is a pin in the sea.
    if (request.lat() != null || request.lng() != null) {
      if (request.lat() == null || request.lng() == null) {
        throw new BadRequestException("Send both lat and lng, or neither.");
      }
      requireServiceable(request.lat(), request.lng());
      entity.setLat(request.lat());
      entity.setLng(request.lng());
    }
    if (request.landmark() != null) entity.setLandmark(trimToNull(request.landmark()));
    if (Boolean.TRUE.equals(request.isDefault())) {
      entity.setDefaultAddress(true);
      addresses.clearDefaultExcept(userId, entity.getId());
    }
    return toResponse(addresses.save(entity));
  }

  @Transactional
  public SavedAddressResponse makeDefault(UUID userId, UUID addressId) {
    SavedAddressEntity entity = addresses.findByIdAndUserId(addressId, userId)
        .orElseThrow(() -> new NotFoundException("Saved address not found"));
    addresses.clearDefaultExcept(userId, entity.getId());
    entity.setDefaultAddress(true);
    return toResponse(addresses.save(entity));
  }

  @Transactional
  public void delete(UUID userId, UUID addressId) {
    SavedAddressEntity entity = addresses.findByIdAndUserId(addressId, userId)
        .orElseThrow(() -> new NotFoundException("Saved address not found"));
    boolean wasDefault = entity.isDefaultAddress();
    addresses.delete(entity);
    if (!wasDefault) return;
    // Never leave the citizen with addresses but no default; the create-task
    // prefill reads the default and would silently stop working.
    addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId).stream()
        .findFirst()
        .ifPresent(next -> {
          next.setDefaultAddress(true);
          addresses.save(next);
        });
  }

  /**
   * One-time import of the addresses an existing install already holds locally.
   *
   * Idempotent by label so a retry after a dropped connection converges instead of
   * duplicating, and so two devices importing the same list agree. Individually
   * invalid entries are skipped rather than failing the batch — this runs in the
   * background on app start and must never be something the citizen has to resolve.
   */
  @Transactional
  public List<SavedAddressResponse> importLocal(UUID userId, ImportAddressesRequest request) {
    for (SaveAddressRequest candidate : request.addresses()) {
      try {
        String label = requireLabel(candidate.label());
        if (addresses.existsByUserIdAndLabelIgnoreCase(userId, label)) continue;
        if (addresses.countByUserId(userId) >= MAX_ADDRESSES_PER_USER) break;
        create(userId, candidate);
      } catch (RuntimeException ignored) {
        // One bad row must not cost the citizen the rest of their addresses.
      }
    }
    return list(userId);
  }

  private static void requireServiceable(double lat, double lng) {
    if (!ServiceArea.isWithinHyderabad(lat, lng)) {
      throw new BadRequestException("That address is outside our service area.");
    }
  }

  private static String requireLabel(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) throw new BadRequestException("Give the address a name.");
    return trimmed;
  }

  private static String requireAddressText(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) throw new BadRequestException("The address cannot be empty.");
    return trimmed;
  }

  private static String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static SavedAddressResponse toResponse(SavedAddressEntity entity) {
    return new SavedAddressResponse(
        entity.getId(),
        entity.getLabel(),
        entity.getAddressText(),
        entity.getLat(),
        entity.getLng(),
        entity.getLandmark(),
        entity.isDefaultAddress(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
