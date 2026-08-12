package com.helpinminutes.api.geo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared cache in front of the geo providers.
 *
 * <p>This is the single biggest cost lever in the maps work. Autocomplete used to
 * run per device with a 90-second in-memory cache, so every citizen typing
 * "hitech city" billed a fresh lookup. Server-side, one lookup answers all of
 * them for a day.
 *
 * <p>Values are JSON strings under the existing {@code him:} namespace. Every
 * operation is best-effort: a Redis outage degrades this to a pass-through, it
 * never fails a request. Misses and errors are indistinguishable to the caller by
 * design.
 */
@Component
public class GeoCache {

  private static final Logger log = LoggerFactory.getLogger(GeoCache.class);
  // v2 invalidates legacy entries that could contain Google Places content.
  private static final String PREFIX = "him:geo:v3:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  public GeoCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /** Best-effort read. Kept separate from loading so provider policy can decide writes. */
  public <T> Optional<T> get(String key, TypeReference<T> type) {
    String fullKey = PREFIX + key;
    if (redis == null) return Optional.empty();
    try {
      String cached = redis.opsForValue().get(fullKey);
      return cached == null
          ? Optional.empty()
          : Optional.of(objectMapper.readValue(cached, type));
    } catch (Exception e) {
      log.debug("Geo cache read failed for {}: {}", fullKey, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Best-effort write.
   *
   * <p>The provider chain calls this only for providers whose terms permit shared
   * caching. In particular, Google Places predictions and place content are never
   * written here or reused across users/billing sessions.
   */
  public <T> void put(String key, Duration ttl, T value) {
    if (redis == null || value == null || ttl == null || ttl.isZero() || ttl.isNegative()) return;
    String fullKey = PREFIX + key;
    try {
      redis.opsForValue().set(fullKey, objectMapper.writeValueAsString(value), ttl);
    } catch (Exception e) {
      log.debug("Geo cache write failed for {}: {}", fullKey, e.getMessage());
    }
  }

  /**
   * Returns the cached value for {@code key}, or computes, stores and returns it.
   *
   * <p>{@code loader} returning empty is <em>not</em> cached: a provider outage
   * must not pin an empty answer in Redis for a day.
   */
  public <T> Optional<T> getOrLoad(
      String key, Duration ttl, TypeReference<T> type, Supplier<Optional<T>> loader) {
    Optional<T> cached = get(key, type);
    if (cached.isPresent()) return cached;
    Optional<T> loaded = loader.get();
    loaded.ifPresent(value -> put(key, ttl, value));
    return loaded;
  }

  /**
   * Cache key for an autocomplete query.
   *
   * <p>The query is normalised and the bias coordinate bucketed to ~1km, so
   * "Hitech City", "hitech  city" and the same query from two nearby users all
   * share one entry.
   *
   * <p>The two tiers get separate namespaces. Premium results are intentionally
   * not persisted, but keeping the namespace separate prevents a cheap cached
   * answer from bypassing the premium provider order. Sharing one would let whichever
   * request arrived first decide the quality every later one gets: a free-tier
   * lookup would pin an Ola answer that the create-task screen then serves as if
   * it were the premium result, and the reverse would quietly hand Google
   * suggestions — whose ids only Google can resolve — to callers that never asked
   * for them. Two namespaces cost a duplicate entry per shared query and nothing
   * else; both are computed from the same normalised query, so the hit rate within
   * each tier is unchanged.
   */
  public static String autocompleteKey(String query, Double biasLat, Double biasLng, boolean premium) {
    String normalised = query == null ? "" : query.trim().toLowerCase().replaceAll("\\s+", " ");
    String bias = biasLat == null || biasLng == null
        ? "-"
        : round(biasLat, 2) + "," + round(biasLng, 2);
    return (premium ? "acp:" : "ac:") + sha1(normalised + "|" + bias);
  }

  public static String placeDetailsKey(String placeId) {
    return "place:" + sha1(placeId == null ? "" : placeId);
  }

  /**
   * Cache key for a reverse geocode, bucketed to 4 decimal places (~11m).
   *
   * <p>Street addresses do not change between adjacent GPS fixes, so bucketing
   * turns a partner's stream of positions into a handful of lookups.
   */
  public static String reverseGeocodeKey(double lat, double lng) {
    return "rev:" + round(lat, 4) + "," + round(lng, 4);
  }

  /** Route key rounded to 4 decimals, matching the app's own route cache grain. */
  public static String routeKey(double fromLat, double fromLng, double toLat, double toLng) {
    return "route:" + round(fromLat, 4) + "," + round(fromLng, 4)
        + "->" + round(toLat, 4) + "," + round(toLng, 4);
  }

  private static String round(double value, int decimals) {
    return String.format("%." + decimals + "f", value);
  }

  private static String sha1(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      // Only reachable if SHA-1 is missing from the JRE. A readable fallback key
      // is better than failing the lookup.
      return Integer.toHexString(value.hashCode());
    }
  }
}
