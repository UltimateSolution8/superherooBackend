package com.helpinminutes.api.users.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ServiceUnavailableException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {
  private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final MojoAuthClient mojoAuth;
  private final Map<String, LocalState> localFallback = new ConcurrentHashMap<>();

  public EmailVerificationService(
      StringRedisTemplate redis,
      AppProperties props,
      MojoAuthClient mojoAuth) {
    this.redis = redis;
    this.props = props;
    this.mojoAuth = mojoAuth;
  }

  public String sendVerificationEmail(String email) {
    String normalized = normalize(email);
    if (normalized.isBlank()) throw new BadRequestException("Email is not added");
    if (!mojoAuth.isConfigured()) {
      throw new ServiceUnavailableException("Email verification is temporarily unavailable");
    }

    String stateId;
    try {
      stateId = mojoAuth.sendEmailOtp(normalized);
    } catch (Exception e) {
      log.warn("MojoAuth email OTP delivery failed: {}", e.getMessage());
      throw new ServiceUnavailableException("Could not send verification email. Please try again.");
    }
    if (stateId == null || stateId.isBlank()) {
      throw new ServiceUnavailableException("Could not send verification email. Please try again.");
    }

    String key = stateKey(normalized);
    long ttlSeconds = props.otp().ttlSeconds();
    try {
      redis.opsForValue().set(key, stateId, Duration.ofSeconds(ttlSeconds));
    } catch (Exception e) {
      log.warn("Redis email verification state write failed; using process-local fallback");
      localFallback.put(key, new LocalState(stateId, System.currentTimeMillis() + ttlSeconds * 1000L));
    }
    log.info("MojoAuth email verification started");
    return null;
  }

  public boolean verifyEmailOtp(String email, String otp) {
    String normalized = normalize(email);
    String candidate = otp == null ? "" : otp.trim();
    if (normalized.isBlank() || !candidate.matches("\\d{4,8}")) return false;

    String key = stateKey(normalized);
    String stateId = null;
    try {
      stateId = redis.opsForValue().get(key);
    } catch (Exception e) {
      log.warn("Redis email verification state read failed; trying process-local fallback");
    }
    if (stateId == null) {
      LocalState local = localFallback.get(key);
      if (local != null && local.expiresAtMillis() > System.currentTimeMillis()) stateId = local.stateId();
    }
    if (stateId == null || !mojoAuth.isConfigured()) return false;

    try {
      if (!mojoAuth.verifyEmailOtp(stateId, candidate)) return false;
      deleteState(key);
      log.info("MojoAuth email verification completed");
      return true;
    } catch (Exception e) {
      log.warn("MojoAuth email OTP verification failed: {}", e.getMessage());
      return false;
    }
  }

  private void deleteState(String key) {
    try {
      redis.delete(key);
    } catch (Exception ignored) {
      // Process-local cleanup still runs.
    }
    localFallback.remove(key);
  }

  private static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase();
  }

  private static String stateKey(String email) {
    return "him:mojo_state_id:" + email;
  }

  private record LocalState(String stateId, long expiresAtMillis) {}
}
