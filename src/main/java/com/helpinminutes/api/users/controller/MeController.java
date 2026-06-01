package com.helpinminutes.api.users.controller;

import com.helpinminutes.api.common.InputValidators;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class MeController {
  private final UserRepository users;

  public MeController(UserRepository users) {
    this.users = users;
  }

  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal UserPrincipal principal) {
    UUID userId = principal.userId();
    UserEntity u = users.findById(userId).orElseThrow();
    return new MeResponse(
        u.getId(),
        u.getRole().name(),
        u.getPhone(),
        u.getEmail(),
        u.isEmailVerified(),
        u.getDisplayName(),
        u.getDemoBalancePaise(),
        u.isBulkCsvEnabled());
  }

  @PutMapping("/me")
  public MeResponse updateMe(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody UpdateMeRequest req) {
    UUID userId = principal.userId();
    UserEntity u = users.findById(userId).orElseThrow();
    if (req.displayName() != null) {
      String displayName = req.displayName().trim();
      if (!displayName.isBlank()) {
        u.setDisplayName(displayName);
      }
    }
    if (req.email() != null) {
      String email = InputValidators.normalizeEmailOrNull(req.email());
      String current = u.getEmail();
      if (email == null) {
        u.setEmail(null);
        u.setEmailVerified(false);
      } else if (current == null || !email.equalsIgnoreCase(current)) {
        users.findByEmail(email).ifPresent(existing -> {
          if (!existing.getId().equals(u.getId())) {
            throw new BadRequestException("email already in use");
          }
        });
        u.setEmail(email);
        u.setEmailVerified(false);
      }
    }
    users.save(u);
    return new MeResponse(
        u.getId(),
        u.getRole().name(),
        u.getPhone(),
        u.getEmail(),
        u.isEmailVerified(),
        u.getDisplayName(),
        u.getDemoBalancePaise(),
        u.isBulkCsvEnabled());
  }

  @PutMapping("/me/email/verify")
  public MeResponse verifyEmail(
      @AuthenticationPrincipal UserPrincipal principal) {
    UserEntity u = users.findById(principal.userId()).orElseThrow();
    if (u.getEmail() == null || u.getEmail().isBlank()) {
      throw new BadRequestException("Email is not added");
    }
    u.setEmailVerified(true);
    users.save(u);
    return new MeResponse(
        u.getId(),
        u.getRole().name(),
        u.getPhone(),
        u.getEmail(),
        u.isEmailVerified(),
        u.getDisplayName(),
        u.getDemoBalancePaise(),
        u.isBulkCsvEnabled());
  }

  public record UpdateMeRequest(String displayName, String email) {}

  public record MeResponse(
      UUID id,
      String role,
      String phone,
      String email,
      boolean emailVerified,
      String displayName,
      Long demoBalancePaise,
      boolean bulkCsvEnabled) {}
}
