package com.helpinminutes.api.auth.controller;

import com.helpinminutes.api.auth.dto.AuthResponse;
import com.helpinminutes.api.auth.dto.ForgotPasswordRequest;
import com.helpinminutes.api.auth.dto.ForgotPasswordResponse;
import com.helpinminutes.api.auth.dto.HelperKycSignupRequest;
import com.helpinminutes.api.auth.dto.LogoutRequest;
import com.helpinminutes.api.auth.dto.ResetPasswordRequest;
import com.helpinminutes.api.auth.dto.OtpStartRequest;
import com.helpinminutes.api.auth.dto.OtpStartResponse;
import com.helpinminutes.api.auth.dto.OtpVerifyRequest;
import com.helpinminutes.api.auth.dto.PasswordLoginRequest;
import com.helpinminutes.api.auth.dto.PasswordSignupRequest;
import com.helpinminutes.api.auth.dto.RefreshRequest;
import com.helpinminutes.api.auth.service.AuthService;
import com.helpinminutes.api.config.AppProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final AppProperties props;

  public AuthController(AuthService auth, AppProperties props) {
    this.auth = auth;
    this.props = props;
  }

  @PostMapping("/otp/start")
  public OtpStartResponse start(@Valid @RequestBody OtpStartRequest req) {
    auth.startOtp(req.phone(), req.channel(), req.appHash(), req.role());
    return new OtpStartResponse(req.phone(), true);
  }

  @PostMapping("/otp/verify")
  public AuthResponse verify(@Valid @RequestBody OtpVerifyRequest req) {
    return auth.verifyOtp(req.phone(), req.otp(), req.role());
  }

  @PostMapping("/refresh")
  public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
    return auth.refresh(req.refreshToken());
  }

  @PostMapping("/password/signup")
  public AuthResponse passwordSignup(@Valid @RequestBody PasswordSignupRequest req) {
    return auth.signupWithPassword(req.email(), req.password(), req.phone(), req.displayName(), req.role());
  }

  @PostMapping("/password/login")
  public AuthResponse passwordLogin(@Valid @RequestBody PasswordLoginRequest req) {
    return auth.loginWithPassword(req.email(), req.password());
  }

  /**
   * Emails a reset code. Always reports success — see {@link ForgotPasswordResponse}.
   */
  @PostMapping("/password/forgot")
  public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
    auth.startPasswordReset(req.email());
    return new ForgotPasswordResponse(req.email(), true);
  }

  @PostMapping("/password/reset")
  public AuthResponse resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
    return auth.resetPassword(req.email(), req.otp(), req.newPassword());
  }

  /** Revokes the refresh token. Public because the access token may already have expired. */
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@Valid @RequestBody LogoutRequest req) {
    auth.logout(req.refreshToken());
  }

  @PostMapping("/email/otp/start")
  public EmailOtpStartResponse startEmailOtp(@Valid @RequestBody EmailOtpStartRequest req) {
    auth.startEmailOtp(req.email());
    return new EmailOtpStartResponse(req.email(), true);
  }

  @PostMapping("/email/otp/verify")
  public AuthResponse verifyEmailOtp(@Valid @RequestBody EmailOtpVerifyRequest req) {
    return auth.verifyEmailOtp(req.email(), req.otp());
  }

  @PostMapping(value = "/password/signup/helper-kyc", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public AuthResponse helperKycSignup(
      @RequestParam @NotBlank @Email String email,
      @RequestParam @NotBlank String password,
      @RequestParam(required = false) @Pattern(
          regexp = "^(|[6-9]\\d{9}|91[6-9]\\d{9}|0[6-9]\\d{9})$",
          message = "phone must be a valid Indian mobile number") String phone,
      @RequestParam(required = false) String displayName,
      @RequestParam @NotBlank String fullName,
      @RequestParam @NotBlank String idNumber,
      @RequestParam("idFront") MultipartFile idFront,
      @RequestParam("idBack") MultipartFile idBack,
      @RequestParam("selfie") MultipartFile selfie) {
    HelperKycSignupRequest req = new HelperKycSignupRequest(email, password, phone, displayName, fullName, idNumber);
    return auth.signupHelperWithKyc(req, idFront, idBack, selfie);
  }

  public record EmailOtpStartRequest(@NotBlank @Email String email) {}

  public record EmailOtpStartResponse(String email, boolean sent) {}

  public record EmailOtpVerifyRequest(@NotBlank @Email String email, @NotBlank String otp) {}
}
