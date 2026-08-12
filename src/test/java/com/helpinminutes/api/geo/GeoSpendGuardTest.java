package com.helpinminutes.api.geo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The two ceilings that stand between a bug and a five-figure Google invoice.
 *
 * <p>What each test protects is the behaviour on the far side of the cap, not the
 * arithmetic: a refused call must degrade the request to the free provider, and a
 * broken Redis must never take search down with it.
 */
class GeoSpendGuardTest {

  private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void refusesOnceTheMonthlyCapIsExhausted() {
    GeoProperties props = new GeoProperties();
    props.getGoogle().setMonthlyCallCap(3);
    props.getGoogle().setPremiumReserveCalls(0);
    GeoSpendGuard guard = new GeoSpendGuard(new CountingRedis(), props);

    assertTrue(guard.tryConsume("autocomplete"));
    assertTrue(guard.tryConsume("autocomplete"));
    assertTrue(guard.tryConsume("autocomplete"));
    assertFalse(guard.tryConsume("autocomplete"));
  }

  @Test
  void refusesOnceOneUsersDailyCapIsExhausted() {
    GeoProperties props = new GeoProperties();
    props.getGoogle().setUserDailyCallCap(2);
    GeoSpendGuard guard = new GeoSpendGuard(new CountingRedis(), props);

    assertTrue(guard.allowPremiumForUser(USER));
    assertTrue(guard.allowPremiumForUser(USER));
    assertFalse(guard.allowPremiumForUser(USER));
  }

  /** One noisy account must not spend anybody else's budget. */
  @Test
  void countsEachUserSeparately() {
    GeoProperties props = new GeoProperties();
    props.getGoogle().setUserDailyCallCap(1);
    GeoSpendGuard guard = new GeoSpendGuard(new CountingRedis(), props);

    assertTrue(guard.allowPremiumForUser(USER));
    assertFalse(guard.allowPremiumForUser(USER));
    assertTrue(guard.allowPremiumForUser(UUID.randomUUID()));
  }

  /**
   * A signed-out caller has no budget to spend from, so it gets the free provider.
   * Pooling them into one anonymous bucket would let any one of them exhaust it for
   * all the others.
   */
  @Test
  void refusesPremiumWithoutAUser() {
    GeoProperties props = new GeoProperties();
    props.getGoogle().setUserDailyCallCap(100);

    assertFalse(new GeoSpendGuard(new CountingRedis(), props).allowPremiumForUser(null));
  }

  /** Redis failure closes only the Google leg; Ola/local search remains available. */
  @Test
  void fallsBackToOlaWhenRedisIsDown() {
    GeoProperties props = new GeoProperties();
    props.getGoogle().setMonthlyCallCap(1);
    props.getGoogle().setPremiumReserveCalls(0);
    props.getGoogle().setUserDailyCallCap(1);
    GeoSpendGuard guard = new GeoSpendGuard(new BrokenRedis(), props);

    assertFalse(guard.tryConsume("autocomplete"));
    assertFalse(guard.allowPremiumForUser(USER));
    assertFalse(guard.allowNewPremiumSession());
  }

  @Test
  void reservesCapacityForDetailsBeforeStartingMorePremiumSessions() {
    GeoProperties props = new GeoProperties();
    props.getGoogle().setMonthlyCallCap(5);
    props.getGoogle().setPremiumReserveCalls(2);
    GeoSpendGuard guard = new GeoSpendGuard(new CountingRedis(), props);

    assertTrue(guard.allowNewPremiumSession());
    assertTrue(guard.tryConsume("autocomplete"));
    assertTrue(guard.tryConsume("autocomplete"));
    assertTrue(guard.tryConsume("autocomplete"));
    // Refused searches do not increment through the reserve.
    assertFalse(guard.tryConsume("autocomplete"));
    assertFalse(guard.tryConsume("autocomplete"));
    assertFalse(guard.allowNewPremiumSession());
    // The reserved calls can still close already-issued Google suggestions.
    assertTrue(guard.tryConsume("placeDetails"));
    assertTrue(guard.tryConsume("placeDetails"));
    assertFalse(guard.tryConsume("placeDetails"));
  }

  @Test
  void detailsAuthorizationIsUserBoundAndSingleUse() {
    GeoSpendGuard guard = new GeoSpendGuard(new CountingRedis(), new GeoProperties());
    String token = "address-session-12345";

    assertTrue(guard.authorizePremiumDetails(USER, token));
    assertFalse(guard.consumePremiumDetailsAuthorization(UUID.randomUUID(), token));
    assertTrue(guard.consumePremiumDetailsAuthorization(USER, token));
    assertFalse(guard.consumePremiumDetailsAuthorization(USER, token));
  }

  @Test
  void detailsAuthorizationRejectsMalformedTokensAndRedisFailure() {
    GeoSpendGuard normal = new GeoSpendGuard(new CountingRedis(), new GeoProperties());
    assertFalse(normal.authorizePremiumDetails(USER, "bad token"));
    assertFalse(normal.consumePremiumDetailsAuthorization(USER, "short"));

    GeoSpendGuard broken = new GeoSpendGuard(new BrokenRedis(), new GeoProperties());
    assertFalse(broken.authorizePremiumDetails(USER, "address-session-12345"));
    assertFalse(broken.consumePremiumDetailsAuthorization(USER, "address-session-12345"));
  }

  @Test
  void zeroDisablesEitherCap() {
    GeoProperties props = new GeoProperties();
    props.getGoogle().setMonthlyCallCap(0);
    props.getGoogle().setUserDailyCallCap(0);
    GeoSpendGuard guard = new GeoSpendGuard(new BrokenRedis(), props);

    assertTrue(guard.tryConsume("autocomplete"));
    // Disabled means disabled, even for the anonymous caller the cap would refuse.
    assertTrue(guard.allowPremiumForUser(null));
  }

  /** Minimal in-memory stand-in: only INCR and EXPIRE are exercised. */
  private static class CountingRedis extends StringRedisTemplate {
    private final Map<String, Long> counters = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();

    @Override
    public ValueOperations<String, String> opsForValue() {
      return new StubValueOperations() {
        @Override
        public Long increment(String key) {
          return counters.merge(key, 1L, Long::sum);
        }

        @Override
        public String get(Object key) {
          String stored = values.get(String.valueOf(key));
          if (stored != null) return stored;
          Long value = counters.get(String.valueOf(key));
          return value == null ? null : String.valueOf(value);
        }

        @Override
        public void set(String key, String value, Duration timeout) {
          values.put(key, value);
        }

        @Override
        public String getAndDelete(String key) {
          return values.remove(key);
        }
      };
    }

    @Override
    public Boolean expire(String key, Duration timeout) {
      return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, java.util.List<String> keys, Object... args) {
      String key = keys.get(0);
      long limit = Long.parseLong(String.valueOf(args[0]));
      long used = counters.getOrDefault(key, 0L);
      if (used >= limit) return (T) Long.valueOf(-1L);
      long next = used + 1L;
      counters.put(key, next);
      return (T) Long.valueOf(next);
    }
  }

  private static final class BrokenRedis extends CountingRedis {
    @Override
    public <T> T execute(RedisScript<T> script, java.util.List<String> keys, Object... args) {
      throw new IllegalStateException("redis is unreachable");
    }

    @Override
    public ValueOperations<String, String> opsForValue() {
      throw new IllegalStateException("redis is unreachable");
    }
  }

  /**
   * ValueOperations has a wide surface and this guard uses one method of it, so the
   * rest throws rather than quietly returning null and making a broken test pass.
   */
  private abstract static class StubValueOperations
      implements ValueOperations<String, String> {

    @Override
    public abstract Long increment(String key);

    @Override
    public void set(String key, String value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void set(String key, String value, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Boolean setIfAbsent(String key, String value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Boolean setIfPresent(String key, String value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Boolean setIfPresent(String key, String value, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void multiSet(Map<? extends String, ? extends String> map) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Boolean multiSetIfAbsent(Map<? extends String, ? extends String> map) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String get(Object key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getAndDelete(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getAndExpire(String key, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getAndExpire(String key, Duration timeout) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getAndPersist(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getAndSet(String key, String value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> multiGet(java.util.Collection<String> keys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Long increment(String key, long delta) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Double increment(String key, double delta) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Long decrement(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Long decrement(String key, long delta) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Integer append(String key, String value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String get(String key, long start, long end) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void set(String key, String value, long offset) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Long size(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Boolean setBit(String key, long offset, boolean value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Boolean getBit(String key, long offset) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<Long> bitField(
        String key, org.springframework.data.redis.connection.BitFieldSubCommands subCommands) {
      throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.redis.core.RedisOperations<String, String> getOperations() {
      throw new UnsupportedOperationException();
    }
  }
}
