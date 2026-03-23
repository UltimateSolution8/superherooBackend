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
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {
  private static final class Counter {
    volatile long minute;
    final AtomicInteger count = new AtomicInteger(0);
  }

  private final Map<String, Counter> counters = new ConcurrentHashMap<>();
  private final int otpStartPerMin = intEnv("RATE_LIMIT_OTP_START_PER_MIN", 5);
  private final int otpVerifyPerMin = intEnv("RATE_LIMIT_OTP_VERIFY_PER_MIN", 10);
  private final int loginPerMin = intEnv("RATE_LIMIT_LOGIN_PER_MIN", 12);
  private final int signupPerMin = intEnv("RATE_LIMIT_SIGNUP_PER_MIN", 6);
  private final int helperKycSignupPerMin = intEnv("RATE_LIMIT_HELPER_KYC_SIGNUP_PER_MIN", 4);
  private final int refreshPerMin = intEnv("RATE_LIMIT_REFRESH_PER_MIN", 30);

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
    } else {
      return false;
    }
    if (limit <= 0) return false;

    String ip = clientIp(request);
    String key = path + ":" + ip;
    long minute = Instant.now().getEpochSecond() / 60;
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
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      if (comma > 0) {
        return forwarded.substring(0, comma).trim();
      }
      return forwarded.trim();
    }
    String real = request.getHeader("X-Real-IP");
    if (real != null && !real.isBlank()) {
      return real.trim();
    }
    return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
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
