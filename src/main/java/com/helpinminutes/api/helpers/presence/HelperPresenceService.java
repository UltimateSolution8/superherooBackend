package com.helpinminutes.api.helpers.presence;

import com.helpinminutes.api.config.AppProperties;
import com.uber.h3core.H3Core;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class HelperPresenceService {
  private static final String ONLINE_HELPERS_KEY = "him:online:helpers";
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

    if (prevCell != null && !prevCell.isBlank() && !prevCell.equals(newCell)) {
      redis.opsForSet().remove(keyOnlineH3(prevCell), helperId.toString());
    }

    redis.opsForHash().put(stateKey, "lat", Double.toString(lat));
    redis.opsForHash().put(stateKey, "lng", Double.toString(lng));
    redis.opsForHash().put(stateKey, "h3", newCell);
    redis.opsForHash().put(stateKey, "online", "1");
    redis.opsForHash().put(stateKey, "lastSeenEpochMs", Long.toString(Instant.now().toEpochMilli()));
    
    // Set 10-minute expiry to automatically clean up Redis memory when helpers go offline/inactive
    redis.expire(stateKey, Duration.ofMinutes(10));

    redis.opsForSet().add(keyOnlineH3(newCell), helperId.toString());
    redis.opsForSet().add(ONLINE_HELPERS_KEY, helperId.toString());
  }

  public void setOffline(UUID helperId) {
    String stateKey = keyHelperState(helperId);
    String prevCell = redis.opsForHash().get(stateKey, "h3") instanceof String s ? s : null;
    if (prevCell != null && !prevCell.isBlank()) {
      redis.opsForSet().remove(keyOnlineH3(prevCell), helperId.toString());
    }

    redis.opsForHash().put(stateKey, "online", "0");
    redis.opsForHash().put(stateKey, "lastSeenEpochMs", Long.toString(Instant.now().toEpochMilli()));
    
    // Expire the offline state after 10 minutes to clean up Redis memory
    redis.expire(stateKey, Duration.ofMinutes(10));
    
    redis.opsForSet().remove(ONLINE_HELPERS_KEY, helperId.toString());
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
    Set<String> helperIds = h3Cells.stream()
        .map(Long::toUnsignedString)
        .map(HelperPresenceService::keyOnlineH3)
        .flatMap(k -> {
          Set<String> members = redis.opsForSet().members(k);
          return members == null ? Set.<String>of().stream() : members.stream();
        })
        .collect(Collectors.toSet());

    return helperIds.stream()
        .map(UUID::fromString)
        .filter(this::isHelperActive)
        .collect(Collectors.toSet());
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
