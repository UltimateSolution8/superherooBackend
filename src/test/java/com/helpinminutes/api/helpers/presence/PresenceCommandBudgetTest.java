package com.helpinminutes.api.helpers.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.TestAppProperties;
import com.uber.h3core.H3Core;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisSetCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Guards the Redis command budget of the presence heartbeat.
 *
 * <p>This is the hottest path in the system — every online partner, every heartbeat,
 * all day — and Upstash bills per command, not per round trip. It cost 7–8 billable
 * commands per beat, which worked out to roughly 40 million commands a month at a
 * hundred partners before any matching happened.
 *
 * <p>The savings were removals, and removals are easy to reintroduce by accident, so
 * they are asserted here by counting the commands the pipeline actually issues.
 */
class PresenceCommandBudgetTest {

  private StringRedisTemplate redis;
  private HashOperations<String, Object, Object> hashOps;
  private CountingConnection connection;
  private HelperPresenceService presence;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redis = mock(StringRedisTemplate.class);
    hashOps = mock(HashOperations.class);
    connection = new CountingConnection();

    when(redis.getStringSerializer()).thenReturn((RedisSerializer) RedisSerializer.string());
    when(redis.opsForHash()).thenReturn(hashOps);
    when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(call -> {
      RedisCallback<?> callback = call.getArgument(0);
      callback.doInRedis(connection.connection());
      return List.of();
    });

    H3Core h3 = mock(H3Core.class);
    when(h3.latLngToCell(anyDouble(), anyDouble(), anyInt())).thenReturn(0x8928308280fffffL);

    presence = new HelperPresenceService(redis, h3, TestAppProperties.defaults());
  }

  /**
   * A partner who has not moved cells — the common case, since a stationary partner
   * heartbeats every few seconds from the same spot.
   */
  @Test
  void stationaryHeartbeatCostsFourCommands() {
    UUID helperId = UUID.randomUUID();
    String sameCell = Long.toUnsignedString(0x8928308280fffffL);
    when(hashOps.multiGet(anyString(), anyList())).thenReturn(List.of("1", sameCell));

    var update = presence.setOnline(helperId, 17.3850, 78.4867);

    // One state read (asserted separately) plus HMSET + PEXPIRE + GEOADD.
    assertEquals(3, connection.pipelinedCommands(),
        "stationary heartbeat should pipeline HMSET + PEXPIRE + GEOADD and nothing more, got: "
            + connection.describe());
    assertFalse(update.cellChanged());
    assertFalse(update.wasOffline());
  }

  /**
   * The write-only set. {@code him:online:helpers} was SADD-ed on every heartbeat and
   * read by exactly one method, which had no callers anywhere in the codebase.
   */
  @Test
  void neverWritesTheUnreadOnlineHelpersSet() {
    UUID helperId = UUID.randomUUID();
    when(hashOps.multiGet(anyString(), anyList()))
        .thenReturn(List.of("1", Long.toUnsignedString(0x8928308280fffffL)));

    presence.setOnline(helperId, 17.3850, 78.4867);

    assertTrue(connection.setAddKeys.stream().noneMatch(key -> key.equals("him:online:helpers")),
        "him:online:helpers has no readers; writing it is a billable no-op");
  }

  /**
   * Moving cells costs three more commands, and that is fine — it is rare relative to
   * the heartbeat rate, and the h3 index has to stay correct.
   */
  @Test
  void movingToANewCellRewritesTheH3SetsWithATtl() {
    UUID helperId = UUID.randomUUID();
    when(hashOps.multiGet(anyString(), anyList())).thenReturn(List.of("1", "999999"));

    var update = presence.setOnline(helperId, 17.3850, 78.4867);

    assertTrue(update.cellChanged());
    // HMSET + PEXPIRE + GEOADD + SREM(old cell) + SADD(new cell) + PEXPIRE(new cell)
    assertEquals(6, connection.pipelinedCommands(), connection.describe());
    // The h3 sets previously had no TTL, so every cell a partner ever passed through
    // left a permanent key behind.
    assertEquals(2, connection.expireCalls, "the new h3 set must get a TTL");
  }

  /** The transition flag is what lets the caller skip the go-online sweep. */
  @Test
  void reportsAnOfflineToOnlineTransition() {
    UUID helperId = UUID.randomUUID();
    when(hashOps.multiGet(anyString(), anyList())).thenReturn(List.of("0", "999999"));

    assertTrue(presence.setOnline(helperId, 17.3850, 78.4867).wasOffline());
  }

  @Test
  void readsOnlineAndCellInASingleCommand() {
    UUID helperId = UUID.randomUUID();
    when(hashOps.multiGet(anyString(), anyList()))
        .thenReturn(List.of("1", Long.toUnsignedString(0x8928308280fffffL)));

    presence.setOnline(helperId, 17.3850, 78.4867);

    ArgumentCaptor<List<Object>> fields = ArgumentCaptor.forClass(List.class);
    verify(hashOps).multiGet(anyString(), fields.capture());
    // One HMGET of two fields, not a HGET plus a separate transition check.
    assertEquals(List.of("online", "h3"), fields.getValue());
    verify(hashOps, never()).get(anyString(), any());
  }

  // ─── counting connection ──────────────────────────────────────────────────

  /**
   * Records which commands a pipeline issues.
   *
   * <p>A Mockito mock rather than a hand-written {@link RedisConnection}: the
   * interface has dozens of methods across every command family, and only four
   * matter here. The production code ignores pipeline return values, so null
   * answers are fine.
   */
  private static final class CountingConnection {
    int hashSets;
    int expireCalls;
    int geoAdds;
    int setAdds;
    int setRems;
    final List<String> setAddKeys = new java.util.ArrayList<>();

    private final RedisConnection connection = mock(RedisConnection.class);

    CountingConnection() {
      // hMSet returns void, so it needs doAnswer rather than when/thenAnswer.
      RedisHashCommands hash = mock(RedisHashCommands.class);
      org.mockito.Mockito.doAnswer(call -> {
        hashSets++;
        return null;
      }).when(hash).hMSet(any(), any());

      RedisKeyCommands keys = mock(RedisKeyCommands.class);
      when(keys.pExpire(any(), anyLong())).thenAnswer(call -> {
        expireCalls++;
        return true;
      });

      RedisSetCommands sets = mock(RedisSetCommands.class);
      when(sets.sAdd(any(), any(byte[][].class))).thenAnswer(call -> {
        setAdds++;
        setAddKeys.add(new String((byte[]) call.getArgument(0), StandardCharsets.UTF_8));
        return 1L;
      });
      when(sets.sRem(any(), any(byte[][].class))).thenAnswer(call -> {
        setRems++;
        return 1L;
      });

      RedisGeoCommands geo = mock(RedisGeoCommands.class);
      when(geo.geoAdd(any(), any(org.springframework.data.geo.Point.class), any()))
          .thenAnswer(call -> {
            geoAdds++;
            return 1L;
          });

      when(connection.hashCommands()).thenReturn(hash);
      when(connection.keyCommands()).thenReturn(keys);
      when(connection.setCommands()).thenReturn(sets);
      when(connection.geoCommands()).thenReturn(geo);
    }

    RedisConnection connection() {
      return connection;
    }

    int pipelinedCommands() {
      return hashSets + expireCalls + geoAdds + setAdds + setRems;
    }

    String describe() {
      return "HMSET=" + hashSets + " PEXPIRE=" + expireCalls + " GEOADD=" + geoAdds
          + " SADD=" + setAdds + " SREM=" + setRems;
    }
  }
}
