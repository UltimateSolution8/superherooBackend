package com.helpinminutes.api.geo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.helpinminutes.api.common.GeoUtils;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs a capability across its configured providers until one answers.
 *
 * <p>The contract that matters: <b>no geo call can fail a user flow.</b> Every
 * method here returns a usable answer even with every provider down — an empty
 * suggestion list, the pinned coordinates as an "address", a straight-line ETA.
 * The response is marked {@code degraded} so the app can hint at it, but booking,
 * accepting and completing a task never depend on a maps provider being up.
 *
 * <p>A per-provider circuit breaker keeps an outage from costing every request its
 * full timeout: after {@code circuitBreakerThreshold} consecutive failures a
 * provider is skipped for {@code circuitBreakerCooldownMs}, then given one probe.
 */
@Service
public class GeoProviderChain {

  private static final Logger log = LoggerFactory.getLogger(GeoProviderChain.class);

  /**
   * Fallback speed for a straight-line ETA, in metres per minute.
   *
   * <p>300 m/min = 18 km/h, which is a realistic Hyderabad two-wheeler average
   * including signals. The app had two different constants for this (18 and
   * 21.6 km/h) in two screens; this is now the single source.
   */
  private static final double FALLBACK_METRES_PER_MINUTE = 300d;

  private final Map<String, GeoProvider> providersByName = new LinkedHashMap<>();
  private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
  private final Map<String, Long> openUntilEpochMs = new ConcurrentHashMap<>();

  private final GeoProperties props;
  private final GeoCache cache;
  private final MeterRegistry meters;

  public GeoProviderChain(
      List<GeoProvider> providers, GeoProperties props, GeoCache cache, MeterRegistry meters) {
    this.props = props;
    this.cache = cache;
    this.meters = meters;
    for (GeoProvider provider : providers) {
      providersByName.put(provider.name(), provider);
    }
    log.info("Geo providers registered: {}", providersByName.keySet());
  }

  public GeoDtos.SuggestionsResponse autocomplete(String query, Double biasLat, Double biasLng) {
    return autocomplete(query, biasLat, biasLng, false, null);
  }

  /**
   * @param premium use the premium provider order. The caller has already checked
   *     the request's context against the allowlist — this method trusts that
   *     decision and does not re-derive it.
   * @param sessionToken groups this lookup with the rest of one address entry for
   *     billing; may be null
   */
  public GeoDtos.SuggestionsResponse autocomplete(
      String query, Double biasLat, Double biasLng, boolean premium, String sessionToken) {
    if (query == null || query.trim().length() < 2) {
      return new GeoDtos.SuggestionsResponse(List.of(), "none", false);
    }
    String trimmed = query.trim();
    Attempt<List<GeoDtos.PlaceSuggestion>> attempt = cachedAttempt(
        GeoCache.autocompleteKey(trimmed, biasLat, biasLng, premium),
        Duration.ofSeconds(premium
            ? props.getCache().getPremiumAutocompleteTtlSeconds()
            : props.getCache().getAutocompleteTtlSeconds()),
        new TypeReference<>() {},
        premium ? props.getPremiumAutocompleteOrder() : props.getAutocompleteOrder(),
        GeoProvider::supportsAutocomplete,
        provider -> provider.autocomplete(trimmed, biasLat, biasLng, sessionToken),
        "autocomplete");

    // Empty suggestions are a legitimate answer, not an error — the citizen can
    // always drop a pin on the map instead. Never surface an exception here.
    return new GeoDtos.SuggestionsResponse(
        attempt.value == null ? List.of() : attempt.value,
        attempt.provider,
        attempt.value == null);
  }

  public GeoDtos.GeoEnvelope<GeoDtos.PlaceDetail> placeDetails(String prefixedPlaceId) {
    return placeDetails(prefixedPlaceId, null);
  }

  /**
   * Resolves a suggestion to coordinates.
   *
   * <p>The prefix, not {@code placeDetailsOrder}, decides who answers when there is
   * one — which is why Google still serves details for a Google suggestion even
   * though it no longer appears in that order. Anything else would hand a foreign
   * id to a provider that cannot read it.
   */
  public GeoDtos.GeoEnvelope<GeoDtos.PlaceDetail> placeDetails(
      String prefixedPlaceId, String sessionToken) {
    ProviderScopedId scoped = ProviderScopedId.parse(prefixedPlaceId);
    // A place id is only meaningful to the provider that minted it, so the prefix
    // pins the lookup rather than walking the whole chain with a foreign id.
    List<String> order = scoped.provider() == null
        ? props.getPlaceDetailsOrder()
        : List.of(scoped.provider());

    Attempt<GeoDtos.PlaceDetail> attempt = cachedAttempt(
        GeoCache.placeDetailsKey(prefixedPlaceId),
        Duration.ofSeconds(props.getCache().getPlaceDetailsTtlSeconds()),
        new TypeReference<>() {},
        order,
        GeoProvider::supportsPlaceDetails,
        provider -> provider.placeDetails(scoped.rawId(), sessionToken),
        "place_details");

    return attempt.value == null
        ? GeoDtos.GeoEnvelope.degraded(null)
        : GeoDtos.GeoEnvelope.served(attempt.value, attempt.provider);
  }

  public GeoDtos.GeoEnvelope<GeoDtos.ReverseGeocode> reverseGeocode(double lat, double lng) {
    Attempt<GeoDtos.ReverseGeocode> attempt = cachedAttempt(
        GeoCache.reverseGeocodeKey(lat, lng),
        Duration.ofSeconds(props.getCache().getReverseGeocodeTtlSeconds()),
        new TypeReference<>() {},
        props.getReverseGeocodeOrder(),
        GeoProvider::supportsReverseGeocode,
        provider -> provider.reverseGeocode(lat, lng),
        "reverse_geocode");

    if (attempt.value != null) {
      return GeoDtos.GeoEnvelope.served(attempt.value, attempt.provider);
    }
    // Coordinates are still a usable address: the partner navigates by pin, and
    // the citizen typed the landmark themselves. Better than an error banner.
    return GeoDtos.GeoEnvelope.degraded(new GeoDtos.ReverseGeocode(
        String.format("%.5f, %.5f", lat, lng), null, null, lat, lng));
  }

  public GeoDtos.GeoEnvelope<GeoDtos.Route> route(
      double fromLat, double fromLng, double toLat, double toLng) {
    Attempt<GeoDtos.Route> attempt = cachedAttempt(
        GeoCache.routeKey(fromLat, fromLng, toLat, toLng),
        Duration.ofSeconds(props.getCache().getRouteTtlSeconds()),
        new TypeReference<>() {},
        props.getRoutingOrder(),
        GeoProvider::supportsRouting,
        provider -> provider.route(fromLat, fromLng, toLat, toLng),
        "route");

    if (attempt.value != null) {
      return GeoDtos.GeoEnvelope.served(attempt.value, attempt.provider);
    }
    // No geometry, but an ETA is the part the citizen actually reads. Straight-line
    // distance at an urban average is a defensible estimate and keeps the tracking
    // screen informative instead of blank.
    double metres = GeoUtils.distanceMeters(fromLat, fromLng, toLat, toLng);
    int etaSeconds = (int) Math.round(Math.max(1d, metres / FALLBACK_METRES_PER_MINUTE) * 60d);
    return GeoDtos.GeoEnvelope.degraded(
        new GeoDtos.Route(null, etaSeconds, (int) Math.round(metres)));
  }

  /**
   * Travel time from each origin to one destination, for candidate ranking.
   *
   * <p>Prefers a provider that can do the whole matrix in one call (self-hosted
   * OSRM {@code /table}). Falls back to straight-line estimates rather than firing
   * N route requests — ranking degrading to distance-order is fine; a dispatch
   * stalling on N HTTP calls while holding a row lock is not.
   */
  public List<Integer> etaSecondsToDestination(
      List<double[]> origins, double destLat, double destLng) {
    if (origins == null || origins.isEmpty()) return List.of();

    for (String providerName : props.getRoutingOrder()) {
      GeoProvider provider = providersByName.get(providerName);
      if (provider == null || !provider.isEnabled() || !provider.supportsEtaMatrix()) continue;
      if (isCircuitOpen(providerName)) continue;
      try {
        Optional<List<Integer>> matrix = provider.etaMatrixToDestination(origins, destLat, destLng);
        if (matrix.isPresent() && matrix.get().size() == origins.size()) {
          recordSuccess(providerName, "eta_matrix");
          return matrix.get();
        }
        recordFailure(providerName, "eta_matrix");
      } catch (RuntimeException e) {
        log.warn("Geo provider {} threw during eta matrix: {}", providerName, e.getMessage());
        recordFailure(providerName, "eta_matrix");
      }
    }

    meters.counter("geo.request", "capability", "eta_matrix", "provider", "local").increment();
    List<Integer> estimated = new ArrayList<>(origins.size());
    for (double[] origin : origins) {
      double metres = GeoUtils.distanceMeters(origin[0], origin[1], destLat, destLng);
      estimated.add((int) Math.round(Math.max(1d, metres / FALLBACK_METRES_PER_MINUTE) * 60d));
    }
    return estimated;
  }

  /** Result of walking a chain: the value (null if nobody answered) and who served it. */
  private record Attempt<T>(T value, String provider) {}

  private <T> Attempt<T> cachedAttempt(
      String cacheKey,
      Duration ttl,
      TypeReference<T> type,
      List<String> order,
      Function<GeoProvider, Boolean> supports,
      Function<GeoProvider, Optional<T>> call,
      String capability) {

    // The provider that served a cache hit is not worth storing separately; the
    // envelope reports "cache" so hit rate is visible in logs and metrics.
    String[] servedBy = {"cache"};
    Optional<T> value = cache.getOrLoad(cacheKey, ttl, type, () -> {
      for (String providerName : order) {
        GeoProvider provider = providersByName.get(providerName);
        if (provider == null || !provider.isEnabled() || !supports.apply(provider)) continue;
        if (isCircuitOpen(providerName)) continue;
        try {
          Optional<T> result = call.apply(provider);
          if (result.isPresent()) {
            recordSuccess(providerName, capability);
            servedBy[0] = providerName;
            return result;
          }
          recordFailure(providerName, capability);
        } catch (RuntimeException e) {
          // A provider is contractually not allowed to throw, but a bad response
          // shape should still fall through rather than 500 the request.
          log.warn("Geo provider {} threw during {}: {}", providerName, capability, e.getMessage());
          recordFailure(providerName, capability);
        }
      }
      return Optional.empty();
    });

    if (value.isPresent() && "cache".equals(servedBy[0])) {
      meters.counter("geo.request", "capability", capability, "provider", "cache").increment();
    }
    return new Attempt<>(value.orElse(null), value.isPresent() ? servedBy[0] : "none");
  }

  private boolean isCircuitOpen(String providerName) {
    Long openUntil = openUntilEpochMs.get(providerName);
    if (openUntil == null) return false;
    if (System.currentTimeMillis() >= openUntil) {
      // Cooldown elapsed: let one request through to probe recovery.
      openUntilEpochMs.remove(providerName);
      consecutiveFailures.computeIfAbsent(providerName, k -> new AtomicInteger()).set(0);
      return false;
    }
    return true;
  }

  private void recordSuccess(String providerName, String capability) {
    consecutiveFailures.computeIfAbsent(providerName, k -> new AtomicInteger()).set(0);
    openUntilEpochMs.remove(providerName);
    meters.counter("geo.request", "capability", capability, "provider", providerName).increment();
  }

  private void recordFailure(String providerName, String capability) {
    int failures = consecutiveFailures
        .computeIfAbsent(providerName, k -> new AtomicInteger())
        .incrementAndGet();
    meters.counter("geo.failure", "capability", capability, "provider", providerName).increment();
    if (failures >= props.getCircuitBreakerThreshold()) {
      openUntilEpochMs.put(providerName, System.currentTimeMillis() + props.getCircuitBreakerCooldownMs());
      log.warn("Geo provider {} tripped the circuit breaker after {} failures; skipping for {}ms",
          providerName, failures, props.getCircuitBreakerCooldownMs());
    }
  }

  /**
   * A place id carrying the provider that issued it, e.g. {@code ola:ChIJ...}.
   *
   * <p>Without the prefix, a Google place id handed to Ola (or the reverse) is a
   * silent miss that looks like an outage.
   */
  private record ProviderScopedId(String provider, String rawId) {
    static ProviderScopedId parse(String value) {
      if (value == null || value.isBlank()) return new ProviderScopedId(null, "");
      int separator = value.indexOf(':');
      if (separator <= 0 || separator == value.length() - 1) {
        return new ProviderScopedId(null, value);
      }
      return new ProviderScopedId(value.substring(0, separator), value.substring(separator + 1));
    }
  }
}
