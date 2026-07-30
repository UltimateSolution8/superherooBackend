package com.helpinminutes.api.helpers.presence;

import com.helpinminutes.api.config.AppProperties;
import com.uber.h3core.H3Core;
import java.util.LinkedHashMap;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.geo.Point;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

@Service
public class HelperPresenceService {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HelperPresenceService.class);
  private static final String ONLINE_HELPERS_KEY = "him:online:helpers";
  private static final String ONLINE_HELPERS_GEO_KEY = "him:online:helpers:geo";
  private final StringRedisTemplate redis;
  private final H3Core h3;
  private final AppProperties props;

  public HelperPresenceService(StringRedisTemplate redis, H3Core h3, AppProperties props) {
    this.redis = redis;
    this.h3 = h3;
    this.props = props;
  }

  public void setOnline(UUID helperId, double lat, double lng) {
    long h3Index = h3.latLngToCell(lat, lng, props.matching().h3Resolution());
    String newCell = Long.toUnsignedString(h3Index);

    String stateKey = keyHelperState(helperId);
    String prevCell = redis.opsForHash().get(stateKey, "h3") instanceof String s ? s : null;

    String member = helperId.toString();
    Map<byte[], byte[]> state = new LinkedHashMap<>();
    state.put(bytes("lat"), bytes(Double.toString(lat)));
    state.put(bytes("lng"), bytes(Double.toString(lng)));
    state.put(bytes("h3"), bytes(newCell));
    state.put(bytes("online"), bytes("1"));
    state.put(bytes("lastSeenEpochMs"), bytes(Long.toString(Instant.now().toEpochMilli())));

    // Upstash is network-remote in production. Pipeline the heartbeat update so
    // one online toggle does not incur a separate network round trip per field.
    redis.executePipelined((RedisCallback<Object>) connection -> {
      if (prevCell != null && !prevCell.isBlank() && !prevCell.equals(newCell)) {
        connection.setCommands().sRem(bytes(keyOnlineH3(prevCell)), bytes(member));
      }
      connection.hashCommands().hMSet(bytes(stateKey), state);
      connection.keyCommands().pExpire(bytes(stateKey), Duration.ofMinutes(10).toMillis());
      connection.setCommands().sAdd(bytes(keyOnlineH3(newCell)), bytes(member));
      connection.setCommands().sAdd(bytes(ONLINE_HELPERS_KEY), bytes(member));
      connection.geoCommands().geoAdd(bytes(ONLINE_HELPERS_GEO_KEY), new Point(lng, lat), bytes(member));
      return null;
    });
  }

  public void setOffline(UUID helperId) {
    String stateKey = keyHelperState(helperId);
    String prevCell = redis.opsForHash().get(stateKey, "h3") instanceof String s ? s : null;
    String member = helperId.toString();
    Map<byte[], byte[]> state = new LinkedHashMap<>();
    state.put(bytes("online"), bytes("0"));
    state.put(bytes("lastSeenEpochMs"), bytes(Long.toString(Instant.now().toEpochMilli())));
    redis.executePipelined((RedisCallback<Object>) connection -> {
      if (prevCell != null && !prevCell.isBlank()) {
        connection.setCommands().sRem(bytes(keyOnlineH3(prevCell)), bytes(member));
      }
      connection.hashCommands().hMSet(bytes(stateKey), state);
      connection.keyCommands().pExpire(bytes(stateKey), Duration.ofMinutes(10).toMillis());
      connection.setCommands().sRem(bytes(ONLINE_HELPERS_KEY), bytes(member));
      connection.zSetCommands().zRem(bytes(ONLINE_HELPERS_GEO_KEY), bytes(member));
      return null;
    });
  }

  private boolean isHelperActive(UUID helperId) {
    String stateKey = keyHelperState(helperId);
    List<Object> fields = redis.opsForHash().multiGet(stateKey, List.of("online", "lastSeenEpochMs", "h3"));
    if (fields == null || fields.size() < 3) {
      return false;
    }
    String online = fields.get(0) instanceof String s ? s : null;
    String lastSeenStr = fields.get(1) instanceof String s ? s : null;
    String h3Cell = fields.get(2) instanceof String s ? s : null;

    if (!"1".equals(online) || lastSeenStr == null) {
      return false;
    }

    try {
      long lastSeen = Long.parseLong(lastSeenStr);
      long now = Instant.now().toEpochMilli();
      if (now - lastSeen < 300_000) { // 5 minutes activity threshold
        return true;
      }
    } catch (NumberFormatException e) {
      // ignore
    }

    // Helper is stale/inactive. Clean up from sets.
    redis.opsForSet().remove(ONLINE_HELPERS_KEY, helperId.toString());
    redis.opsForGeo().remove(ONLINE_HELPERS_GEO_KEY, helperId.toString());
    if (h3Cell != null && !h3Cell.isBlank()) {
      redis.opsForSet().remove(keyOnlineH3(h3Cell), helperId.toString());
    }
    redis.opsForHash().put(stateKey, "online", "0");
    return false;
  }

  public HelperState getHelperState(UUID helperId) {
    String stateKey = keyHelperState(helperId);
    List<Object> fields = redis.opsForHash().multiGet(stateKey, List.of("lat", "lng", "h3", "online", "lastSeenEpochMs"));
    if (fields == null || fields.size() < 5) {
      return null;
    }
    Object lat = fields.get(0);
    Object lng = fields.get(1);
    Object cell = fields.get(2);
    Object online = fields.get(3);
    Object lastSeen = fields.get(4);

    if (!(lat instanceof String latS) || !(lng instanceof String lngS)) {
      return null;
    }
    return new HelperState(
        Double.parseDouble(latS),
        Double.parseDouble(lngS),
        cell instanceof String cs ? cs : null,
        online instanceof String os ? os : null,
        lastSeen instanceof String ls ? Long.parseLong(ls) : null);
  }

  public Set<UUID> getOnlineHelpersForCells(List<Long> h3Cells) {
    if (h3Cells == null || h3Cells.isEmpty()) return Set.of();
    List<String> keys = h3Cells.stream()
        .map(Long::toUnsignedString)
        .map(HelperPresenceService::keyOnlineH3)
        .toList();
    Set<String> helperIds = keys.size() == 1
        ? redis.opsForSet().members(keys.get(0))
        : redis.opsForSet().union(keys.get(0), keys.subList(1, keys.size()));
    if (helperIds == null || helperIds.isEmpty()) return Set.of();

    return helperIds.stream()
        .map(HelperPresenceService::safeUuid)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
  }

  /**
   * Finds nearby helpers with one Redis geospatial lookup, then verifies their
   * authoritative online heartbeat before they enter matching. H3 remains as a
   * migration fallback while existing online sessions populate the GEO index.
   */
  public Map<UUID, HelperState> getNearbyActiveHelperStates(
      double lat,
      double lng,
      double radiusMeters,
      int limit) {
    var args = RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
        .includeDistance()
        .sortAscending()
        .limit(Math.max(1, limit));
    org.springframework.data.geo.GeoResults<RedisGeoCommands.GeoLocation<String>> results;
    try {
      results = redis.opsForGeo().search(
          ONLINE_HELPERS_GEO_KEY,
          GeoReference.fromCoordinate(lng, lat),
          new Distance(Math.max(1d, radiusMeters) / 1000d, Metrics.KILOMETERS),
          args);
    } catch (RuntimeException e) {
      log.warn("Nearby helper GEO lookup failed; using H3 fallback: {}", e.getMessage());
      return Map.of();
    }
    if (results == null || results.getContent().isEmpty()) return Map.of();

    List<String> memberIds = results.getContent().stream()
        .map(result -> result.getContent().getName())
        .filter(java.util.Objects::nonNull)
        .filter(id -> safeUuid(id) != null)
        .toList();
    if (memberIds.isEmpty()) return Map.of();
    byte[][] fields = List.of("lat", "lng", "h3", "online", "lastSeenEpochMs").stream()
        .map(redis.getStringSerializer()::serialize)
        .toArray(byte[][]::new);
    List<Object> stateRows;
    try {
      stateRows = redis.executePipelined((RedisCallback<Object>) connection -> {
        for (String memberId : memberIds) {
          connection.hashCommands().hMGet(
              redis.getStringSerializer().serialize(keyHelperState(UUID.fromString(memberId))), fields);
        }
        return null;
      });
    } catch (RuntimeException e) {
      log.warn("Nearby helper state pipeline failed; using H3 fallback: {}", e.getMessage());
      return Map.of();
    }

    Map<UUID, HelperState> active = new LinkedHashMap<>();
    List<String> staleMembers = new java.util.ArrayList<>();
    for (int i = 0; i < memberIds.size(); i++) {
      UUID helperId = safeUuid(memberIds.get(i));
      HelperState state = helperId == null || i >= stateRows.size() ? null : stateFromPipeline(stateRows.get(i));
      if (helperId != null && isActiveState(state)) {
        active.put(helperId, state);
      } else {
        staleMembers.add(memberIds.get(i));
      }
    }
    if (!staleMembers.isEmpty()) {
      redis.opsForGeo().remove(ONLINE_HELPERS_GEO_KEY, staleMembers.toArray(String[]::new));
    }
    return active;
  }

  /**
   * Bulk-reads helper state in one pipeline.
   *
   * The H3 fallback previously called {@link #getHelperState} in a Java loop —
   * one network round trip per helper against a remote Redis. At the k-ring
   * sizes that path uses, that was hundreds of sequential round trips inside a
   * transaction holding a row lock.
   */
  public Map<UUID, HelperState> getHelperStates(Collection<UUID> helperIds) {
    if (helperIds == null || helperIds.isEmpty()) return Map.of();
    List<UUID> ids = List.copyOf(helperIds);
    byte[][] fields = List.of("lat", "lng", "h3", "online", "lastSeenEpochMs").stream()
        .map(redis.getStringSerializer()::serialize)
        .toArray(byte[][]::new);
    List<Object> stateRows;
    try {
      stateRows = redis.executePipelined((RedisCallback<Object>) connection -> {
        for (UUID helperId : ids) {
          connection.hashCommands().hMGet(
              redis.getStringSerializer().serialize(keyHelperState(helperId)), fields);
        }
        return null;
      });
    } catch (RuntimeException e) {
      log.warn("Helper state pipeline failed: {}", e.getMessage());
      return Map.of();
    }

    Map<UUID, HelperState> states = new LinkedHashMap<>();
    for (int i = 0; i < ids.size() && i < stateRows.size(); i++) {
      HelperState state = stateFromPipeline(stateRows.get(i));
      if (state != null) states.put(ids.get(i), state);
    }
    return states;
  }

  private static HelperState stateFromPipeline(Object raw) {
    if (!(raw instanceof List<?> values) || values.size() < 5) return null;
    try {
      String lat = stringValue(values.get(0));
      String lng = stringValue(values.get(1));
      if (lat == null || lng == null) return null;
      String lastSeen = stringValue(values.get(4));
      return new HelperState(
          Double.parseDouble(lat),
          Double.parseDouble(lng),
          stringValue(values.get(2)),
          stringValue(values.get(3)),
          lastSeen == null ? null : Long.parseLong(lastSeen));
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String stringValue(Object value) {
    if (value instanceof String string) return string;
    if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    return value == null ? null : String.valueOf(value);
  }

  private boolean isActiveState(HelperState state) {
    if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) return false;
    long staleMs = Math.max(10, props.matching().helperStaleAfterSeconds()) * 1000L;
    return Instant.now().toEpochMilli() - state.lastSeenEpochMs() <= staleMs;
  }

  private static UUID safeUuid(String raw) {
    try {
      return raw == null ? null : UUID.fromString(raw);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private byte[] bytes(String value) {
    return redis.getStringSerializer().serialize(value);
  }

  public Set<UUID> getOnlineHelpers() {
    Set<String> helperIds = redis.opsForSet().members(ONLINE_HELPERS_KEY);
    if (helperIds == null || helperIds.isEmpty()) {
      return Set.of();
    }
    return helperIds.stream()
        .map(raw -> {
          try {
            return UUID.fromString(raw);
          } catch (Exception ignored) {
            return null;
          }
        })
        .filter(java.util.Objects::nonNull)
        .filter(this::isHelperActive)
        .collect(Collectors.toSet());
  }

  public record HelperState(
      double lat,
      double lng,
      String h3Cell,
      String online,
      Long lastSeenEpochMs
  ) {}

  private static String keyHelperState(UUID helperId) {
    return "him:helper:" + helperId + ":state";
  }

  private static String keyOnlineH3(String h3Cell) {
    return "him:online:h3:" + h3Cell;
  }
}
