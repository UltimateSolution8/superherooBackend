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
  // Every one of these sends an email or spends money if left unmetered.
  private final int emailOtpStartPerMin = intEnv("RATE_LIMIT_EMAIL_OTP_START_PER_MIN", 4);
  private final int emailOtpVerifyPerMin = intEnv("RATE_LIMIT_EMAIL_OTP_VERIFY_PER_MIN", 10);
  private final int forgotPasswordPerMin = intEnv("RATE_LIMIT_FORGOT_PASSWORD_PER_MIN", 4);
  private final int resetPasswordPerMin = intEnv("RATE_LIMIT_RESET_PASSWORD_PER_MIN", 8);
  private final int logoutPerMin = intEnv("RATE_LIMIT_LOGOUT_PER_MIN", 30);
  /** Unauthenticated and backed by a paid LLM — an open cost-amplification target. */
  private final int chatbotPerMin = intEnv("RATE_LIMIT_CHATBOT_PER_MIN", 8);
  private final int publicPartnerKycPerMin = intEnv("RATE_LIMIT_PUBLIC_PARTNER_KYC_PER_MIN", 3);
  private final int ifscLookupPerMin = intEnv("RATE_LIMIT_IFSC_LOOKUP_PER_MIN", 30);
  private final int bankChangeOtpStartPerMin = intEnv("RATE_LIMIT_BANK_CHANGE_OTP_START_PER_MIN", 3);
  private final int bankChangeOtpVerifyPerMin = intEnv("RATE_LIMIT_BANK_CHANGE_OTP_VERIFY_PER_MIN", 8);
  /**
   * Proxied maps lookups. Billable per request upstream, so metered even though
   * they are authenticated GETs. 120/min still allows a fast typist several
   * address entries a minute after server-side debouncing and caching.
   */
  private final int geoAutocompletePerMin = intEnv("RATE_LIMIT_GEO_AUTOCOMPLETE_PER_MIN", 120);
  private final int geoLookupPerMin = intEnv("RATE_LIMIT_GEO_LOOKUP_PER_MIN", 60);
  /** Task creation triggers content moderation, which may call a paid LLM. */
  private final int taskCreatePerMin = intEnv("RATE_LIMIT_TASK_CREATE_PER_MIN", 20);

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
    boolean ifscLookup = "GET".equalsIgnoreCase(method)
        && pathMatchesIfsc(request.getRequestURI());
    boolean geoLookup = "GET".equalsIgnoreCase(method)
        && pathMatchesGeo(request.getRequestURI());
    if (!"POST".equalsIgnoreCase(method) && !ifscLookup && !geoLookup) {
      return false;
    }
    String path = request.getRequestURI();
    String bucket = path;
    int limit = 0;
    if (ifscLookup) {
      limit = ifscLookupPerMin;
      bucket = "/api/v1/*/ifsc/*";
    } else if (geoLookup) {
      // Proxied Places/Directions calls cost money per request upstream. A single
      // client typing fast is normal; a script hammering this endpoint would burn
      // the Ola free tier and then real money. Autocomplete gets the looser limit
      // because one address entry legitimately fires several keystroke requests.
      limit = path.contains("/autocomplete") ? geoAutocompletePerMin : geoLookupPerMin;
      bucket = path.contains("/autocomplete") ? "/api/v1/geo/autocomplete" : "/api/v1/geo/*";
    } else if (path.endsWith("/api/v1/tasks")) {
      // Task creation runs content moderation, which can call an LLM. Unmetered,
      // an authenticated buyer could drive unbounded provider spend.
      limit = taskCreatePerMin;
    } else if (path.matches("^/api/v1/(helper|mediator)/payout-account/change-challenge$")) {
      limit = bankChangeOtpStartPerMin;
      bucket = "/api/v1/*/payout-account/change-challenge";
    } else if (path.matches("^/api/v1/(helper|mediator)/payout-account/change-challenge/verify$")) {
      limit = bankChangeOtpVerifyPerMin;
      bucket = "/api/v1/*/payout-account/change-challenge/verify";
    } else if (path.endsWith("/api/v1/auth/otp/start")) {
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
    } else if (path.endsWith("/api/v1/auth/password/forgot")) {
      limit = forgotPasswordPerMin;
    } else if (path.endsWith("/api/v1/auth/password/reset")) {
      limit = resetPasswordPerMin;
    } else if (path.endsWith("/api/v1/auth/logout")) {
      limit = logoutPerMin;
    } else if (path.endsWith("/api/v1/auth/email/otp/start")) {
      limit = emailOtpStartPerMin;
    } else if (path.endsWith("/api/v1/auth/email/otp/verify")) {
      limit = emailOtpVerifyPerMin;
    } else if (path.endsWith("/api/v1/me/email/verify/send")) {
      limit = emailOtpStartPerMin;
    } else if (path.endsWith("/api/v1/me/phone/verify/send")) {
      limit = otpStartPerMin;
    } else if (path.endsWith("/api/public/chatbot/chat")) {
      limit = chatbotPerMin;
    } else if (path.endsWith("/api/public/partner-kyc")) {
      limit = publicPartnerKycPerMin;
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

    String ip = ClientIpResolver.resolve(request);
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

  private static boolean pathMatchesIfsc(String path) {
    return path != null && path.matches("^/api/v1/(helper|mediator)/ifsc/[^/]+$");
  }

  private static boolean pathMatchesGeo(String path) {
    return path != null && path.matches("^/api/(v1/)?geo/(autocomplete|place|reverse|route)$");
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
