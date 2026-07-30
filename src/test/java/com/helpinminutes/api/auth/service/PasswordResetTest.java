package com.helpinminutes.api.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.auth.repo.RefreshTokenRepository;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.mediator.repo.HelperMediatorLinkRepository;
import com.helpinminutes.api.security.JwtService;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.users.service.EmailVerificationService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordResetTest {

  private static final String EMAIL = "citizen@gmail.com";

  private UserRepository users;
  private RefreshTokenRepository refreshTokens;
  private EmailVerificationService emailVerification;
  private PasswordEncoder passwordEncoder;
  private JwtService jwt;
  private AuthService service;
  private UserEntity user;

  @BeforeEach
  void setUp() {
    users = mock(UserRepository.class);
    refreshTokens = mock(RefreshTokenRepository.class);
    emailVerification = mock(EmailVerificationService.class);
    passwordEncoder = mock(PasswordEncoder.class);
    jwt = mock(JwtService.class);

    AppProperties props = mock(AppProperties.class);
    when(props.jwt()).thenReturn(new AppProperties.Jwt(
        "test-access-secret-0123456789abcdef0123456789abcdef",
        "test-refresh-secret-0123456789abcdef0123456789abcdef",
        900, 3600));
    when(jwt.createAccessToken(any())).thenReturn("access-token");
    when(jwt.createRefreshToken(any())).thenReturn("refresh-token");
    when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");

    user = new UserEntity();
    user.setId(UUID.randomUUID());
    user.setEmail(EMAIL);
    user.setRole(UserRole.BUYER);
    user.setStatus(UserStatus.ACTIVE);
    user.setEmailVerified(false);

    service = new AuthService(
        props, mock(OtpService.class), users, mock(HelperProfileRepository.class), jwt,
        refreshTokens, passwordEncoder, mock(SupabaseStorageService.class),
        mock(HelperMediatorLinkRepository.class), emailVerification);
  }

  // ─── forgot password ──────────────────────────────────────────────────────

  @Test
  void forgotPasswordDoesNotRevealWhetherTheAccountExists() {
    when(users.findByEmail(anyString())).thenReturn(Optional.empty());

    // No exception, no distinguishing signal — just a quiet no-op.
    assertNull(service.startPasswordReset("nobody@gmail.com"));
    verify(emailVerification, never()).sendPasswordResetEmail(anyString());
  }

  @Test
  void forgotPasswordSendsCodeForAnExistingActiveAccount() {
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    when(emailVerification.sendPasswordResetEmail(EMAIL)).thenReturn("998877");

    assertEquals("998877", service.startPasswordReset(EMAIL));
  }

  @Test
  void forgotPasswordIsSilentForBlockedAccounts() {
    user.setStatus(UserStatus.BLOCKED);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

    assertNull(service.startPasswordReset(EMAIL));
    verify(emailVerification, never()).sendPasswordResetEmail(anyString());
  }

  // ─── reset password ───────────────────────────────────────────────────────

  @Test
  void resetPasswordRejectsAnInvalidCode() {
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    when(emailVerification.verifyPasswordResetOtp(EMAIL, "000000")).thenReturn(false);

    assertThrows(BadRequestException.class,
        () -> service.resetPassword(EMAIL, "000000", "NewPassw0rd"));
    verify(users, never()).save(any());
  }

  @Test
  void resetPasswordRevokesEveryExistingSession() {
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    when(emailVerification.verifyPasswordResetOtp(EMAIL, "445566")).thenReturn(true);

    var response = service.resetPassword(EMAIL, "445566", "NewPassw0rd");

    assertNotNull(response.accessToken());
    // Any other device holding a refresh token must be signed out.
    verify(refreshTokens).revokeAllByUserId(eq(user.getId()), any(Instant.class));
    verify(passwordEncoder).encode("NewPassw0rd");
    assertEquals("bcrypt-hash", user.getPasswordHash());
  }

  @Test
  void resetPasswordMarksTheEmailVerified() {
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    when(emailVerification.verifyPasswordResetOtp(EMAIL, "445566")).thenReturn(true);

    service.resetPassword(EMAIL, "445566", "NewPassw0rd");

    // Completing an emailed challenge proves control of the address.
    org.junit.jupiter.api.Assertions.assertTrue(user.isEmailVerified());
  }

  @Test
  void resetPasswordEnforcesThePasswordPolicy() {
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

    assertThrows(BadRequestException.class,
        () -> service.resetPassword(EMAIL, "445566", "short1"), "too short");
    assertThrows(BadRequestException.class,
        () -> service.resetPassword(EMAIL, "445566", "alllettersonly"), "no digit");
    assertThrows(BadRequestException.class,
        () -> service.resetPassword(EMAIL, "445566", "password123"), "banned");

    // The code is never even checked when the new password is unacceptable.
    verify(emailVerification, never()).verifyPasswordResetOtp(anyString(), anyString());
  }

  // ─── logout ───────────────────────────────────────────────────────────────

  @Test
  void logoutRevokesThePresentedRefreshToken() {
    service.logout("some-refresh-token");
    verify(refreshTokens).revokeAllByHash(anyString(), any(Instant.class));
  }

  @Test
  void logoutIsIdempotentAndIgnoresBlankInput() {
    service.logout(null);
    service.logout("   ");
    verify(refreshTokens, never()).revokeAllByHash(anyString(), any(Instant.class));
  }
}
