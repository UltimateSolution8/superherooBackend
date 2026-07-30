package com.helpinminutes.api.users.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.users.service.email.EmailOtpDispatch;
import com.helpinminutes.api.users.service.email.EmailOtpSender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies email one-time passcodes.
 *
 * Providers are tried in {@code @Order} sequence (MojoAuth, then SMTP) so a
 * new provider such as Amazon SES can be slotted in without touching this class
 * — see {@link EmailOtpSender}.
 *
 * State is stored as {@code <providerId>:<state>} under a purpose-scoped Redis
 * key. Scoping by purpose matters: without it a code issued to verify an email
 * address could be replayed to reset that account's password.
 */
@Service
public class EmailVerificationService {
  private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

  /** A 6-digit code is brute-forceable without a cap on guesses. */
  private static final int MAX_VERIFY_ATTEMPTS = 5;

  public enum Purpose {
    /** Confirming ownership of an address. Key kept as-is for backward compatibility. */
    VERIFY_EMAIL("him:email_otp:"),
    /** Authorising a password reset. */
    PASSWORD_RESET("him:pwd_reset_otp:");

    private final String keyPrefix;

    Purpose(String keyPrefix) {
      this.keyPrefix = keyPrefix;
    }
  }

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final List<EmailOtpSender> senders;
  private final Map<String, LocalState> localFallback = new ConcurrentHashMap<>();

  public EmailVerificationService(
      StringRedisTemplate redis, AppProperties props, List<EmailOtpSender> senders) {
    this.redis = redis;
    this.props = props;
    this.senders = senders;
  }

  /**
   * @return the plaintext code when the active provider verifies locally, so the
   *     controller can echo it under {@code app.otp.returnOtpInResponse} in
   *     local development. {@code null} for delegated providers.
   */
  public String sendVerificationEmail(String email) {
    return send(email, Purpose.VERIFY_EMAIL);
  }

  public String sendPasswordResetEmail(String email) {
    return send(email, Purpose.PASSWORD_RESET);
  }

  public boolean verifyEmailOtp(String email, String otp) {
    return verify(email, otp, Purpose.VERIFY_EMAIL);
  }

  public boolean verifyPasswordResetOtp(String email, String otp) {
    return verify(email, otp, Purpose.PASSWORD_RESET);
  }

  private String send(String email, Purpose purpose) {
    String normalized = normalize(email);
    if (normalized.isBlank()) throw new BadRequestException("Email is not added");

    for (EmailOtpSender sender : senders) {
      if (!sender.isConfigured()) continue;
      EmailOtpDispatch dispatch = sender.send(normalized);
      if (dispatch == null) continue; // provider failed; try the next one
      storeState(normalized, purpose, sender.providerId() + ":" + dispatch.state());
      resetAttempts(normalized, purpose);
      log.info("Email OTP issued provider={} purpose={}", sender.providerId(), purpose);
      return dispatch.plaintextOtp();
    }

    // Unreachable in practice: SmtpEmailOtpSender is always "configured".
    throw new BadRequestException("Email delivery is temporarily unavailable. Please try again.");
  }

  private boolean verify(String email, String otp, Purpose purpose) {
    String normalized = normalize(email);
    String candidate = otp == null ? "" : otp.trim();
    if (normalized.isBlank() || !candidate.matches("\\d{4,8}")) return false;

    String key = stateKey(normalized, purpose);
    String stateValue = readState(key);
    if (stateValue == null) return false;

    if (registerAttempt(normalized, purpose) > MAX_VERIFY_ATTEMPTS) {
      // Burn the code rather than let an attacker keep guessing.
      deleteState(key);
      resetAttempts(normalized, purpose);
      log.warn("Email OTP invalidated after {} failed attempts purpose={}", MAX_VERIFY_ATTEMPTS, purpose);
      return false;
    }

    int split = stateValue.indexOf(':');
    if (split <= 0) return false;
    String providerId = stateValue.substring(0, split);
    String state = stateValue.substring(split + 1);

    for (EmailOtpSender sender : senders) {
      if (!sender.providerId().equals(providerId)) continue;
      if (!sender.verify(normalized, state, candidate)) return false;
      deleteState(key);
      resetAttempts(normalized, purpose);
      log.info("Email OTP verified provider={} purpose={}", providerId, purpose);
      return true;
    }

    log.warn("No email OTP provider registered for stored provider id '{}'", providerId);
    return false;
  }

  private void storeState(String email, Purpose purpose, String stateValue) {
    String key = stateKey(email, purpose);
    long ttl = props.otp().ttlSeconds();
    try {
      redis.opsForValue().set(key, stateValue, Duration.ofSeconds(ttl));
    } catch (Exception e) {
      log.warn("Redis email OTP state write failed; using process-local fallback");
      localFallback.put(key, new LocalState(stateValue, System.currentTimeMillis() + ttl * 1000L));
    }
  }

  private String readState(String key) {
    try {
      String value = redis.opsForValue().get(key);
      if (value != null) return value;
    } catch (Exception e) {
      log.warn("Redis email OTP state read failed; trying process-local fallback");
    }
    LocalState local = localFallback.get(key);
    if (local != null && local.expiresAtMillis() > System.currentTimeMillis()) {
      return local.stateValue();
    }
    return null;
  }

  /** @return the attempt number just consumed, starting at 1. */
  private long registerAttempt(String email, Purpose purpose) {
    String key = attemptsKey(email, purpose);
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redis.expire(key, Duration.ofSeconds(props.otp().ttlSeconds()));
      }
      return count == null ? 1L : count;
    } catch (Exception e) {
      // Redis unavailable: do not lock the user out on infrastructure trouble.
      log.warn("Redis OTP attempt counter unavailable: {}", e.getMessage());
      return 1L;
    }
  }

  private void resetAttempts(String email, Purpose purpose) {
    try {
      redis.delete(attemptsKey(email, purpose));
    } catch (Exception ignored) {
      // Counter expires on its own TTL.
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

  private static String stateKey(String email, Purpose purpose) {
    return purpose.keyPrefix + email;
  }

  private static String attemptsKey(String email, Purpose purpose) {
    return purpose.keyPrefix + "attempts:" + email;
  }

  private record LocalState(String stateValue, long expiresAtMillis) {}
}
