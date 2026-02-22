package com.helpinminutes.api.auth.controller;

import com.helpinminutes.api.auth.dto.AuthResponse;
import com.helpinminutes.api.auth.dto.HelperKycSignupRequest;
import com.helpinminutes.api.auth.dto.OtpStartRequest;
import com.helpinminutes.api.auth.dto.OtpStartResponse;
import com.helpinminutes.api.auth.dto.OtpVerifyRequest;
import com.helpinminutes.api.auth.dto.PasswordLoginRequest;
import com.helpinminutes.api.auth.dto.PasswordSignupRequest;
import com.helpinminutes.api.auth.dto.RefreshRequest;
import com.helpinminutes.api.auth.service.AuthService;
import com.helpinminutes.api.config.AppProperties;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
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
    String otp = auth.startOtp(req.phone(), req.channel());
    return new OtpStartResponse(req.phone(), true, props.otp().returnOtpInResponse() ? otp : null);
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

  @PostMapping(value = "/password/signup/helper-kyc", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public AuthResponse helperKycSignup(
      @RequestParam String email,
      @RequestParam String password,
      @RequestParam(required = false) String phone,
      @RequestParam(required = false) String displayName,
      @RequestParam String fullName,
      @RequestParam String idNumber,
      @RequestParam("idFront") MultipartFile idFront,
      @RequestParam("idBack") MultipartFile idBack,
      @RequestParam("selfie") MultipartFile selfie) {
    HelperKycSignupRequest req = new HelperKycSignupRequest(email, password, phone, displayName, fullName, idNumber);
    return auth.signupHelperWithKyc(req, idFront, idBack, selfie);
  }
}
