package com.helpinminutes.api.auth.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.config.TwilioProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import com.twilio.Twilio;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class OtpService {
  private static final SecureRandom RNG = new SecureRandom();
  private static final Logger log = LoggerFactory.getLogger(OtpService.class);

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final TwilioProperties twilio;
  private final ConcurrentHashMap<String, LocalOtp> localFallback = new ConcurrentHashMap<>();

  public OtpService(StringRedisTemplate redis, AppProperties props, TwilioProperties twilio) {
    this.redis = redis;
    this.props = props;
    this.twilio = twilio;
  }

  public String startOtp(String phone, String channel) {
    if (twilio.enabled()) {
      Twilio.init(twilio.accountSid(), twilio.authToken());
      String chosen = normalizeChannel(channel);
      Verification.creator(twilio.verifyServiceSid(), phone, chosen).create();
      return null;
    }
    String otp = String.format("%06d", RNG.nextInt(1_000_000));
    try {
      redis.opsForValue().set(key(phone), otp, Duration.ofSeconds(props.otp().ttlSeconds()));
    } catch (Exception e) {
      log.warn("Redis OTP write failed, falling back to local cache: {}", e.getMessage());
      localFallback.put(key(phone), new LocalOtp(otp, expiresAtMs()));
    }
    return otp;
  }

  public boolean verifyOtp(String phone, String otp) {
    if (twilio.enabled()) {
      Twilio.init(twilio.accountSid(), twilio.authToken());
      VerificationCheck check = VerificationCheck.creator(twilio.verifyServiceSid()).setCode(otp).setTo(phone).create();
      return "approved".equalsIgnoreCase(check.getStatus());
    }
    String key = key(phone);
    String expected = null;
    try {
      expected = redis.opsForValue().get(key);
    } catch (Exception e) {
      log.warn("Redis OTP read failed, falling back to local cache: {}", e.getMessage());
    }
    if (expected == null) {
      LocalOtp local = localFallback.get(key);
      if (local == null || local.isExpired()) {
        localFallback.remove(key);
        return false;
      }
      expected = local.code();
    }
    boolean ok = expected.equals(otp);
    if (ok) {
      try {
        redis.delete(key);
      } catch (Exception e) {
        log.warn("Redis OTP delete failed, clearing local cache: {}", e.getMessage());
      }
      localFallback.remove(key);
    }
    return ok;
  }

  private static String normalizeChannel(String channel) {
    if (channel == null || channel.isBlank()) {
      return "sms";
    }
    String lower = channel.trim().toLowerCase(Locale.ROOT);
    if (lower.equals("call") || lower.equals("voice")) {
      return "call";
    }
    if (lower.equals("whatsapp") || lower.equals("wa")) {
      return "whatsapp";
    }
    return "sms";
  }

  private static String key(String phone) {
    return "him:otp:" + phone;
  }

  private long expiresAtMs() {
    return System.currentTimeMillis() + (props.otp().ttlSeconds() * 1000L);
  }

  private record LocalOtp(String code, long expiresAtMs) {
    boolean isExpired() {
      return System.currentTimeMillis() > expiresAtMs;
    }
  }
}
