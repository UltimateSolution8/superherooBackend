package com.helpinminutes.api.users.controller;

import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.dto.SavedAddressDtos.ImportAddressesRequest;
import com.helpinminutes.api.users.dto.SavedAddressDtos.SaveAddressRequest;
import com.helpinminutes.api.users.dto.SavedAddressDtos.SavedAddressResponse;
import com.helpinminutes.api.users.dto.SavedAddressDtos.UpdateAddressRequest;
import com.helpinminutes.api.users.service.SavedAddressService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The citizen's own saved addresses.
 *
 * Every route derives the owner from the authenticated principal; none of them take
 * a user id from the caller. Additive to the API — nothing existing changed shape.
 */
@RestController
@RequestMapping("/api/v1/me/addresses")
public class SavedAddressController {
  private final SavedAddressService addresses;

  public SavedAddressController(SavedAddressService addresses) {
    this.addresses = addresses;
  }

  @GetMapping
  public List<SavedAddressResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
    return addresses.list(principal.userId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SavedAddressResponse create(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody SaveAddressRequest request) {
    return addresses.create(principal.userId(), request);
  }

  @PatchMapping("/{addressId}")
  public SavedAddressResponse update(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID addressId,
      @Valid @RequestBody UpdateAddressRequest request) {
    return addresses.update(principal.userId(), addressId, request);
  }

  @PostMapping("/{addressId}/default")
  public SavedAddressResponse makeDefault(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID addressId) {
    return addresses.makeDefault(principal.userId(), addressId);
  }

  @DeleteMapping("/{addressId}")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID addressId) {
    addresses.delete(principal.userId(), addressId);
    return ResponseEntity.noContent().build();
  }

  /** One-time migration of an install's device-local list. Idempotent by label. */
  @PostMapping("/import")
  public List<SavedAddressResponse> importLocal(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody ImportAddressesRequest request) {
    return addresses.importLocal(principal.userId(), request);
  }
}
