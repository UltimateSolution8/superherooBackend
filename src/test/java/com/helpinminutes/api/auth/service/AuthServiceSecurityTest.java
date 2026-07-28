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
  @Test
  public void passwordSignupBlocksPrivilegedRoles() {
    AuthService service = new AuthService(
        mock(AppProperties.class),
        mock(OtpService.class),
        mock(UserRepository.class),
        mock(HelperProfileRepository.class),
        mock(JwtService.class),
        mock(RefreshTokenRepository.class),
        mock(PasswordEncoder.class),
        mock(SupabaseStorageService.class),
        mock(HelperMediatorLinkRepository.class));

    assertThrows(BadRequestException.class, () -> service.signupWithPassword(
        "mediator@helpinminutes.app", "Password@123", "9876543210", "Mediator", UserRole.MEDIATOR));
    assertThrows(BadRequestException.class, () -> service.signupWithPassword(
        "support@helpinminutes.app", "Password@123", "9876543211", "Support", UserRole.SUPPORT));
    assertThrows(BadRequestException.class, () -> service.signupWithPassword(
        "kyc@helpinminutes.app", "Password@123", "9876543212", "KYC", UserRole.KYC));
  }
}
