package com.helpinminutes.api.auth.service;

import com.helpinminutes.api.auth.dto.AuthResponse;
import com.helpinminutes.api.auth.dto.HelperKycSignupRequest;
import com.helpinminutes.api.auth.model.RefreshTokenEntity;
import com.helpinminutes.api.auth.repo.RefreshTokenRepository;
import com.helpinminutes.api.common.HashUtils;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.security.JwtService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AuthService {
  private final AppProperties props;
  private final OtpService otp;
  private final UserRepository users;
  private final HelperProfileRepository helperProfiles;
  private final JwtService jwt;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordEncoder passwordEncoder;
  private final SupabaseStorageService storage;

  public AuthService(
      AppProperties props,
      OtpService otp,
      UserRepository users,
      HelperProfileRepository helperProfiles,
      JwtService jwt,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      SupabaseStorageService storage) {
    this.props = props;
    this.otp = otp;
    this.users = users;
    this.helperProfiles = helperProfiles;
    this.jwt = jwt;
    this.refreshTokens = refreshTokens;
    this.passwordEncoder = passwordEncoder;
    this.storage = storage;
  }

  public String startOtp(String phone, String channel) {
    return otp.startOtp(phone, channel);
  }

  @Transactional
  public AuthResponse verifyOtp(String phone, String otpCode, UserRole role) {
    if (!otp.verifyOtp(phone, otpCode)) {
      throw new BadRequestException("Invalid OTP");
    }

    UserEntity user = users.findByPhone(phone).orElseGet(() -> {
      if (role == UserRole.ADMIN) {
        String bootstrapAdminPhone = System.getenv("BOOTSTRAP_ADMIN_PHONE");
        if (bootstrapAdminPhone == null || !bootstrapAdminPhone.equals(phone)) {
          throw new BadRequestException("Admin signup is disabled");
        }
      }

      UserEntity u = new UserEntity();
      u.setPhone(phone);
      u.setRole(role);
      u.setStatus(UserStatus.ACTIVE);
      users.save(u);

      if (role == UserRole.HELPER) {
        HelperProfileEntity hp = new HelperProfileEntity();
        hp.setUserId(u.getId());
        helperProfiles.save(hp);
      }

      return u;
    });

    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new BadRequestException("User is not active");
    }

    if (user.getRole() != role) {
      throw new BadRequestException("Role mismatch for this phone");
    }

    String accessToken = jwt.createAccessToken(user);
    String refreshToken = jwt.createRefreshToken(user);

    persistRefreshToken(user, refreshToken);

    return toAuthResponse(user, accessToken, refreshToken);
  }

  @Transactional
  public AuthResponse refresh(String refreshToken) {
    UserPrincipal subject = jwt.parseRefreshToken(refreshToken);
    String hash = HashUtils.sha256Hex(refreshToken);

    RefreshTokenEntity existing = refreshTokens.findActiveByHash(hash, Instant.now())
        .orElseThrow(() -> new BadRequestException("Refresh token invalid"));

    // rotate
    refreshTokens.revoke(existing.getId(), Instant.now());

    UserEntity user = users.findById(subject.userId())
        .orElseThrow(() -> new BadRequestException("User not found"));

    String newAccess = jwt.createAccessToken(user);
    String newRefresh = jwt.createRefreshToken(user);
    persistRefreshToken(user, newRefresh);

    return toAuthResponse(user, newAccess, newRefresh);
  }

  @Transactional
  public AuthResponse signupWithPassword(String email, String password, String phone, String displayName, UserRole role) {
    if (role == UserRole.ADMIN) {
      throw new BadRequestException("Admin signup is disabled");
    }
    String em = normalizeEmail(email);
    if (users.findByEmail(em).isPresent()) {
      throw new BadRequestException("Email already in use");
    }

    String normalizedPhone = normalizePhoneOrNull(phone);
    if (normalizedPhone != null && users.findByPhone(normalizedPhone).isPresent()) {
      throw new BadRequestException("Phone already in use");
    }

    UserEntity u = new UserEntity();
    u.setEmail(em);
    u.setPhone(normalizedPhone);
    u.setRole(role);
    u.setStatus(UserStatus.ACTIVE);
    u.setDisplayName(trimOrNull(displayName));
    u.setPasswordHash(passwordEncoder.encode(password));
    users.save(u);

    if (role == UserRole.HELPER) {
      ensureHelperProfile(u.getId());
    }

    String accessToken = jwt.createAccessToken(u);
    String refreshToken = jwt.createRefreshToken(u);
    persistRefreshToken(u, refreshToken);
    return toAuthResponse(u, accessToken, refreshToken);
  }

  @Transactional
  public AuthResponse signupHelperWithKyc(
      HelperKycSignupRequest req,
      MultipartFile idFront,
      MultipartFile idBack,
      MultipartFile selfie) {
    String em = normalizeEmail(req.email());
    if (users.findByEmail(em).isPresent()) {
      throw new BadRequestException("Email already in use");
    }

    String normalizedPhone = normalizePhoneOrNull(req.phone());
    if (normalizedPhone != null && users.findByPhone(normalizedPhone).isPresent()) {
      throw new BadRequestException("Phone already in use");
    }

    UserEntity u = new UserEntity();
    u.setEmail(em);
    u.setPhone(normalizedPhone);
    u.setRole(UserRole.HELPER);
    u.setStatus(UserStatus.ACTIVE);
    u.setDisplayName(trimOrNull(req.displayName()));
    u.setPasswordHash(passwordEncoder.encode(req.password()));
    users.save(u);

    String frontUrl = storage.uploadHelperKycDoc(u.getId(), "id-front", idFront);
    String backUrl = storage.uploadHelperKycDoc(u.getId(), "id-back", idBack);
    String selfieUrl = storage.uploadHelperKycDoc(u.getId(), "selfie", selfie);

    HelperProfileEntity hp = ensureHelperProfile(u.getId());
    hp.setKycStatus(HelperKycStatus.PENDING);
    hp.setKycRejectionReason(null);
    hp.setKycFullName(req.fullName().trim());
    hp.setKycIdNumber(req.idNumber().trim());
    hp.setKycDocFrontUrl(frontUrl);
    hp.setKycDocBackUrl(backUrl);
    hp.setKycSelfieUrl(selfieUrl);
    hp.setKycSubmittedAt(Instant.now());
    helperProfiles.save(hp);

    String accessToken = jwt.createAccessToken(u);
    String refreshToken = jwt.createRefreshToken(u);
    persistRefreshToken(u, refreshToken);
    return toAuthResponse(u, accessToken, refreshToken);
  }

  @Transactional
  public AuthResponse loginWithPassword(String email, String password) {
    String em = normalizeEmail(email);
    UserEntity user = users.findByEmail(em).orElseThrow(() -> new BadRequestException("Invalid credentials"));
    if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
      throw new BadRequestException("Password login not enabled for this user");
    }
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new BadRequestException("Invalid credentials");
    }
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new BadRequestException("User is not active");
    }

    String accessToken = jwt.createAccessToken(user);
    String refreshToken = jwt.createRefreshToken(user);
    persistRefreshToken(user, refreshToken);
    return toAuthResponse(user, accessToken, refreshToken);
  }

  private void persistRefreshToken(UserEntity user, String refreshToken) {
    Instant now = Instant.now();
    RefreshTokenEntity rt = new RefreshTokenEntity();
    rt.setUserId(user.getId());
    rt.setTokenHash(HashUtils.sha256Hex(refreshToken));
    rt.setIssuedAt(now);
    rt.setExpiresAt(now.plusSeconds(props.jwt().refreshTtlSeconds()));
    refreshTokens.save(rt);
  }

  private HelperProfileEntity ensureHelperProfile(java.util.UUID helperId) {
    return helperProfiles.findById(helperId).orElseGet(() -> {
      HelperProfileEntity hp = new HelperProfileEntity();
      hp.setUserId(helperId);
      hp.setKycStatus(HelperKycStatus.PENDING);
      return helperProfiles.save(hp);
    });
  }

  private static String normalizeEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new BadRequestException("email is required");
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizePhoneOrNull(String phone) {
    if (phone == null) return null;
    String p = phone.trim();
    return p.isBlank() ? null : p;
  }

  private static String trimOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isBlank() ? null : t;
  }

  private static AuthResponse toAuthResponse(UserEntity user, String accessToken, String refreshToken) {
    return new AuthResponse(
        accessToken,
        refreshToken,
        new AuthResponse.User(user.getId(), user.getRole(), user.getPhone(), user.getDisplayName()));
  }
}
