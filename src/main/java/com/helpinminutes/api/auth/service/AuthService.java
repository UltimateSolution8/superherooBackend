package com.helpinminutes.api.auth.service;

import com.helpinminutes.api.auth.dto.AuthResponse;
import com.helpinminutes.api.auth.dto.HelperKycSignupRequest;
import com.helpinminutes.api.auth.model.RefreshTokenEntity;
import com.helpinminutes.api.auth.repo.RefreshTokenRepository;
import com.helpinminutes.api.common.HashUtils;
import com.helpinminutes.api.common.InputValidators;
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
import com.helpinminutes.api.users.service.EmailVerificationService;
import com.helpinminutes.api.mediator.model.HelperMediatorLinkEntity;
import com.helpinminutes.api.mediator.repo.HelperMediatorLinkRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private final AppProperties props;
  private final OtpService otp;
  private final UserRepository users;
  private final HelperProfileRepository helperProfiles;
  private final JwtService jwt;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordEncoder passwordEncoder;
  private final SupabaseStorageService storage;
  private final HelperMediatorLinkRepository helperMediatorLinks;
  private final EmailVerificationService emailVerificationService;

  public AuthService(
      AppProperties props,
      OtpService otp,
      UserRepository users,
      HelperProfileRepository helperProfiles,
      JwtService jwt,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      SupabaseStorageService storage,
      HelperMediatorLinkRepository helperMediatorLinks,
      EmailVerificationService emailVerificationService) {
    this.props = props;
    this.otp = otp;
    this.users = users;
    this.helperProfiles = helperProfiles;
    this.jwt = jwt;
    this.refreshTokens = refreshTokens;
    this.passwordEncoder = passwordEncoder;
    this.storage = storage;
    this.helperMediatorLinks = helperMediatorLinks;
    this.emailVerificationService = emailVerificationService;
  }

  AuthService(
      AppProperties props,
      OtpService otp,
      UserRepository users,
      HelperProfileRepository helperProfiles,
      JwtService jwt,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      SupabaseStorageService storage,
      HelperMediatorLinkRepository helperMediatorLinks) {
    this(props, otp, users, helperProfiles, jwt, refreshTokens, passwordEncoder, storage,
        helperMediatorLinks, null);
  }

  AuthService(
      AppProperties props,
      OtpService otp,
      UserRepository users,
      HelperProfileRepository helperProfiles,
      JwtService jwt,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      SupabaseStorageService storage) {
    this(props, otp, users, helperProfiles, jwt, refreshTokens, passwordEncoder, storage,
        null, null);
  }

  public String startOtp(String phone, String channel) {
    boolean isReviewer = "9999999991".equals(phone) || "9999999992".equals(phone) || "9999999993".equals(phone);
    if (isReviewer) {
      return "123456";
    }
    return otp.startOtp(phone, channel);
  }

  @Transactional
  public AuthResponse verifyOtp(String phone, String otpCode, UserRole role) {
    boolean isReviewer = "9999999991".equals(phone) || "9999999992".equals(phone) || "9999999993".equals(phone);
    if (!isReviewer && !otp.verifyOtp(phone, otpCode)) {
      throw new BadRequestException("Invalid OTP");
    }

    UserEntity user = users.findByPhoneAndRole(phone, role).orElseGet(() -> {
      if (!isReviewer) {
        if (role == UserRole.ADMIN) {
          String bootstrapAdminPhone = System.getenv("BOOTSTRAP_ADMIN_PHONE");
          if (bootstrapAdminPhone == null || !bootstrapAdminPhone.equals(phone)) {
            throw new BadRequestException("Admin signup is disabled");
          }
        }
        if (role == UserRole.KYC || role == UserRole.SUPPORT || role == UserRole.MEDIATOR) {
          throw new BadRequestException("This account must be created by an admin before login");
        }
      }

      UserEntity u = new UserEntity();
      u.setPhone(phone);
      u.setRole(role);
      u.setStatus(UserStatus.ACTIVE);
      if (isReviewer) {
        if (role == UserRole.BUYER) u.setDisplayName("Reviewer Buyer");
        if (role == UserRole.HELPER) u.setDisplayName("Reviewer Helper");
        if (role == UserRole.MEDIATOR) u.setDisplayName("Reviewer Mediator");
      }
      users.save(u);

      if (role == UserRole.HELPER) {
        HelperProfileEntity hp = new HelperProfileEntity();
        hp.setUserId(u.getId());
        if (isReviewer) {
          hp.setKycStatus(HelperKycStatus.APPROVED);
        }
        helperProfiles.save(hp);
      }

      return u;
    });

    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new BadRequestException("User is not active");
    }

    if (isReviewer && role == UserRole.HELPER) {
      HelperProfileEntity hp = helperProfiles.findById(user.getId()).orElseGet(() -> {
        HelperProfileEntity newHp = new HelperProfileEntity();
        newHp.setUserId(user.getId());
        return newHp;
      });
      hp.setKycStatus(HelperKycStatus.APPROVED);
      helperProfiles.save(hp);
    }

    String accessToken = jwt.createAccessToken(user);
    String refreshToken = jwt.createRefreshToken(user);

    persistRefreshToken(user, refreshToken);

    return toAuthResponse(user, accessToken, refreshToken);
  }

  @jakarta.annotation.PostConstruct
  @Transactional
  public void initReviewerAccounts() {
    try {
      log.info("Initializing Google Play reviewer accounts...");

      // 1. Setup Buyer
      UserEntity buyer = users.findByPhoneAndRole("9999999991", UserRole.BUYER).orElseGet(() -> {
        UserEntity u = new UserEntity();
        u.setPhone("9999999991");
        u.setRole(UserRole.BUYER);
        u.setStatus(UserStatus.ACTIVE);
        u.setDisplayName("Reviewer Buyer");
        return users.save(u);
      });

      // 2. Setup Helper
      UserEntity helper = users.findByPhoneAndRole("9999999992", UserRole.HELPER).orElseGet(() -> {
        UserEntity u = new UserEntity();
        u.setPhone("9999999992");
        u.setRole(UserRole.HELPER);
        u.setStatus(UserStatus.ACTIVE);
        u.setDisplayName("Reviewer Helper");
        return users.save(u);
      });

      HelperProfileEntity hp = helperProfiles.findById(helper.getId()).orElseGet(() -> {
        HelperProfileEntity newHp = new HelperProfileEntity();
        newHp.setUserId(helper.getId());
        return newHp;
      });
      hp.setKycStatus(HelperKycStatus.APPROVED);
      helperProfiles.save(hp);

      // 3. Setup Mediator
      UserEntity mediator = users.findByPhoneAndRole("9999999993", UserRole.MEDIATOR).orElseGet(() -> {
        UserEntity u = new UserEntity();
        u.setPhone("9999999993");
        u.setRole(UserRole.MEDIATOR);
        u.setStatus(UserStatus.ACTIVE);
        u.setDisplayName("Reviewer Mediator");
        return users.save(u);
      });

      // 4. Link Helper and Mediator
      HelperMediatorLinkEntity link = helperMediatorLinks.findByHelperIdAndMediatorId(helper.getId(), mediator.getId())
          .orElseGet(HelperMediatorLinkEntity::new);
      link.setHelperId(helper.getId());
      link.setMediatorId(mediator.getId());
      link.setStatus("ACTIVE");
      link.setCreatedBy("HELPER");
      helperMediatorLinks.save(link);

      log.info("Reviewer accounts initialized, KYC approved, and linked successfully!");
    } catch (Exception e) {
      log.error("Failed to initialize reviewer accounts", e);
    }
  }

  @Transactional
  public AuthResponse refresh(String refreshToken) {
    UserPrincipal subject = jwt.parseRefreshToken(refreshToken);
    String hash = HashUtils.sha256Hex(refreshToken);
    Instant now = Instant.now();

    List<RefreshTokenEntity> activeMatches = refreshTokens.findAllActiveByHash(hash, now);
    if (activeMatches.isEmpty()) {
      throw new BadRequestException("Refresh token invalid");
    }
    RefreshTokenEntity existing = activeMatches.get(0);
    if (!existing.getUserId().equals(subject.userId())) {
      throw new BadRequestException("Refresh token invalid");
    }

    // Rotate and aggressively clean up any duplicate rows for the same token hash.
    refreshTokens.revokeAllByHash(hash, now);

    UserEntity user = users.findById(subject.userId())
        .orElseThrow(() -> new BadRequestException("User not found"));

    String newAccess = jwt.createAccessToken(user);
    String newRefresh = jwt.createRefreshToken(user);
    persistRefreshToken(user, newRefresh);

    return toAuthResponse(user, newAccess, newRefresh);
  }

  @Transactional
  public AuthResponse signupWithPassword(String email, String password, String phone, String displayName, UserRole role) {
    if (role == UserRole.ADMIN || role == UserRole.KYC || role == UserRole.SUPPORT || role == UserRole.MEDIATOR) {
      throw new BadRequestException("This account must be created by an admin");
    }
    String em = InputValidators.requireEmail(email);
    if (users.findByEmail(em).isPresent()) {
      throw new BadRequestException("Email already in use");
    }

    String normalizedPhone = InputValidators.normalizeIndianPhoneOrNull(phone);
    if (normalizedPhone != null && users.findByPhoneAndRole(normalizedPhone, role).isPresent()) {
      throw new BadRequestException("Phone already in use");
    }

    UserEntity u = new UserEntity();
    u.setEmail(em);
    u.setEmailVerified(false);
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

  public String startEmailOtp(String email) {
    String em = InputValidators.requireEmail(email, false);
    users.findByEmail(em).orElseThrow(() -> new BadRequestException("Account not found"));
    return emailVerificationService.sendVerificationEmail(em);
  }

  @Transactional
  public AuthResponse verifyEmailOtp(String email, String otpCode) {
    String em = InputValidators.requireEmail(email, false);
    UserEntity user = users.findByEmail(em).orElseThrow(() -> new BadRequestException("Account not found"));
    if (!emailVerificationService.verifyEmailOtp(em, otpCode)) {
      throw new BadRequestException("Invalid verification code");
    }
    user.setEmailVerified(true);
    users.save(user);

    String accessToken = jwt.createAccessToken(user);
    String refreshToken = jwt.createRefreshToken(user);
    persistRefreshToken(user, refreshToken);
    return toAuthResponse(user, accessToken, refreshToken);
  }

  @Transactional
  public AuthResponse signupHelperWithKyc(
      HelperKycSignupRequest req,
      MultipartFile idFront,
      MultipartFile idBack,
      MultipartFile selfie) {
    String em = InputValidators.requireEmail(req.email());
    if (users.findByEmail(em).isPresent()) {
      throw new BadRequestException("Email already in use");
    }

    String normalizedPhone = InputValidators.normalizeIndianPhoneOrNull(req.phone());
    if (normalizedPhone != null && users.findByPhoneAndRole(normalizedPhone, UserRole.HELPER).isPresent()) {
      throw new BadRequestException("Phone already in use");
    }

    UserEntity u = new UserEntity();
    u.setEmail(em);
    u.setEmailVerified(false);
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
    String em = InputValidators.requireEmail(email, false);
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
    try {
      refreshTokens.save(rt);
    } catch (Exception ex) {
      log.warn("Failed to persist refresh token for user {}: {}", user.getId(), ex.getMessage());
    }
  }

  private HelperProfileEntity ensureHelperProfile(java.util.UUID helperId) {
    return helperProfiles.findById(helperId).orElseGet(() -> {
      HelperProfileEntity hp = new HelperProfileEntity();
      hp.setUserId(helperId);
      hp.setKycStatus(HelperKycStatus.PENDING);
      return helperProfiles.save(hp);
    });
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
        new AuthResponse.User(
            user.getId(),
            user.getRole(),
            user.getPhone(),
            user.getEmail(),
            user.isEmailVerified(),
            user.getDisplayName(),
            user.isBulkCsvEnabled()));
  }
}
