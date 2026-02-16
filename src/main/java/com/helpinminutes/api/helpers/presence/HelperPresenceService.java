package com.helpinminutes.api.helpers.presence;

import com.helpinminutes.api.config.AppProperties;
import com.uber.h3core.H3Core;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class HelperPresenceService {
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

    redis.opsForSet().add(keyOnlineH3(newCell), helperId.toString());
  }

  public void setOffline(UUID helperId) {
    String stateKey = keyHelperState(helperId);
    String prevCell = redis.opsForHash().get(stateKey, "h3") instanceof String s ? s : null;
    if (prevCell != null && !prevCell.isBlank()) {
      redis.opsForSet().remove(keyOnlineH3(prevCell), helperId.toString());
    }

    redis.opsForHash().put(stateKey, "online", "0");
    redis.opsForHash().put(stateKey, "lastSeenEpochMs", Long.toString(Instant.now().toEpochMilli()));
  }


  public HelperState getHelperState(UUID helperId) {
    String stateKey = keyHelperState(helperId);
    Object lat = redis.opsForHash().get(stateKey, "lat");
    Object lng = redis.opsForHash().get(stateKey, "lng");
    Object cell = redis.opsForHash().get(stateKey, "h3");
    Object online = redis.opsForHash().get(stateKey, "online");
    Object lastSeen = redis.opsForHash().get(stateKey, "lastSeenEpochMs");

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
        .map(com.helpinminutes.api.helpers.presence.HelperPresenceService::keyOnlineH3)
        .flatMap(k -> {
          Set<String> members = redis.opsForSet().members(k);
          return members == null ? Set.<String>of().stream() : members.stream();
        })
        .collect(Collectors.toSet());

    return helperIds.stream().map(UUID::fromString).collect(Collectors.toSet());
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
