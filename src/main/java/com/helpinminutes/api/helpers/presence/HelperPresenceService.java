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
  private static final String ONLINE_HELPERS_GEO_KEY = "him:online:helpers:geo";

  /**
   * How long a partner's state hash survives without a heartbeat.
   *
   * <p>Comfortably longer than the staleness threshold used for matching, so an
   * expired hash means "gone", not "briefly quiet".
   */
  private static final Duration STATE_TTL = Duration.ofMinutes(10);

  /**
   * TTL on the per-cell membership sets.
   *
   * <p>These previously had none, so every H3 cell any partner ever passed through
   * left a permanent key behind — unbounded key growth on a per-command-billed
   * store. They are only a cold-start fallback, so expiry is harmless: the GEO index
   * is authoritative.
   */
  private static final Duration H3_SET_TTL = Duration.ofMinutes(30);
  private final StringRedisTemplate redis;
  private final H3Core h3;
  private final AppProperties props;

  public HelperPresenceService(StringRedisTemplate redis, H3Core h3, AppProperties props) {
    this.redis = redis;
    this.h3 = h3;
    this.props = props;
  }

  /**
   * Claims the right to run a go-online dispatch sweep for this helper, at most
   * once per {@code cooldown}.
   *
   * <p>Backed by SET NX EX, so the claim is atomic across instances and expires on
   * its own — nothing has to release it. If Redis is unavailable the caller is
   * allowed through: suppressing dispatch is worse than occasionally repeating it.
   *
   * @return true if the caller should proceed with the sweep
   */
  public boolean tryAcquireGoOnlineDispatchLock(UUID helperId, Duration cooldown) {
    try {
      Boolean acquired = redis.opsForValue()
          .setIfAbsent("him:goonline:dispatch:" + helperId, "1", cooldown);
      return Boolean.TRUE.equals(acquired);
    } catch (Exception e) {
      log.warn("Go-online dispatch lock unavailable for helper {}: {}", helperId, e.getMessage());
      return true;
    }
  }

  /**
   * Result of a presence write.
   *
   * @param wasOffline true when this heartbeat flipped the partner from offline to
   *     online. Callers use it to run once-per-session work — the go-online
   *     dispatch sweep — instead of on every heartbeat.
   * @param cellChanged true when the partner moved into a different H3 cell
   */
  public record PresenceUpdate(boolean wasOffline, boolean cellChanged) {}

  /**
   * Writes a partner's heartbeat.
   *
   * <h2>Command budget</h2>
   *
   * Upstash bills per command, not per round trip, and this is the single hottest
   * path in the system — every online partner, every heartbeat, all day. It used to
   * cost 7–8 billable commands; a stationary partner now costs 4:
   *
   * <pre>
   *   HMGET online,h3           1   (needed: we cannot know the previous cell otherwise)
   *   HMSET state               1
   *   PEXPIRE state             1
   *   GEOADD geo index          1
   *   SREM/SADD h3 sets         0   only when the cell actually changed
   * </pre>
   *
   * Three specific savings, all of them removals rather than tricks:
   *
   * <ul>
   *   <li>The {@code him:online:helpers} SADD is gone. That set was written on every
   *       heartbeat and read by exactly one method, which had no callers anywhere.
   *   <li>The h3 set membership is only rewritten when the cell changes. Re-adding
   *       the same member to the same set every 15s was a billable no-op, and a
   *       stationary partner is the common case.
   *   <li>The read is one HMGET of two fields rather than a HGET plus a separate
   *       transition check, and it is what lets the caller skip the go-online
   *       dispatch lock (another command) on non-transitions.
   * </ul>
   */
  public PresenceUpdate setOnline(UUID helperId, double lat, double lng) {
    long h3Index = h3.latLngToCell(lat, lng, props.matching().h3Resolution());
    String newCell = Long.toUnsignedString(h3Index);

    String stateKey = keyHelperState(helperId);
    List<Object> previous = redis.opsForHash().multiGet(stateKey, List.of("online", "h3"));
    String prevOnline = previous != null && previous.size() > 0 && previous.get(0) instanceof String s ? s : null;
    String prevCell = previous != null && previous.size() > 1 && previous.get(1) instanceof String s ? s : null;
    boolean wasOffline = !"1".equals(prevOnline);
    boolean cellChanged = prevCell == null || prevCell.isBlank() || !prevCell.equals(newCell);

    String member = helperId.toString();
    Map<byte[], byte[]> state = new LinkedHashMap<>();
    state.put(bytes("lat"), bytes(Double.toString(lat)));
    state.put(bytes("lng"), bytes(Double.toString(lng)));
    state.put(bytes("h3"), bytes(newCell));
    state.put(bytes("online"), bytes("1"));
    state.put(bytes("lastSeenEpochMs"), bytes(Long.toString(Instant.now().toEpochMilli())));

    // Upstash is network-remote in production. Pipeline the write so one heartbeat
    // is a single round trip even though it is several commands.
    redis.executePipelined((RedisCallback<Object>) connection -> {
      connection.hashCommands().hMSet(bytes(stateKey), state);
      connection.keyCommands().pExpire(bytes(stateKey), STATE_TTL.toMillis());
      connection.geoCommands().geoAdd(bytes(ONLINE_HELPERS_GEO_KEY), new Point(lng, lat), bytes(member));
      if (cellChanged) {
        if (prevCell != null && !prevCell.isBlank()) {
          connection.setCommands().sRem(bytes(keyOnlineH3(prevCell)), bytes(member));
        }
        connection.setCommands().sAdd(bytes(keyOnlineH3(newCell)), bytes(member));
        // The h3 sets exist only as a cold-start fallback for sessions that predate
        // the GEO index, so they are allowed to expire. Without a TTL they accrued
        // one permanent key per cell any partner ever passed through.
        connection.keyCommands().pExpire(bytes(keyOnlineH3(newCell)), H3_SET_TTL.toMillis());
      }
      return null;
    });
    return new PresenceUpdate(wasOffline, cellChanged);
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
      connection.keyCommands().pExpire(bytes(stateKey), STATE_TTL.toMillis());
      connection.zSetCommands().zRem(bytes(ONLINE_HELPERS_GEO_KEY), bytes(member));
      return null;
    });
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
    Map<String, String> staleCells = new LinkedHashMap<>();
    for (int i = 0; i < memberIds.size(); i++) {
      UUID helperId = safeUuid(memberIds.get(i));
      HelperState state = helperId == null || i >= stateRows.size() ? null : stateFromPipeline(stateRows.get(i));
      if (helperId != null && isActiveState(state)) {
        active.put(helperId, state);
      } else {
        staleMembers.add(memberIds.get(i));
        if (state != null && state.h3Cell() != null && !state.h3Cell().isBlank()) {
          staleCells.put(memberIds.get(i), state.h3Cell());
        }
      }
    }
    if (!staleMembers.isEmpty()) {
      // Self-heal every index the member appears in, not just the GEO one. Removing
      // it from GEO alone left the h3 sets holding partners who force-quit without
      // calling setOffline — permanently, since nothing else swept them.
      redis.executePipelined((RedisCallback<Object>) connection -> {
        connection.zSetCommands().zRem(
            bytes(ONLINE_HELPERS_GEO_KEY),
            staleMembers.stream().map(this::bytes).toArray(byte[][]::new));
        staleCells.forEach((member, cell) ->
            connection.setCommands().sRem(bytes(keyOnlineH3(cell)), bytes(member)));
        return null;
      });
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
