package com.helpinminutes.api.users.service;

import com.helpinminutes.api.config.AppProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {
  private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
  private static final SecureRandom RNG = new SecureRandom();

  private final JavaMailSender mailSender;
  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final ConcurrentHashMap<String, LocalOtp> localFallback = new ConcurrentHashMap<>();

  public EmailVerificationService(JavaMailSender mailSender, StringRedisTemplate redis, AppProperties props) {
    this.mailSender = mailSender;
    this.redis = redis;
    this.props = props;
  }

  public String sendVerificationEmail(String email) {
    String otp = String.format("%06d", RNG.nextInt(1_000_000));
    String key = "him:email_otp:" + email.toLowerCase().trim();
    
    // Store in Redis or fallback local map
    try {
      redis.opsForValue().set(key, otp, Duration.ofSeconds(props.otp().ttlSeconds()));
    } catch (Exception e) {
      log.warn("Redis Email OTP write failed, falling back to local cache: {}", e.getMessage());
      localFallback.put(key, new LocalOtp(otp, System.currentTimeMillis() + (props.otp().ttlSeconds() * 1000L)));
    }

    // Send the real email
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom("superheroooprivatelimited@gmail.com");
      message.setTo(email);
      message.setSubject("Superherooo - Email Verification Code");
      message.setText("Your email verification code is: " + otp + "\nIt is valid for " 
          + (props.otp().ttlSeconds() / 60) + " minutes.");
      mailSender.send(message);
      log.info("Verification email sent successfully to {}", email);
    } catch (Exception e) {
      log.error("Failed to send verification email to {}: {}", email, e.getMessage());
      // Even if SMTP fails, we want the dev flow to keep going, but log the failure.
    }

    return otp;
  }

  public boolean verifyEmailOtp(String email, String otp) {
    String key = "him:email_otp:" + email.toLowerCase().trim();
    String expected = null;
    
    try {
      expected = redis.opsForValue().get(key);
    } catch (Exception e) {
      log.warn("Redis Email OTP read failed, falling back to local cache: {}", e.getMessage());
    }

    if (expected == null) {
      LocalOtp local = localFallback.get(key);
      if (local != null && !local.isExpired()) {
        expected = local.code();
      } else if (local != null) {
        localFallback.remove(key);
      }
    }

    if (expected != null && expected.equals(otp.trim())) {
      try {
        redis.delete(key);
      } catch (Exception e) {
        log.warn("Redis Email OTP delete failed: {}", e.getMessage());
      }
      localFallback.remove(key);
      return true;
    }

    return false;
  }

  private record LocalOtp(String code, long expiresAtMs) {
    boolean isExpired() {
      return System.currentTimeMillis() > expiresAtMs;
    }
  }
}
