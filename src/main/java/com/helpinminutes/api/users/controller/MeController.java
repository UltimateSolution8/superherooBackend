package com.helpinminutes.api.users.controller;

import com.helpinminutes.api.common.InputValidators;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.users.service.EmailVerificationService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class MeController {
  private final UserRepository users;
  private final EmailVerificationService emailVerificationService;
  private final AppProperties props;

  public MeController(UserRepository users, EmailVerificationService emailVerificationService, AppProperties props) {
    this.users = users;
    this.emailVerificationService = emailVerificationService;
    this.props = props;
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

  @PostMapping("/me/email/verify/send")
  public SendEmailOtpResponse sendEmailOtp(
      @AuthenticationPrincipal UserPrincipal principal) {
    UserEntity u = users.findById(principal.userId()).orElseThrow();
    if (u.getEmail() == null || u.getEmail().isBlank()) {
      throw new BadRequestException("Email is not added");
    }
    String otp = emailVerificationService.sendVerificationEmail(u.getEmail());
    return new SendEmailOtpResponse(true, props.otp().returnOtpInResponse() ? otp : null);
  }

  @PutMapping("/me/email/verify")
  public MeResponse verifyEmail(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam("otp") String otp) {
    UserEntity u = users.findById(principal.userId()).orElseThrow();
    if (u.getEmail() == null || u.getEmail().isBlank()) {
      throw new BadRequestException("Email is not added");
    }
    if (!emailVerificationService.verifyEmailOtp(u.getEmail(), otp)) {
      throw new BadRequestException("Invalid verification code");
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

  public record SendEmailOtpResponse(boolean success, String otp) {}
}
