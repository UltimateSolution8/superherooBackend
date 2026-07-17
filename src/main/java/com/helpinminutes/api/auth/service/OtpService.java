package com.helpinminutes.api.auth.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.config.ExotelProperties;
import com.helpinminutes.api.config.TwilioProperties;
import com.helpinminutes.api.errors.BadRequestException;
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

  private final StringRedisTemplate redis;
  private final AppProperties props;
  private final TwilioProperties twilio;
  private final ExotelProperties exotel;
  private final Executor otpDeliveryExecutor;
  private final ConcurrentHashMap<String, LocalOtp> localFallback = new ConcurrentHashMap<>();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public OtpService(
      StringRedisTemplate redis,
      AppProperties props,
      TwilioProperties twilio,
      ExotelProperties exotel,
      @Qualifier("otpDeliveryExecutor") Executor otpDeliveryExecutor) {
    this.redis = redis;
    this.props = props;
    this.twilio = twilio;
    this.exotel = exotel;
    this.otpDeliveryExecutor = otpDeliveryExecutor;
  }

  public String startOtp(String phone, String channel) {
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
      log.warn("Redis OTP rate limiting failed for {}: {}", phone, e.getMessage());
    }

    if (exotel != null && exotel.enabled()) {
      String otp = createAndStoreLocalOtp(phone);
      if (exotel.canSendSms()) {
        try {
          // OTP generation must not wait on an external SMS gateway. Delivery is
          // best-effort and dev OTP remains available as the configured fallback.
          otpDeliveryExecutor.execute(() -> sendExotelOtp(phone, otp));
        } catch (RejectedExecutionException e) {
          log.warn("Exotel OTP delivery queue is full for {}. Using dev/local OTP.", phone);
        }
      } else {
        log.warn("Exotel OTP is enabled but SMS is not sent because EXOTEL_FROM or credentials are missing. Using dev/local OTP.");
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
            log.info("Twilio OTP request sent asynchronously for {}", recipient);
          } catch (Exception ex) {
            log.warn("Twilio OTP async start failed for {}: {}", recipient, ex.getMessage());
          }
        });
      } catch (Exception e) {
        log.warn("Failed to queue Twilio OTP request for {}: {}", recipient, e.getMessage());
      }
      return localOtp;
    }
    return createAndStoreLocalOtp(phone);
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
        log.warn("Exotel OTP SMS failed for {} status={} body={}", to, res.statusCode(), safeLogBody(res.body()));
      }
    } catch (Exception e) {
      log.warn("Exotel OTP SMS failed for {}: {}", phone, e.getMessage());
    }
  }

  public boolean verifyOtp(String phone, String otp) {
    if (props.otp().returnOtpInResponse() && ("123456".equals(otp) || "1234".equals(otp))) {
      return true;
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
      if (local != null && !local.isExpired()) {
        expected = local.code();
      } else if (local != null) {
        localFallback.remove(key);
      }
    }
    if (expected != null) {
      boolean ok = expected.equals(otp);
      if (ok) {
        clearLocalOtp(key);
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
    String key = key(phone);
    try {
      redis.opsForValue().set(key, otp, Duration.ofSeconds(props.otp().ttlSeconds()));
    } catch (Exception e) {
      log.warn("Redis OTP write failed, falling back to local cache: {}", e.getMessage());
      localFallback.put(key, new LocalOtp(otp, expiresAtMs()));
    }
    return otp;
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
