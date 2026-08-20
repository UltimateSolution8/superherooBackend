package com.helpinminutes.api.auth.service;

import com.helpinminutes.api.common.LogMasking;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.config.ExotelProperties;
import com.helpinminutes.api.config.Msg91Properties;
import com.helpinminutes.api.config.ReviewerPhoneProperties;
import com.helpinminutes.api.config.TwilioProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.users.model.UserRole;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.nio.charset.StandardCharsets;
import com.twilio.Twilio;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class OtpService {
  private static final SecureRandom RNG = new SecureRandom();
  private static final Logger log = LoggerFactory.getLogger(OtpService.class);
  /** Guesses allowed against a single issued code before it is burned. */
  private static final int MAX_VERIFY_ATTEMPTS = 5;

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final TwilioProperties twilio;
  private final ExotelProperties exotel;
  private final Msg91Properties msg91;
  private final ReviewerPhoneProperties reviewerPhones;
  private final Executor otpDeliveryExecutor;
  private final ConcurrentHashMap<String, LocalOtp> localFallback = new ConcurrentHashMap<>();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public OtpService(
      StringRedisTemplate redis,
      AppProperties props,
      TwilioProperties twilio,
      ExotelProperties exotel,
      Msg91Properties msg91,
      ReviewerPhoneProperties reviewerPhones,
      @Qualifier("otpDeliveryExecutor") Executor otpDeliveryExecutor) {
    this.redis = redis;
    this.props = props;
    this.twilio = twilio;
    this.exotel = exotel;
    this.msg91 = msg91;
    this.reviewerPhones = reviewerPhones;
    this.otpDeliveryExecutor = otpDeliveryExecutor;
  }

  public String startOtp(String phone, String channel) {
    return startOtp(phone, channel, null, null);
  }

  public String startOtp(String phone, String channel, String appHash, UserRole role) {
    if (phone == null || phone.isBlank()) {
      throw new BadRequestException("Phone number is required");
    }

    // Redis rate limiting: Max 5 OTPs per phone number per hour
    String rateKey = "him:otp_rate:" + phone.trim();
    try {
      Long currentCount = redis.opsForValue().increment(rateKey);
      if (currentCount != null) {
        if (currentCount == 1) {
          redis.expire(rateKey, Duration.ofHours(1));
        }
        if (currentCount > 5) {
          throw new BadRequestException("Too many OTP requests. Please try again after an hour.");
        }
      }
    } catch (BadRequestException e) {
      throw e;
    } catch (Exception e) {
      log.warn("Redis OTP rate limiting failed for {}: {}", LogMasking.phone(phone), e.getMessage());
    }

    // A provisioned code for an allowlisted number. Stored exactly where a random
    // one would be, so verification cannot tell the difference — see
    // ReviewerPhoneProperties for why this exists and what it does not grant.
    String provisioned = reviewerPhones == null ? null : reviewerPhones.codeFor(phone);
    if (provisioned != null) {
      storeOtp(phone, provisioned);
      log.warn("Provisioned reviewer OTP issued for {} — no SMS sent", LogMasking.phone(phone));
      return provisioned;
    }

    // Selected on canSendSms rather than enabled: a provider that is switched on but
    // has no credentials cannot deliver anything, and taking its branch anyway meant
    // a configured fallback was skipped in favour of an OTP nobody would ever see.
    if (msg91 != null && msg91.canSendSms()) {
      String otp = createAndStoreLocalOtp(phone);
      try {
        // OTP generation must not wait on an external SMS gateway.
        otpDeliveryExecutor.execute(() -> sendMsg91Otp(phone, otp, appHash, role));
      } catch (RejectedExecutionException e) {
        log.warn("MSG91 OTP delivery queue is full for {}; the code was not sent.", LogMasking.phone(phone));
      }
      return otp;
    }
    if (msg91 != null && msg91.enabled()) {
      log.warn("MSG91 is enabled but authKey or templateId are missing — falling through to the next provider.");
    }

    if (exotel != null && exotel.canSendSms()) {
      String otp = createAndStoreLocalOtp(phone);
      try {
        otpDeliveryExecutor.execute(() -> sendExotelOtp(phone, otp));
      } catch (RejectedExecutionException e) {
        log.warn("Exotel OTP delivery queue is full for {}; the code was not sent.", LogMasking.phone(phone));
      }
      return otp;
    }

    String recipient = toTwilioRecipient(phone);
    if (twilio.enabled()) {
      String localOtp = createAndStoreLocalOtp(phone);
      try {
        String chosen = normalizeChannel(channel);
        otpDeliveryExecutor.execute(() -> {
          try {
            Twilio.init(twilio.accountSid(), twilio.authToken());
            Verification.creator(twilio.verifyServiceSid(), recipient, chosen).create();
            log.info("Twilio OTP request sent asynchronously for {}", LogMasking.phone(recipient));
          } catch (Exception ex) {
            log.warn("Twilio OTP async start failed for {}: {}", LogMasking.phone(recipient), ex.getMessage());
          }
        });
      } catch (Exception e) {
        log.warn("Failed to queue Twilio OTP request for {}: {}", LogMasking.phone(recipient), e.getMessage());
      }
      return localOtp;
    }
    return createAndStoreLocalOtp(phone);
  }

  private void sendMsg91Otp(String phone, String otp, String appHash, UserRole role) {
    try {
      String to = toIndiaRecipient(phone);
      int expiryMin = msg91.normalizedOtpExpiryMinutes();
      String resolvedHash = org.springframework.util.StringUtils.hasText(appHash) ? appHash.trim() : resolveDefaultAppHash(role);
      String templateId = msg91.templateId() != null ? msg91.templateId().trim() : "";
      String authKey = msg91.authKey() != null ? msg91.authKey().trim() : "";

      String url = "https://control.msg91.com/api/v5/flow";
      String jsonBody = "{"
          + "\"template_id\":\"" + encJson(templateId) + "\","
          + "\"short_url\":\"0\","
          + "\"recipients\":[{"
          + "\"mobiles\":\"" + encJson(to) + "\","
          + "\"var1\":\"" + encJson(otp) + "\","
          + "\"var2\":\"" + expiryMin + "\","
          + "\"var3\":\"" + encJson(resolvedHash) + "\""
          + "}]"
          + "}";

      HttpRequest req = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/json")
          .header("authkey", authKey)
          .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
          .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      String body = res.body();
      if (res.statusCode() < 200 || res.statusCode() >= 300 || (body != null && body.contains("\"type\":\"error\""))) {
        log.warn("MSG91 OTP SMS failed for {} status={} body={}", LogMasking.phone(to), res.statusCode(), safeLogBody(body));
      } else {
        log.info("MSG91 OTP SMS sent successfully for {} body={}", LogMasking.phone(to), safeLogBody(body));
      }
    } catch (Exception e) {
      log.warn("MSG91 OTP SMS failed for {}: {}", LogMasking.phone(phone), e.getMessage());
    }
  }

  /**
   * Last-resort SMS Retriever hashes, one per app.
   *
   * The client sends its own hash and that is what should be used: an app hash is
   * derived from the signing certificate, so these constants are only correct for
   * the keystore they were generated against. A build signed with a different key —
   * or by Play App Signing after re-signing — produces a different hash, and an SMS
   * carrying the wrong one means zero-touch auto-read silently never fires.
   *
   * Reaching this at all is worth a warning for that reason.
   */
  private static String resolveDefaultAppHash(UserRole role) {
    log.warn("No SMS Retriever app hash supplied by the client for role {} — falling back to the "
        + "built-in hash, which is only correct for the original keystore. Auto-read may not fire.", role);
    if (role == UserRole.BUYER) return "QxF6BzNczWU";
    if (role == UserRole.HELPER) return "Q5vrW5aH4wx";
    if (role == UserRole.MEDIATOR) return "xQNwzZe+Qv8";
    return "";
  }

  private static String encJson(String value) {
    if (value == null) return "";
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private void sendExotelOtp(String phone, String otp) {
    try {
      String to = toIndiaRecipient(phone);
      String body = "Your Superherooo OTP is " + otp + ". It is valid for "
          + Math.max(1, props.otp().ttlSeconds() / 60) + " minutes.";
      String form = "From=" + enc(exotel.from().trim())
          + "&To=" + enc(to)
          + "&Body=" + enc(body);
      String userInfo = encUserInfo(exotel.apiKey()) + ":" + encUserInfo(exotel.apiToken());
      URI uri = URI.create("https://" + userInfo + "@" + exotel.normalizedSubdomain()
          + "/v1/Accounts/" + encPath(exotel.accountSid()) + "/Sms/send");
      HttpRequest req = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() < 200 || res.statusCode() >= 300) {
        // Body is deliberately not logged: an Exotel error can echo the message
        // text, which contains the OTP.
        log.warn("Exotel OTP SMS failed for {} status={}", LogMasking.phone(to), res.statusCode());
      }
    } catch (Exception e) {
      log.warn("Exotel OTP SMS failed for {}: {}", LogMasking.phone(phone), e.getMessage());
    }
  }

  public boolean verifyOtp(String phone, String otp) {
    String key = key(phone);

    // A 6-digit code with a 5-minute TTL is trivially brute-forceable without a
    // cap on guesses. The IP-keyed RateLimitFilter does not help here: an
    // attacker rotating IPs still gets unlimited attempts at one phone number.
    if (registerVerifyAttempt(phone) > MAX_VERIFY_ATTEMPTS) {
      clearLocalOtp(key);
      log.warn("OTP invalidated after {} failed verification attempts", MAX_VERIFY_ATTEMPTS);
      return false;
    }

    String expected = null;
    try {
      expected = redis.opsForValue().get(key);
    } catch (Exception e) {
      log.warn("Redis OTP read failed, falling back to local cache: {}", e.getMessage());
    }
    if (expected == null) {
      LocalOtp local = localFallback.get(key);
      if (local != null && !local.isExpired()) {
        expected = local.code();
      } else if (local != null) {
        localFallback.remove(key);
      }
    }
    if (expected != null) {
      boolean ok = java.security.MessageDigest.isEqual(
          expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          otp == null ? new byte[0] : otp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (ok) {
        clearLocalOtp(key);
        clearVerifyAttempts(phone);
      }
      return ok;
    }

    if (twilio.enabled()) {
      try {
        Twilio.init(twilio.accountSid(), twilio.authToken());
        String recipient = toTwilioRecipient(phone);
        boolean isApproved = executeWithTimeout(() -> {
          VerificationCheck check = VerificationCheck.creator(twilio.verifyServiceSid())
              .setCode(otp)
              .setTo(recipient)
              .create();
          return "approved".equalsIgnoreCase(check.getStatus());
        }, 4); // Fail fast after 4 seconds
        return isApproved;
      } catch (Exception e) {
        log.warn("Twilio OTP verify failed or timed out: {}", e.getMessage());
        return false;
      }
    }
    return false;
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

  /** @return the attempt number just consumed, starting at 1. */
  private long registerVerifyAttempt(String phone) {
    String key = attemptsKey(phone);
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redis.expire(key, java.time.Duration.ofSeconds(props.otp().ttlSeconds()));
      }
      return count == null ? 1L : count;
    } catch (Exception e) {
      // Never lock a user out because Redis is having a bad day.
      log.warn("Redis OTP attempt counter unavailable: {}", e.getMessage());
      return 1L;
    }
  }

  private void clearVerifyAttempts(String phone) {
    try {
      redis.delete(attemptsKey(phone));
    } catch (Exception ignored) {
      // Counter expires on its own TTL.
    }
  }

  private static String attemptsKey(String phone) {
    return "him:otp_attempts:" + phone;
  }

  private static String key(String phone) {
    return "him:otp:" + phone;
  }

  private static String toTwilioRecipient(String phone) {
    if (phone == null) return null;
    String normalized = phone.replaceAll("\\s+", "");
    if (normalized.startsWith("+")) return normalized;
    if (normalized.matches("^91[6-9]\\d{9}$")) return "+" + normalized;
    if (normalized.matches("^[6-9]\\d{9}$")) return "+91" + normalized;
    return normalized;
  }

  private static String toIndiaRecipient(String phone) {
    if (phone == null) return "";
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() > 10) {
      digits = digits.substring(digits.length() - 10);
    }
    return digits.matches("^[6-9]\\d{9}$") ? "91" + digits : digits;
  }

  private static String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String encPath(String value) {
    return enc(value).replace("+", "%20");
  }

  private static String encUserInfo(String value) {
    return enc(value).replace("+", "%20");
  }

  private static String safeLogBody(String body) {
    if (body == null) return "";
    return body.length() <= 300 ? body : body.substring(0, 300);
  }

  private String createAndStoreLocalOtp(String phone) {
    String otp = String.format("%06d", RNG.nextInt(1_000_000));
    storeOtp(phone, otp);
    return otp;
  }

  /** Redis, with the in-process cache as the fallback when Redis is unavailable. */
  private void storeOtp(String phone, String otp) {
    String key = key(phone);
    try {
      redis.opsForValue().set(key, otp, Duration.ofSeconds(props.otp().ttlSeconds()));
    } catch (Exception e) {
      log.warn("Redis OTP write failed, falling back to local cache: {}", e.getMessage());
      localFallback.put(key, new LocalOtp(otp, expiresAtMs()));
    }
  }

  private void clearLocalOtp(String key) {
    try {
      redis.delete(key);
    } catch (Exception e) {
      log.warn("Redis OTP delete failed, clearing local cache: {}", e.getMessage());
    }
    localFallback.remove(key);
  }

  private long expiresAtMs() {
    return System.currentTimeMillis() + (props.otp().ttlSeconds() * 1000L);
  }

  private <T> T executeWithTimeout(java.util.concurrent.Callable<T> callable, int timeoutSeconds) throws Exception {
    try {
      return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try {
          return callable.call();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, otpDeliveryExecutor).orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS).get();
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  private record LocalOtp(String code, long expiresAtMs) {
    boolean isExpired() {
      return System.currentTimeMillis() > expiresAtMs;
    }
  }
}
