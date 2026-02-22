package com.helpinminutes.api.auth.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.config.TwilioProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import com.twilio.Twilio;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class OtpService {
  private static final SecureRandom RNG = new SecureRandom();

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final TwilioProperties twilio;

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
    redis.opsForValue().set(key(phone), otp, Duration.ofSeconds(props.otp().ttlSeconds()));
    return otp;
  }

  public boolean verifyOtp(String phone, String otp) {
    if (twilio.enabled()) {
      Twilio.init(twilio.accountSid(), twilio.authToken());
      VerificationCheck check = VerificationCheck.creator(twilio.verifyServiceSid(), otp).setTo(phone).create();
      return "approved".equalsIgnoreCase(check.getStatus());
    }
    String key = key(phone);
    String expected = redis.opsForValue().get(key);
    if (expected == null) {
      return false;
    }
    boolean ok = expected.equals(otp);
    if (ok) {
      redis.delete(key);
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
}
