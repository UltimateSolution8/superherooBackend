package com.helpinminutes.api.geo;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Two ceilings on Google usage: one on the bill, one on any single account.
 *
 * <p>Create-task autocomplete sits on Google because at launch volume it is free.
 * What makes that safe rather than optimistic is this: a bug that retries in a loop,
 * or a scraper hammering autocomplete, would otherwise turn a ₹0 month into a
 * five-figure one with no signal until the invoice. Past a cap the Google provider
 * reports no answer and {@link GeoProviderChain} falls through to Ola — which is
 * worse-looking search, not broken search.
 *
 * <p>The two ceilings answer different questions and neither replaces the other:
 *
 * <ul>
 *   <li><b>Monthly, global</b> ({@link #tryConsume}) — counts calls actually made to
 *       Google, from inside the provider, so it tracks spend. It bounds the invoice.
 *   <li><b>Daily, per user</b> ({@link #allowPremiumForUser}) — counts premium
 *       <em>requests</em>, from the controller, cache hits included. It bounds how
 *       much of the shared monthly budget one account can consume in a day. Counting
 *       cache hits makes it stricter than spend, which is the right direction: a
 *       client looping one query is exactly what it exists to stop.
 * </ul>
 *
 * <p>Counted in IST, in Redis so every instance shares one budget. A Redis outage
 * fails <em>closed to Google</em>, not closed to the user: the provider chain moves
 * to Ola/local answers. This keeps the application usable while preserving the
 * stated hard cost ceiling even when the budget store is unhealthy.
 *
 * <p>Routing uses the same global counter. A dual OSRM/Ola outage must not create an
 * unbounded Google Directions bill; once the cap is reached the chain returns its
 * straight-line ETA fallback instead.
 */
@Component
public class GeoSpendGuard {

  private static final Logger log = LoggerFactory.getLogger(GeoSpendGuard.class);
  private static final String PREFIX = "him:geo:google:calls:";
  private static final String USER_PREFIX = "him:geo:google:user:";
  private static final String SESSION_PREFIX = "him:geo:google:session:";
  private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

  /** Keys outlive their month by a margin, so a late call cannot resurrect one. */
  private static final Duration KEY_TTL = Duration.ofDays(40);

  /** Per-user keys only need to outlive their own day. */
  private static final Duration USER_KEY_TTL = Duration.ofDays(2);
  /** Google recommends a fresh token per session; stale predictions are useless. */
  private static final Duration SESSION_KEY_TTL = Duration.ofMinutes(30);
  private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>(
      "local used = tonumber(redis.call('GET', KEYS[1]) or '0'); "
          + "local limit = tonumber(ARGV[1]); "
          + "if used >= limit then return -1; end; "
          + "used = redis.call('INCR', KEYS[1]); "
          + "if used == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); end; "
          + "return used;",
      Long.class);

  private final StringRedisTemplate redis;
  private final GeoProperties props;

  /** One log line per process when the cap first bites, rather than one per request. */
  private final AtomicBoolean capReported = new AtomicBoolean(false);

  public GeoSpendGuard(StringRedisTemplate redis, GeoProperties props) {
    this.redis = redis;
    this.props = props;
  }

  /**
   * Records one billable call and says whether it may proceed.
   *
   * @param capability only for the log line when the cap trips
   * @return false once this month's cap is exhausted
   */
  public boolean tryConsume(String capability) {
    long cap = props.getGoogle().getMonthlyCallCap();
    if (cap <= 0) return true; // 0 or negative disables the guard.
    long reserve = Math.max(0L, Math.min(cap, props.getGoogle().getPremiumReserveCalls()));
    // Only the details request that resolves an already-displayed prediction may
    // consume the reserve. A routing outage or repeated autocomplete cannot starve
    // a user who taps a Google result shown moments earlier.
    long effectiveLimit = "placeDetails".equals(capability) ? cap : cap - reserve;
    if (effectiveLimit <= 0) return false;

    String key = PREFIX + YearMonth.now(BILLING_ZONE);
    try {
      Long used = redis.execute(
          CONSUME_SCRIPT,
          java.util.List.of(key),
          String.valueOf(effectiveLimit),
          String.valueOf(KEY_TTL.toMillis()));
      if (used == null) return false;
      if (used < 0) {
        if (capReported.compareAndSet(false, true)) {
          log.warn(
              "Google geo spend limit reached (limit {}, hard cap {}); falling back for {}",
              effectiveLimit,
              cap,
              capability);
        }
        return false;
      }
      capReported.set(false);
      return true;
    } catch (Exception e) {
      log.warn("Geo spend guard unavailable; refusing Google {} and falling back", capability);
      return false;
    }
  }

  /**
   * Whether a new premium autocomplete session may start.
   *
   * <p>This is a read, not a spend. It stops issuing Google suggestions before the
   * hard cap so their later place-details calls have reserved capacity. If Redis is
   * unavailable the request simply uses Ola.
   */
  public boolean allowNewPremiumSession() {
    long cap = props.getGoogle().getMonthlyCallCap();
    if (cap <= 0) return true;
    long reserve = Math.max(0L, Math.min(cap, props.getGoogle().getPremiumReserveCalls()));
    long cutoff = cap - reserve;
    String key = PREFIX + YearMonth.now(BILLING_ZONE);
    try {
      String raw = redis.opsForValue().get(key);
      long used = raw == null || raw.isBlank() ? 0L : Long.parseLong(raw);
      return used < cutoff;
    } catch (Exception e) {
      log.warn("Geo premium budget unavailable; using Ola for new autocomplete sessions");
      return false;
    }
  }

  /**
   * Records one premium request for {@code userId} and says whether it may proceed.
   *
   * <p>A false here is not an error: the caller downgrades the request to the free
   * provider order, so the user still gets suggestions. Signed-out callers cannot
   * reach the premium path at all, so a null id is refused rather than pooled into
   * one shared bucket that everybody would share the blame for.
   */
  public boolean allowPremiumForUser(UUID userId) {
    long cap = props.getGoogle().getUserDailyCallCap();
    if (cap <= 0) return true; // 0 or negative disables the per-user cap.
    if (userId == null) return false;

    String key = USER_PREFIX + userId + ":" + LocalDate.now(BILLING_ZONE);
    try {
      Long used = redis.opsForValue().increment(key);
      if (used == null) return true;
      if (used == 1L) {
        redis.expire(key, USER_KEY_TTL);
      }
      if (used > cap) {
        // Per user per day, so this is bounded and worth logging every time — it is
        // the signal that one account is behaving unlike the others.
        log.warn("Daily premium geo cap reached for user {} ({} requests, cap {})", userId, used, cap);
        return false;
      }
      return true;
    } catch (Exception e) {
      log.warn("Geo per-user budget unavailable; using Ola for user {}", userId);
      return false;
    }
  }

  /**
   * Authorizes exactly one Google details lookup for a session that actually
   * received Google predictions.
   *
   * <p>The controller calls this only after the provider chain reports Google.
   * That prevents a caller from inventing {@code google:} place ids and consuming
   * the reserve that exists for suggestions already displayed to real users.
   */
  public boolean authorizePremiumDetails(UUID userId, String sessionToken) {
    if (userId == null || !validSessionToken(sessionToken)) return false;
    try {
      redis.opsForValue().set(sessionKey(userId, sessionToken), "1", SESSION_KEY_TTL);
      return true;
    } catch (Exception e) {
      log.warn("Could not authorize Google details session for user {}; future lookup will use pin fallback", userId);
      return false;
    }
  }

  /** Consumes the one-time authorization created by {@link #authorizePremiumDetails}. */
  public boolean consumePremiumDetailsAuthorization(UUID userId, String sessionToken) {
    if (userId == null || !validSessionToken(sessionToken)) return false;
    try {
      return "1".equals(redis.opsForValue().getAndDelete(sessionKey(userId, sessionToken)));
    } catch (Exception e) {
      // Cost controls fail closed to Google. The app can still drop a pin.
      log.warn("Google details session store unavailable for user {}; refusing paid lookup", userId);
      return false;
    }
  }

  public boolean validSessionToken(String sessionToken) {
    if (sessionToken == null) return false;
    int length = sessionToken.length();
    if (length < 8 || length > 128) return false;
    for (int i = 0; i < length; i++) {
      char c = sessionToken.charAt(i);
      if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
          && !(c >= '0' && c <= '9') && c != '-' && c != '_' && c != '.') {
        return false;
      }
    }
    return true;
  }

  private static String sessionKey(UUID userId, String sessionToken) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(sessionToken.getBytes(StandardCharsets.UTF_8));
      return SESSION_PREFIX + userId + ":" + HexFormat.of().formatHex(digest);
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
