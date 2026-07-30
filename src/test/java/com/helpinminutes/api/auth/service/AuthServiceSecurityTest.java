package com.helpinminutes.api.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.helpinminutes.api.auth.repo.RefreshTokenRepository;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.mediator.repo.HelperMediatorLinkRepository;
import com.helpinminutes.api.security.JwtService;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

public class AuthServiceSecurityTest {

  /** Phone numbers that used to bypass OTP verification entirely. */
  private static final String[] FORMER_REVIEWER_PHONES = {
      "9999999991", "9999999992", "9999999993"};

  private AuthService newService(OtpService otp, UserRepository users) {
    return new AuthService(
        mock(AppProperties.class),
        otp,
        users,
        mock(HelperProfileRepository.class),
        mock(JwtService.class),
        mock(RefreshTokenRepository.class),
        mock(PasswordEncoder.class),
        mock(SupabaseStorageService.class),
        mock(HelperMediatorLinkRepository.class));
  }

  @Test
  public void formerReviewerPhonesNoLongerBypassOtpVerification() {
    OtpService otp = mock(OtpService.class);
    when(otp.verifyOtp(anyString(), anyString())).thenReturn(false);
    AuthService service = newService(otp, mock(UserRepository.class));

    for (String phone : FORMER_REVIEWER_PHONES) {
      assertThrows(BadRequestException.class,
          () -> service.verifyOtp(phone, "123456", UserRole.BUYER),
          "Reviewer phone " + phone + " must not bypass OTP verification");
      assertThrows(BadRequestException.class,
          () -> service.verifyOtp(phone, "000000", UserRole.HELPER),
          "Reviewer phone " + phone + " must not bypass OTP verification");
    }
  }

  @Test
  public void startOtpDelegatesToOtpServiceForEveryPhone() {
    OtpService otp = mock(OtpService.class);
    when(otp.startOtp(anyString(), any())).thenReturn("654321");
    AuthService service = newService(otp, mock(UserRepository.class));

    for (String phone : FORMER_REVIEWER_PHONES) {
      // Must not short-circuit to a hardcoded "123456".
      assertEquals("654321", service.startOtp(phone, null));
      verify(otp).startOtp(phone, null);
    }
  }

  @Test
  public void mediatorRoleCannotSelfProvisionViaOtp() {
    OtpService otp = mock(OtpService.class);
    when(otp.verifyOtp(anyString(), anyString())).thenReturn(true);
    UserRepository users = mock(UserRepository.class);
    when(users.findByPhoneAndRole(anyString(), any())).thenReturn(java.util.Optional.empty());
    AuthService service = newService(otp, users);

    // Previously the reviewer bypass let 9999999993 self-create a MEDIATOR.
    assertThrows(BadRequestException.class,
        () -> service.verifyOtp("9999999993", "123456", UserRole.MEDIATOR));
  }

  @Test
  public void passwordSignupBlocksPrivilegedRoles() {
    AuthService service = newService(mock(OtpService.class), mock(UserRepository.class));

    assertThrows(BadRequestException.class, () -> service.signupWithPassword(
        "mediator@helpinminutes.app", "Password@123", "9876543210", "Mediator", UserRole.MEDIATOR));
    assertThrows(BadRequestException.class, () -> service.signupWithPassword(
        "support@helpinminutes.app", "Password@123", "9876543211", "Support", UserRole.SUPPORT));
    assertThrows(BadRequestException.class, () -> service.signupWithPassword(
        "kyc@helpinminutes.app", "Password@123", "9876543212", "KYC", UserRole.KYC));
  }
}
