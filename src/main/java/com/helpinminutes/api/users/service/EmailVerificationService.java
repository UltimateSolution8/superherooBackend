package com.helpinminutes.api.users.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ServiceUnavailableException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {
  private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int MAX_VERIFY_ATTEMPTS = 5;

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final JavaMailSender mailSender;
  private final Map<String, LocalOtp> localFallback = new ConcurrentHashMap<>();

  @Value("${spring.mail.username:}")
  private String fromAddress;

  public EmailVerificationService(
      StringRedisTemplate redis,
      AppProperties props,
      JavaMailSender mailSender) {
    this.redis = redis;
    this.props = props;
    this.mailSender = mailSender;
  }

  public String sendVerificationEmail(String email) {
    String normalized = email == null ? "" : email.trim().toLowerCase();
    if (normalized.isBlank()) throw new BadRequestException("Email is not added");
    if (fromAddress == null || fromAddress.isBlank()) {
      throw new ServiceUnavailableException("Email verification is temporarily unavailable");
    }

    String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
    String hash = BCrypt.hashpw(otp, BCrypt.gensalt(10));
    long ttlSeconds = props.otp().ttlSeconds();
    String otpKey = otpKey(normalized);
    String attemptsKey = attemptsKey(normalized);

    try {
      redis.opsForValue().set(otpKey, hash, Duration.ofSeconds(ttlSeconds));
      redis.delete(attemptsKey);
    } catch (Exception e) {
      log.warn("Redis email OTP write failed; using process-local fallback");
      localFallback.put(normalized, new LocalOtp(hash, System.currentTimeMillis() + ttlSeconds * 1000L, 0));
    }

    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(fromAddress.trim());
      message.setTo(normalized);
      message.setSubject("Your Superherooo verification code");
      message.setText("Your Superherooo email verification code is " + otp
          + ". It expires in " + Math.max(1, ttlSeconds / 60) + " minutes.\n\n"
          + "Do not share this code with anyone.");
      mailSender.send(message);
      log.info("Email verification code sent successfully");
    } catch (Exception e) {
      deleteOtp(normalized);
      log.error("Email verification delivery failed", e);
      throw new ServiceUnavailableException("Could not send verification email. Please try again.");
    }

    return props.otp().returnOtpInResponse() ? otp : null;
  }

  public boolean verifyEmailOtp(String email, String otp) {
    String normalized = email == null ? "" : email.trim().toLowerCase();
    String candidate = otp == null ? "" : otp.trim();
    if (normalized.isBlank() || !candidate.matches("\\d{6}")) return false;

    String hash = null;
    int attempts = 0;
    try {
      hash = redis.opsForValue().get(otpKey(normalized));
      String rawAttempts = redis.opsForValue().get(attemptsKey(normalized));
      attempts = rawAttempts == null ? 0 : Integer.parseInt(rawAttempts);
    } catch (Exception e) {
      LocalOtp local = localFallback.get(normalized);
      if (local != null && local.expiresAtMillis() > System.currentTimeMillis()) {
        hash = local.hash();
        attempts = local.attempts();
      }
    }

    if (hash == null || attempts >= MAX_VERIFY_ATTEMPTS) {
      deleteOtp(normalized);
      return false;
    }
    if (!BCrypt.checkpw(candidate, hash)) {
      recordFailedAttempt(normalized, hash, attempts + 1);
      return false;
    }

    deleteOtp(normalized);
    return true;
  }

  private void recordFailedAttempt(String email, String hash, int attempts) {
    try {
      redis.opsForValue().set(attemptsKey(email), String.valueOf(attempts),
          Duration.ofSeconds(props.otp().ttlSeconds()));
    } catch (Exception e) {
      LocalOtp current = localFallback.get(email);
      long expiresAt = current == null
          ? System.currentTimeMillis() + props.otp().ttlSeconds() * 1000L
          : current.expiresAtMillis();
      localFallback.put(email, new LocalOtp(hash, expiresAt, attempts));
    }
  }

  private void deleteOtp(String email) {
    try {
      redis.delete(otpKey(email));
      redis.delete(attemptsKey(email));
    } catch (Exception ignored) {
      // Local cleanup still runs below.
    }
    localFallback.remove(email);
  }

  private static String otpKey(String email) {
    return "him:email_otp:" + email;
  }

  private static String attemptsKey(String email) {
    return "him:email_otp_attempts:" + email;
  }

  private record LocalOtp(String hash, long expiresAtMillis, int attempts) {}
}
