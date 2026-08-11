package com.helpinminutes.api.geo;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A monthly ceiling on billable Google search calls.
 *
 * <p>Search sits on Google because at launch volume it is free or close to it. What
 * makes that safe rather than optimistic is this: a bug that retries in a loop, or a
 * scraper hammering autocomplete, would otherwise turn a ₹0 month into a five-figure
 * one with no signal until the invoice. Past the cap the Google provider reports no
 * answer for the text SKUs and {@link GeoProviderChain} falls through to Ola — which
 * is worse-looking search, not broken search.
 *
 * <p>Counted per calendar month in IST, in Redis so every instance shares one budget.
 * A Redis outage fails <em>open</em>: losing search entirely because the counter is
 * unavailable would be a worse outcome than a day of uncapped spend, and the Cloud
 * Console quota cap is the real hard limit behind this one.
 *
 * <p>Routing is deliberately not metered here. It only reaches Google when both OSRM
 * and Ola are down, and a partner with no route at all is a worse failure than the
 * cost of the call.
 */
@Component
public class GeoSpendGuard {

  private static final Logger log = LoggerFactory.getLogger(GeoSpendGuard.class);
  private static final String PREFIX = "him:geo:google:calls:";
  private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

  /** Keys outlive their month by a margin, so a late call cannot resurrect one. */
  private static final Duration KEY_TTL = Duration.ofDays(40);

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
}
