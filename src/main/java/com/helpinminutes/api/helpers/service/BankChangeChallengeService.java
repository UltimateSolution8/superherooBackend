package com.helpinminutes.api.helpers.service;

import com.helpinminutes.api.auth.service.OtpService;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.ServiceUnavailableException;
import com.helpinminutes.api.helpers.dto.BankChangeChallengeResponse;
import com.helpinminutes.api.helpers.dto.BankChangeTokenResponse;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class BankChangeChallengeService {
  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final DefaultRedisScript<String> GET_AND_DELETE = new DefaultRedisScript<>(
      "local v=redis.call('GET',KEYS[1]); if v then redis.call('DEL',KEYS[1]); end; return v", String.class);

  private final StringRedisTemplate redis;
  private final UserRepository users;
  private final OtpService otp;
  private final AppProperties props;

  public BankChangeChallengeService(StringRedisTemplate redis, UserRepository users, OtpService otp, AppProperties props) {
    this.redis = redis;
    this.users = users;
    this.otp = otp;
    this.props = props;
  }

  public BankChangeChallengeResponse start(UUID userId, UserRole role) {
    var user = users.findById(userId).orElseThrow(() -> new ForbiddenException("User not found"));
    if (user.getRole() != role) throw new ForbiddenException("Bank-change role mismatch");
    String phone = user.getPhone();
    if (phone == null || phone.isBlank()) throw new BadRequestException("Add a registered phone number before changing bank details");
    UUID challengeId = UUID.randomUUID();
    String code = otp.startOtp(phone, "sms");
    try {
      String activeKey = activeChallengeKey(userId);
      String previousChallenge = redis.opsForValue().get(activeKey);
      if (previousChallenge != null) redis.delete(challengeKey(UUID.fromString(previousChallenge)));
      redis.opsForValue().set(challengeKey(challengeId), payload(userId, role, phone),
          Duration.ofSeconds(props.otp().ttlSeconds()));
      redis.opsForValue().set(activeKey, challengeId.toString(), Duration.ofSeconds(props.otp().ttlSeconds()));
    } catch (RuntimeException e) {
      throw new ServiceUnavailableException("Bank-change verification is temporarily unavailable");
    }
    return new BankChangeChallengeResponse(challengeId, maskPhone(phone),
        Instant.now().plusSeconds(props.otp().ttlSeconds()), 60,
        props.otp().returnOtpInResponse() ? code : null);
  }

  public BankChangeTokenResponse verify(UUID userId, UserRole role, UUID challengeId, String code) {
    String expected;
    try {
      expected = redis.opsForValue().get(challengeKey(challengeId));
    } catch (RuntimeException e) {
      throw new ServiceUnavailableException("Bank-change verification is temporarily unavailable");
    }
    if (expected == null || !expected.startsWith(userId + "|" + role.name() + "|")) {
      throw new BadRequestException("Bank-change verification has expired");
    }
    String phone = expected.substring(expected.lastIndexOf('|') + 1);
    if (!otp.verifyOtp(phone, code)) throw new BadRequestException("Invalid verification code");
    String token = randomToken();
    try {
      redis.delete(challengeKey(challengeId));
      redis.delete(activeChallengeKey(userId));
      redis.opsForValue().set(tokenKey(token), payload(userId, role, "verified"), TOKEN_TTL);
    } catch (RuntimeException e) {
      throw new ServiceUnavailableException("Bank-change verification is temporarily unavailable");
    }
    return new BankChangeTokenResponse(token, Instant.now().plus(TOKEN_TTL));
  }

  public void consume(UUID userId, UserRole role, String token) {
    String value;
    try {
      value = redis.execute(GET_AND_DELETE, List.of(tokenKey(token)));
    } catch (RuntimeException e) {
      throw new ServiceUnavailableException("Bank-change verification is temporarily unavailable");
    }
    if (!payload(userId, role, "verified").equals(value)) {
      throw new ForbiddenException("Fresh phone verification is required to change bank details");
    }
  }

  private static String payload(UUID userId, UserRole role, String value) { return userId + "|" + role.name() + "|" + value; }
  private static String challengeKey(UUID id) { return "him:bank-change:challenge:" + id; }
  private static String activeChallengeKey(UUID userId) { return "him:bank-change:active:" + userId; }
  private static String tokenKey(String token) { return "him:bank-change:token:" + token; }
  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
  private static String maskPhone(String phone) {
    String digits = phone.replaceAll("\\D", "");
    return digits.length() <= 4 ? "••••" : "••••••" + digits.substring(digits.length() - 4);
  }
}
