package com.helpinminutes.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {
  private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>(
      "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n",
      Long.class);
  private static final class Counter {
    volatile long minute;
    final AtomicInteger count = new AtomicInteger(0);
  }

  private final Map<String, Counter> counters = new ConcurrentHashMap<>();
  private final StringRedisTemplate redis;
  private final int otpStartPerMin = intEnv("RATE_LIMIT_OTP_START_PER_MIN", 5);
  private final int otpVerifyPerMin = intEnv("RATE_LIMIT_OTP_VERIFY_PER_MIN", 10);
  private final int loginPerMin = intEnv("RATE_LIMIT_LOGIN_PER_MIN", 12);
  private final int signupPerMin = intEnv("RATE_LIMIT_SIGNUP_PER_MIN", 6);
  private final int helperKycSignupPerMin = intEnv("RATE_LIMIT_HELPER_KYC_SIGNUP_PER_MIN", 4);
  private final int refreshPerMin = intEnv("RATE_LIMIT_REFRESH_PER_MIN", 30);
  private final int paymentOrderPerMin = intEnv("RATE_LIMIT_PAYMENT_ORDER_PER_MIN", 20);
  private final int paymentVerifyPerMin = intEnv("RATE_LIMIT_PAYMENT_VERIFY_PER_MIN", 40);

  public RateLimitFilter() {
    this(null);
  }

  public RateLimitFilter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    if (isLimited(request)) {
      response.setStatus(429);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"RATE_LIMIT\",\"message\":\"Too many requests. Please wait and try again.\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean isLimited(HttpServletRequest request) {
    String method = request.getMethod();
    if (!"POST".equalsIgnoreCase(method)) {
      return false;
    }
    String path = request.getRequestURI();
    String bucket = path;
    int limit = 0;
    if (path.endsWith("/api/v1/auth/otp/start")) {
      limit = otpStartPerMin;
    } else if (path.endsWith("/api/v1/auth/otp/verify")) {
      limit = otpVerifyPerMin;
    } else if (path.endsWith("/api/v1/auth/password/login")) {
      limit = loginPerMin;
    } else if (path.endsWith("/api/v1/auth/password/signup/helper-kyc")) {
      limit = helperKycSignupPerMin;
    } else if (path.endsWith("/api/v1/auth/password/signup")) {
      limit = signupPerMin;
    } else if (path.endsWith("/api/v1/auth/refresh")) {
      limit = refreshPerMin;
    } else if (path.startsWith("/api/v1/payments/tasks/") && path.endsWith("/orders")) {
      limit = paymentOrderPerMin;
      bucket = "/api/v1/payments/tasks/*/orders";
    } else if (path.startsWith("/api/v1/payments/batches/") && path.endsWith("/orders")) {
      limit = paymentOrderPerMin;
      bucket = "/api/v1/payments/batches/*/orders";
    } else if (path.endsWith("/api/v1/payments/verify")) {
      limit = paymentVerifyPerMin;
    } else {
      return false;
    }
    if (limit <= 0) return false;

    String ip = clientIp(request);
    String key = bucket + ":" + ip;
    long minute = Instant.now().getEpochSecond() / 60;
    if (redis != null) {
      try {
        Long current = redis.execute(
            INCREMENT_WITH_EXPIRY,
            java.util.List.of("him:rate:" + bucket + ":" + ip + ":" + minute),
            "70");
        if (current != null) return current > limit;
      } catch (RuntimeException ignored) {
        // Authentication remains available during a Redis incident, while the
        // process-local limiter still provides basic protection.
      }
    }
    if (counters.size() > 10_000) {
      counters.entrySet().removeIf(entry -> entry.getValue().minute < minute - 1);
    }
    Counter counter = counters.computeIfAbsent(key, k -> {
      Counter c = new Counter();
      c.minute = minute;
      return c;
    });
    if (counter.minute != minute) {
      counter.minute = minute;
      counter.count.set(0);
    }
    int current = counter.count.incrementAndGet();
    return current > limit;
  }

  private static String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    if (isTrustedProxy(remote)) {
      String real = request.getHeader("X-Real-IP");
      if (real != null && !real.isBlank()) return real.trim();
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        String[] chain = forwarded.split(",");
        for (int i = chain.length - 1; i >= 0; i--) {
          String candidate = chain[i].trim();
          if (!candidate.isBlank() && !isTrustedProxy(candidate)) return candidate;
        }
      }
    }
    return remote == null ? "unknown" : remote;
  }

  private static boolean isTrustedProxy(String ip) {
    if (ip == null) return false;
    String value = ip.trim();
    if (value.equals("127.0.0.1") || value.equals("::1") || value.startsWith("10.") || value.startsWith("192.168.")) {
      return true;
    }
    if (value.startsWith("172.")) {
      String[] parts = value.split("\\.");
      if (parts.length > 1) {
        try {
          int second = Integer.parseInt(parts[1]);
          return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
          return false;
        }
      }
    }
    return false;
  }

  private static int intEnv(String key, int fallback) {
    try {
      String raw = System.getenv(key);
      if (raw == null || raw.isBlank()) return fallback;
      return Integer.parseInt(raw.trim());
    } catch (Exception ignored) {
      return fallback;
    }
  }
}
