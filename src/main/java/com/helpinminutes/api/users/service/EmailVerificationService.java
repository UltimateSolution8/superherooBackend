package com.helpinminutes.api.users.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {
  private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
  private static final SecureRandom RNG = new SecureRandom();

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final MojoAuthClient mojoAuth;
  private final JavaMailSender mailSender;
  private final Map<String, LocalState> localFallback = new ConcurrentHashMap<>();

  @Autowired
  public EmailVerificationService(
      StringRedisTemplate redis,
      AppProperties props,
      MojoAuthClient mojoAuth,
      ObjectProvider<JavaMailSender> mailSender) {
    this.redis = redis;
    this.props = props;
    this.mojoAuth = mojoAuth;
    this.mailSender = mailSender.getIfAvailable();
  }

  EmailVerificationService(
      StringRedisTemplate redis,
      AppProperties props,
      MojoAuthClient mojoAuth) {
    this.redis = redis;
    this.props = props;
    this.mojoAuth = mojoAuth;
    this.mailSender = null;
  }

  public String sendVerificationEmail(String email) {
    String normalized = normalize(email);
    if (normalized.isBlank()) throw new BadRequestException("Email is not added");

    if (mojoAuth.isConfigured()) {
      try {
        String stateId = mojoAuth.sendEmailOtp(normalized);
        if (stateId != null && !stateId.isBlank()) {
          storeState(normalized, "mojo:" + stateId);
          log.info("MojoAuth email verification started");
          return null;
        }
      } catch (Exception e) {
        log.warn("MojoAuth email OTP delivery failed; falling back to SMTP/local OTP: {}", e.getMessage());
      }
    }

    String otp = generateOtp();
    storeState(normalized, "local:" + otp);
    sendLocalOtpEmail(normalized, otp);
    return otp;
  }

  private void storeState(String email, String stateValue) {
    String key = stateKey(email);
    try {
      redis.opsForValue().set(key, stateValue, Duration.ofSeconds(props.otp().ttlSeconds()));
    } catch (Exception e) {
      log.warn("Redis email verification state write failed; using process-local fallback");
      localFallback.put(key, new LocalState(stateValue, System.currentTimeMillis() + props.otp().ttlSeconds() * 1000L));
    }
  }

  public boolean verifyEmailOtp(String email, String otp) {
    String normalized = normalize(email);
    String candidate = otp == null ? "" : otp.trim();
    if (normalized.isBlank() || !candidate.matches("\\d{4,8}")) return false;

    String key = stateKey(normalized);
    String stateValue = null;
    try {
      stateValue = redis.opsForValue().get(key);
    } catch (Exception e) {
      log.warn("Redis email verification state read failed; trying process-local fallback");
    }
    if (stateValue == null) {
      LocalState local = localFallback.get(key);
      if (local != null && local.expiresAtMillis() > System.currentTimeMillis()) stateValue = local.stateValue();
    }
    if (stateValue == null) return false;

    if (stateValue.startsWith("local:")) {
      boolean ok = stateValue.substring("local:".length()).equals(candidate);
      if (ok) deleteState(key);
      return ok;
    }
    if (!stateValue.startsWith("mojo:") || !mojoAuth.isConfigured()) return false;
    String stateId = stateValue.substring("mojo:".length());

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

  private void sendLocalOtpEmail(String email, String otp) {
    if (mailSender == null) {
      log.warn("JavaMailSender is unavailable; email verification OTP generated but not sent");
      return;
    }
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setTo(email);
      message.setSubject("Your Superherooo verification code");
      message.setText("Your Superherooo email verification code is " + otp + ". It is valid for "
          + Math.max(1, props.otp().ttlSeconds() / 60) + " minutes.");
      mailSender.send(message);
      log.info("Superherooo email verification OTP sent");
    } catch (Exception e) {
      log.warn("SMTP email OTP delivery failed; dev OTP remains available when enabled: {}", e.getMessage());
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
    return "him:email_otp:" + email;
  }

  private static String generateOtp() {
    return String.valueOf(100000 + RNG.nextInt(900000));
  }

  private record LocalState(String stateValue, long expiresAtMillis) {}
}
