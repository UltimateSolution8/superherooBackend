package com.helpinminutes.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * Redis-backed response cache.
 *
 * <p>{@code @Cacheable} and {@code @CacheEvict} annotations already existed in
 * {@code ReportService} but did nothing, because no configuration ever enabled
 * caching. They are live as of this class — which is why the master report's
 * cache key had to be fixed first: it was two raw {@code Instant}s, so a
 * "now"-relative dashboard window produced a fresh key on every request and would
 * have filled Redis with entries that could never be read.
 *
 * <p>Scope is deliberately narrow: reports and slow-moving admin lookups. Nothing
 * on the buyer or partner hot path is cached — task lifecycle reads, the nearby
 * marketplace list, user profiles, authentication and payment state must always
 * reflect the database, and helper positions change by the second.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "CACHE_ENABLED", havingValue = "true", matchIfMissing = true)
public class CacheConfig {
  private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

  /**
   * Cache manager, built on a SCAN-based writer.
   *
   * <p>The default batch strategy for {@code @CacheEvict(allEntries = true)} is
   * {@code BatchStrategies.keys()}, which issues a single {@code KEYS him:cache:*}
   * pattern command. Against a managed, per-command-billed Redis that is one
   * expensive and potentially blocking command every time an admin hits "refresh
   * views". SCAN with a cursor costs more round trips and no blocking.
   */
  @Bean
  public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
    RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
        // Namespaced so cache entries are distinguishable from presence, OTP and
        // rate-limit keys in the same Redis instance.
        .prefixCacheNameWith("him:cache:")
        // A null is usually "not found", which is exactly the answer that should
        // be re-checked rather than remembered.
        .disableCachingNullValues()
        .serializeValuesWith(SerializationPair.fromSerializer(
            new GenericJackson2JsonRedisSerializer(objectMapper)))
        .entryTtl(Duration.ofMinutes(5));

    Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
    // Date-windowed consolidated report, keyed to the hour.
    perCache.put("reports", base.entryTtl(Duration.ofMinutes(5)));
    // Backed by materialized views that are themselves refreshed on a schedule,
    // so a longer TTL costs no additional staleness.
    perCache.put("reportsMv", base.entryTtl(Duration.ofMinutes(10)));
    // Note: `adminCounters` and `learningCatalog` used to be configured here with no
    // @Cacheable anywhere referencing them. Dead config invites the assumption that
    // those paths are cached when they are not, so they are gone until something
    // actually uses them.

    return RedisCacheManager.builder(
            RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory, BatchStrategies.scan(500)))
        .cacheDefaults(base)
        .withInitialCacheConfigurations(perCache)
        // Writes are deferred to after commit, so a rolled-back transaction
        // cannot leave its results in the cache.
        .transactionAware()
        .build();
  }

  /**
   * Without this, every {@code @Cacheable} method propagates
   * {@code RedisConnectionFailureException} to the caller — turning a Redis blip
   * into a 500 on endpoints that would otherwise have worked perfectly well by
   * going to the database. A cache outage should cost latency, not availability.
   */
  @Bean
  public CacheErrorHandler cacheErrorHandler() {
    return new CacheErrorHandler() {
      @Override
      public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache read failed, falling through to source (cache={}, key={}): {}",
            cache.getName(), key, exception.getMessage());
      }

      @Override
      public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache write failed (cache={}, key={}): {}",
            cache.getName(), key, exception.getMessage());
      }

      @Override
      public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        // Worth a louder level: a failed evict means stale data can be served
        // until the entry's TTL expires.
        log.error("Cache evict failed; entries may be stale until TTL (cache={}, key={}): {}",
            cache.getName(), key, exception.getMessage());
      }

      @Override
      public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.error("Cache clear failed; entries may be stale until TTL (cache={}): {}",
            cache.getName(), exception.getMessage());
      }
    };
  }
}
