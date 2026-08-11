package com.helpinminutes.api.moderation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.moderation.dto.AIReviewResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caches model verdicts by the hash of the text that produced them.
 *
 * <p>There was no caching at any layer, so identical text was re-billed every time
 * it appeared: on each booking, on every bulk-CSV row retry, and again on prepaid
 * activation — that last one moderates the same task twice, once in
 * {@code createTask} and once in {@code activateTask}.
 *
 * <p>Duplicates are common in practice. Recurring tasks regenerate the same wording,
 * bulk bookings repeat a template across rows, and citizens re-post a job after a
 * search times out.
 *
 * <p>Every operation is best-effort. A Redis outage turns this into a pass-through;
 * it never fails a booking.
 */
@Component
public class ModerationResultCache {

  private static final Logger log = LoggerFactory.getLogger(ModerationResultCache.class);
  private static final String KEY_PREFIX = "him:mod:";

  /**
   * How long a verdict stays valid.
   *
   * <p>Long, because the input is immutable: the same words get the same answer. The
   * only reason to expire at all is so a policy or prompt change eventually takes
   * effect without a manual flush.
   */
  private static final Duration TTL = Duration.ofDays(30);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  public ModerationResultCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  public Optional<AIReviewResult> get(String title, String description) {
    try {
      String cached = redis.opsForValue().get(key(title, description));
      if (cached == null) return Optional.empty();
      return Optional.of(objectMapper.readValue(cached, AIReviewResult.class));
    } catch (Exception e) {
      log.debug("Moderation cache read failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  public void put(String title, String description, AIReviewResult result) {
    if (result == null) return;
    // A synthetic fallback verdict must never be cached: it reflects a provider
    // outage, not a judgement about this text, and pinning it for 30 days would
    // silently extend one bad minute into a month of unreviewed approvals.
    if (result.modelUsed() != null && result.modelUsed().startsWith("fallback")) {
      return;
    }
    try {
      redis.opsForValue().set(
          key(title, description), objectMapper.writeValueAsString(result), TTL);
    } catch (Exception e) {
      log.debug("Moderation cache write failed: {}", e.getMessage());
    }
  }

  /** SHA-256 of the normalised text, so trivial whitespace edits still hit. */
  private static String key(String title, String description) {
    String combined = com.helpinminutes.api.tasks.service.moderation.TextNormalizer.normalize(
        (title == null ? "" : title) + " " + (description == null ? "" : description));
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return KEY_PREFIX + HexFormat.of().formatHex(
          digest.digest(combined.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      return KEY_PREFIX + Integer.toHexString(combined.hashCode());
    }
  }
}
