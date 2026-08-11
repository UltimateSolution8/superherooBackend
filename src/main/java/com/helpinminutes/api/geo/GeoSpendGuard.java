package com.helpinminutes.api.geo;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * fails <em>open</em>: losing search entirely because the counter is unavailable
 * would be a worse outcome than a day of uncapped spend, and the Cloud Console quota
 * cap is the real hard limit behind both of these.
 *
 * <p>Routing is deliberately not metered here. It only reaches Google when both OSRM
 * and Ola are down, and a partner with no route at all is a worse failure than the
 * cost of the call.
 */
@Component
public class GeoSpendGuard {

  private static final Logger log = LoggerFactory.getLogger(GeoSpendGuard.class);
  private static final String PREFIX = "him:geo:google:calls:";
  private static final String USER_PREFIX = "him:geo:google:user:";
  private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

  /** Keys outlive their month by a margin, so a late call cannot resurrect one. */
  private static final Duration KEY_TTL = Duration.ofDays(40);

  /** Per-user keys only need to outlive their own day. */
  private static final Duration USER_KEY_TTL = Duration.ofDays(2);

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

    String key = PREFIX + YearMonth.now(BILLING_ZONE);
    try {
      Long used = redis.opsForValue().increment(key);
      if (used == null) return true;
      if (used == 1L) {
        redis.expire(key, KEY_TTL);
      }
      if (used > cap) {
        if (capReported.compareAndSet(false, true)) {
          log.warn(
              "Google geo spend cap reached ({} calls this month, cap {}); falling back to the"
                  + " next provider for {} and the other text lookups until next month.",
              used,
              cap,
              capability);
        }
        return false;
      }
      capReported.set(false);
      return true;
    } catch (Exception e) {
      // Fail open: see the class comment.
      log.debug("Geo spend guard unavailable, allowing the call: {}", e.getMessage());
      return true;
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
      log.debug("Geo per-user guard unavailable, allowing the request: {}", e.getMessage());
      return true;
    }
  }
}
