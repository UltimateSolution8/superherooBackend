package com.helpinminutes.api.auth.service;

import com.helpinminutes.api.config.AppProperties;
import java.security.SecureRandom;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class OtpService {
  private static final SecureRandom RNG = new SecureRandom();

  private final StringRedisTemplate redis;
  private final AppProperties props;

  public OtpService(StringRedisTemplate redis, AppProperties props) {
    this.redis = redis;
    this.props = props;
  }

  public String startOtp(String phone) {
    String otp = String.format("%06d", RNG.nextInt(1_000_000));
    redis.opsForValue().set(key(phone), otp, Duration.ofSeconds(props.otp().ttlSeconds()));

    // In production we would send via SMS provider here.
    return otp;
  }

  public boolean verifyOtp(String phone, String otp) {
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

  private static String key(String phone) {
    return "him:otp:" + phone;
  }
}
