package com.helpinminutes.api.users.controller;

import com.helpinminutes.api.common.InputValidators;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.users.service.EmailVerificationService;
import com.helpinminutes.api.auth.service.OtpService;
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
  private final OtpService otpService;

  public MeController(UserRepository users, EmailVerificationService emailVerificationService, AppProperties props, OtpService otpService) {
    this.users = users;
    this.emailVerificationService = emailVerificationService;
    this.props = props;
    this.otpService = otpService;
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
        u.isBulkCsvEnabled(),
        u.getDob(),
        u.getBloodGroup(),
        u.getGender());
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
    if (req.dob() != null) {
      u.setDob(req.dob().trim().isEmpty() ? null : req.dob().trim());
    }
    if (req.bloodGroup() != null) {
      u.setBloodGroup(req.bloodGroup().trim().isEmpty() ? null : req.bloodGroup().trim());
    }
    if (req.gender() != null) {
      u.setGender(req.gender().trim().isEmpty() ? null : req.gender().trim());
    }
    users.save(u);
    return new MeResponse(
        u.getId(),
        u.getRole().name(),
        u.getPhone(),
        u.getEmail(),
        u.isEmailVerified(),
        u.getDisplayName(),
        u.isBulkCsvEnabled(),
        u.getDob(),
        u.getBloodGroup(),
        u.getGender());
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
        u.isBulkCsvEnabled(),
        u.getDob(),
        u.getBloodGroup(),
        u.getGender());
  }

  @PostMapping("/me/phone/verify/send")
  public SendPhoneOtpResponse sendPhoneOtp(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam("phone") String phone) {
    String normalized = InputValidators.normalizeIndianPhoneOrNull(phone);
    if (normalized == null || normalized.isBlank()) {
      throw new BadRequestException("Invalid phone number");
    }
    users.findByPhone(normalized).ifPresent(existing -> {
      if (!existing.getId().equals(principal.userId())) {
        throw new BadRequestException("Phone number already in use");
      }
    });
    String otp = otpService.startOtp(normalized, "sms");
    return new SendPhoneOtpResponse(true, props.otp().returnOtpInResponse() ? otp : null);
  }

  @PutMapping("/me/phone/verify")
  public MeResponse verifyPhone(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam("phone") String phone,
      @RequestParam("otp") String otp) {
    String normalized = InputValidators.normalizeIndianPhoneOrNull(phone);
    if (normalized == null || normalized.isBlank()) {
      throw new BadRequestException("Invalid phone number");
    }
    users.findByPhone(normalized).ifPresent(existing -> {
      if (!existing.getId().equals(principal.userId())) {
        throw new BadRequestException("Phone number already in use");
      }
    });
    if (!otpService.verifyOtp(normalized, otp)) {
      throw new BadRequestException("Invalid verification code");
    }
    UserEntity u = users.findById(principal.userId()).orElseThrow();
    u.setPhone(normalized);
    users.save(u);
    return new MeResponse(
        u.getId(),
        u.getRole().name(),
        u.getPhone(),
        u.getEmail(),
        u.isEmailVerified(),
        u.getDisplayName(),
        u.isBulkCsvEnabled(),
        u.getDob(),
        u.getBloodGroup(),
        u.getGender());
  }

  public record UpdateMeRequest(String displayName, String email, String dob, String bloodGroup, String gender) {}

  public record MeResponse(
      UUID id,
      String role,
      String phone,
      String email,
      boolean emailVerified,
      String displayName,
      boolean bulkCsvEnabled,
      String dob,
      String bloodGroup,
      String gender) {}

  public record SendEmailOtpResponse(boolean success, String otp) {}

  public record SendPhoneOtpResponse(boolean success, String otp) {}
}
