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

  /**
   * Fails open. Losing address search because a counter is unreachable is a worse
   * outcome than a day of uncapped spend, and the Cloud Console quota cap is the
   * real hard limit behind both of these.
   */
  @Test
  void allowsEverythingWhenRedisIsDown() {
    GeoProperties props = new GeoProperties();
    props.getGoogle().setMonthlyCallCap(1);
    props.getGoogle().setUserDailyCallCap(1);
    GeoSpendGuard guard = new GeoSpendGuard(new BrokenRedis(), props);

    assertTrue(guard.tryConsume("autocomplete"));
    assertTrue(guard.tryConsume("autocomplete"));
    assertTrue(guard.allowPremiumForUser(USER));
    assertTrue(guard.allowPremiumForUser(USER));
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

    @Override
    public ValueOperations<String, String> opsForValue() {
      return new StubValueOperations() {
        @Override
        public Long increment(String key) {
          return counters.merge(key, 1L, Long::sum);
        }
      };
    }

    @Override
    public Boolean expire(String key, Duration timeout) {
      return true;
    }
  }

  private static final class BrokenRedis extends CountingRedis {
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
