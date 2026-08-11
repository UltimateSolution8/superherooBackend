package com.helpinminutes.api.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract that matters for maps: <b>no geo failure can break a user flow.</b>
 *
 * <p>Autocomplete, geocoding and routing are assists. A citizen can always drop a
 * pin, and a partner can always navigate by coordinates. So every path here has to
 * end in a usable answer even with every provider down — the old code called three
 * public OSM demo endpoints directly from the app with URLs inlined in six screens,
 * and each one had its own ad-hoc failure handling.
 */
class GeoProviderChainTest {

  private GeoProperties props;
  private GeoProviderChain chain;

  @BeforeEach
  void setUp() {
    props = new GeoProperties();
    props.setAutocompleteOrder(List.of("first", "second"));
    props.setReverseGeocodeOrder(List.of("first", "second"));
    props.setPlaceDetailsOrder(List.of("first", "second"));
    props.setRoutingOrder(List.of("first", "second"));
  }

  private GeoProviderChain chainOf(GeoProvider... providers) {
    return new GeoProviderChain(List.of(providers), props, noOpCache(), new SimpleMeterRegistry());
  }

  // ─── fallthrough ──────────────────────────────────────────────────────────

  @Test
  void fallsThroughToTheNextProviderWhenTheFirstReturnsNothing() {
    chain = chainOf(
        new StubProvider("first").withAutocomplete(Optional.empty()),
        new StubProvider("second").withAutocomplete(Optional.of(List.of(suggestion("Hitech City")))));

    var result = chain.autocomplete("hitech", 17.3850, 78.4867);

    assertEquals(1, result.suggestions().size());
    assertEquals("second", result.provider());
    assertFalse(result.degraded());
  }

  @Test
  void fallsThroughWhenAProviderThrows() {
    // Providers are contractually not allowed to throw, but a malformed upstream
    // response should still fall through rather than 500 the request.
    chain = chainOf(
        new StubProvider("first").throwingOnAutocomplete(),
        new StubProvider("second").withAutocomplete(Optional.of(List.of(suggestion("Gachibowli")))));

    var result = chain.autocomplete("gachi", null, null);

    assertEquals("second", result.provider());
    assertEquals(1, result.suggestions().size());
  }

  @Test
  void skipsProvidersThatAreDisabledOrDoNotSupportTheCapability() {
    StubProvider unconfigured = new StubProvider("first").disabled();
    StubProvider working = new StubProvider("second")
        .withAutocomplete(Optional.of(List.of(suggestion("Kondapur"))));

    chain = chainOf(unconfigured, working);
    chain.autocomplete("kondapur", null, null);

    // A provider with no API key must not consume a timeout budget to discover it.
    assertEquals(0, unconfigured.autocompleteCalls.get());
    assertEquals(1, working.autocompleteCalls.get());
  }

  // ─── degraded answers, never errors ───────────────────────────────────────

  @Test
  void everyProviderDownStillReturnsAnEmptySuggestionListRatherThanFailing() {
    chain = chainOf(
        new StubProvider("first").withAutocomplete(Optional.empty()),
        new StubProvider("second").withAutocomplete(Optional.empty()));

    var result = chain.autocomplete("nowhere", null, null);

    assertTrue(result.suggestions().isEmpty());
    assertTrue(result.degraded(), "the app shows a hint, not an error");
  }

  @Test
  void everyProviderDownStillReturnsAnAddressForReverseGeocode() {
    chain = chainOf(new StubProvider("first").withReverse(Optional.empty()));

    var result = chain.reverseGeocode(17.3850, 78.4867);

    assertTrue(result.degraded());
    assertNotNull(result.result());
    // Coordinates are a usable address: the partner navigates by pin anyway, and
    // the citizen typed their own landmark.
    assertTrue(result.result().formattedAddress().contains("17.38"));
    assertEquals(17.3850, result.result().lat());
  }

  @Test
  void everyProviderDownStillReturnsAnEtaForRouting() {
    chain = chainOf(new StubProvider("first").withRoute(Optional.empty()));

    // ~1.55km apart.
    var result = chain.route(17.3850, 78.4867, 17.3990, 78.4867);

    assertTrue(result.degraded());
    assertNotNull(result.result());
    // No geometry to draw, but the ETA is the part the citizen reads. At 18 km/h
    // urban average, ~1.55km is about 5 minutes.
    assertNotNull(result.result().etaSeconds());
    assertTrue(result.result().etaSeconds() >= 240 && result.result().etaSeconds() <= 360,
        "expected a straight-line estimate near 5 min, got " + result.result().etaSeconds() + "s");
  }

  // ─── ETA matrix (candidate ranking) ───────────────────────────────────────

  @Test
  void etaMatrixPrefersAProviderThatCanAnswerTheWholeMatrixAtOnce() {
    StubProvider perRouteOnly = new StubProvider("first").withRoute(Optional.of(route(60)));
    StubProvider matrixCapable = new StubProvider("second").withEtaMatrix(Optional.of(List.of(120, 300)));

    chain = chainOf(perRouteOnly, matrixCapable);
    List<Integer> etas = chain.etaSecondsToDestination(
        List.of(new double[] {17.3860, 78.4867}, new double[] {17.4000, 78.4867}), 17.3850, 78.4867);

    assertEquals(List.of(120, 300), etas);
    // Ranking N candidates must never become N route calls: that would run inside a
    // transaction holding a row lock on the task.
    assertEquals(0, perRouteOnly.routeCalls.get());
  }

  @Test
  void etaMatrixFallsBackToStraightLineWhenNoProviderCanAnswer() {
    chain = chainOf(new StubProvider("first").withEtaMatrix(Optional.empty()));

    List<Integer> etas = chain.etaSecondsToDestination(
        List.of(new double[] {17.3860, 78.4867}, new double[] {17.4000, 78.4867}), 17.3850, 78.4867);

    assertEquals(2, etas.size());
    // Degraded ranking is acceptable; a dispatch that stalls is not.
    assertTrue(etas.get(0) < etas.get(1), "nearer origin should still rank first");
  }

  @Test
  void etaMatrixFallsThroughFromOsrmToOla() {
    props.setRoutingOrder(List.of("osrm", "ola"));
    StubProvider osrm = new StubProvider("osrm").withEtaMatrix(Optional.empty());
    StubProvider ola = new StubProvider("ola").withEtaMatrix(Optional.of(List.of(180, 420)));
    chain = chainOf(osrm, ola);

    List<Integer> etas = chain.etaSecondsToDestination(
        List.of(new double[] {17.3860, 78.4867}, new double[] {17.4000, 78.4867}), 17.3850, 78.4867);

    assertEquals(List.of(180, 420), etas);
  }

  @Test
  void etaMatrixIgnoresAProviderThatReturnsAWrongSizedMatrix() {
    StubProvider mismatched = new StubProvider("first").withEtaMatrix(Optional.of(List.of(120)));
    chain = chainOf(mismatched);

    List<Integer> etas = chain.etaSecondsToDestination(
        List.of(new double[] {17.3860, 78.4867}, new double[] {17.4000, 78.4867}), 17.3850, 78.4867);

    // A short row would silently misalign ETAs with candidates, ranking partners by
    // someone else's travel time.
    assertEquals(2, etas.size());
  }

  // ─── circuit breaker ──────────────────────────────────────────────────────

  @Test
  void stopsCallingAProviderThatKeepsFailing() {
    props.setCircuitBreakerThreshold(3);
    props.setCircuitBreakerCooldownMs(60_000);
    StubProvider broken = new StubProvider("first").withAutocomplete(Optional.empty());
    StubProvider working = new StubProvider("second")
        .withAutocomplete(Optional.of(List.of(suggestion("Madhapur"))));

    chain = chainOf(broken, working);
    for (int i = 0; i < 6; i++) {
      chain.autocomplete("query" + i, null, null);
    }

    // Without the breaker, a provider that is down costs every single request its
    // full timeout before the chain reaches one that works.
    assertEquals(3, broken.autocompleteCalls.get());
    assertEquals(6, working.autocompleteCalls.get());
  }

  // ─── place id routing ─────────────────────────────────────────────────────

  @Test
  void routesAPlaceDetailsLookupToTheProviderThatIssuedTheId() {
    StubProvider ola = new StubProvider("ola").withPlaceDetails(Optional.of(placeDetail()));
    StubProvider google = new StubProvider("google").withPlaceDetails(Optional.of(placeDetail()));
    props.setPlaceDetailsOrder(List.of("ola", "google"));

    chain = chainOf(ola, google);
    chain.placeDetails("google:ChIJabc123");

    // A place id only means something to its issuer. Handing a Google id to Ola is a
    // silent miss that looks like an outage.
    assertEquals(0, ola.placeDetailsCalls.get());
    assertEquals(1, google.placeDetailsCalls.get());
    assertEquals("ChIJabc123", google.lastPlaceId);
  }

  @Test
  void tooShortAQueryNeverReachesAProvider() {
    StubProvider provider = new StubProvider("first")
        .withAutocomplete(Optional.of(List.of(suggestion("a"))));
    chain = chainOf(provider);

    assertTrue(chain.autocomplete("h", null, null).suggestions().isEmpty());
    // One-character predictions are noise, and every call is billable upstream.
    assertEquals(0, provider.autocompleteCalls.get());
  }

  // ─── fixtures ─────────────────────────────────────────────────────────────

  /**
   * A cache that always misses and never stores.
   *
   * <p>Redis is not available in a unit test, and caching is orthogonal to the
   * fallthrough behaviour under test here — a stubbed hit would hide it.
   */
  private static GeoCache noOpCache() {
    return new GeoCache(null, new com.fasterxml.jackson.databind.ObjectMapper()) {
      @Override
      public <T> Optional<T> getOrLoad(
          String key,
          java.time.Duration ttl,
          com.fasterxml.jackson.core.type.TypeReference<T> type,
          java.util.function.Supplier<Optional<T>> loader) {
        return loader.get();
      }
    };
  }

  private static GeoDtos.PlaceSuggestion suggestion(String text) {
    return new GeoDtos.PlaceSuggestion("stub:1", text, "Hyderabad", text, 17.3850, 78.4867, null);
  }

  private static GeoDtos.PlaceDetail placeDetail() {
    return new GeoDtos.PlaceDetail("stub:1", "Madhapur, Hyderabad", "Madhapur", 17.4483, 78.3915);
  }

  private static GeoDtos.Route route(int etaSeconds) {
    return new GeoDtos.Route("_p~iF~ps|U", etaSeconds, 1000);
  }

  /** Configurable provider that records how often each capability was invoked. */
  private static final class StubProvider implements GeoProvider {
    private final String name;
    private boolean enabled = true;
    private boolean throwOnAutocomplete;
    private Optional<List<GeoDtos.PlaceSuggestion>> autocomplete;
    private Optional<GeoDtos.PlaceDetail> placeDetails;
    private Optional<GeoDtos.ReverseGeocode> reverse;
    private Optional<GeoDtos.Route> route;
    private Optional<List<Integer>> etaMatrix;

    final AtomicInteger autocompleteCalls = new AtomicInteger();
    final AtomicInteger placeDetailsCalls = new AtomicInteger();
    final AtomicInteger routeCalls = new AtomicInteger();
    String lastPlaceId;

    StubProvider(String name) {
      this.name = name;
    }

    StubProvider disabled() {
      this.enabled = false;
      return this;
    }

    StubProvider throwingOnAutocomplete() {
      this.throwOnAutocomplete = true;
      this.autocomplete = Optional.empty();
      return this;
    }

    StubProvider withAutocomplete(Optional<List<GeoDtos.PlaceSuggestion>> value) {
      this.autocomplete = value;
      return this;
    }

    StubProvider withPlaceDetails(Optional<GeoDtos.PlaceDetail> value) {
      this.placeDetails = value;
      return this;
    }

    StubProvider withReverse(Optional<GeoDtos.ReverseGeocode> value) {
      this.reverse = value;
      return this;
    }

    StubProvider withRoute(Optional<GeoDtos.Route> value) {
      this.route = value;
      return this;
    }

    StubProvider withEtaMatrix(Optional<List<Integer>> value) {
      this.etaMatrix = value;
      return this;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean isEnabled() {
      return enabled;
    }

    @Override
    public boolean supportsAutocomplete() {
      return autocomplete != null || throwOnAutocomplete;
    }

    @Override
    public boolean supportsPlaceDetails() {
      return placeDetails != null;
    }

    @Override
    public boolean supportsReverseGeocode() {
      return reverse != null;
    }

    @Override
    public boolean supportsRouting() {
      return route != null;
    }

    @Override
    public boolean supportsEtaMatrix() {
      return etaMatrix != null;
    }

    @Override
    public Optional<List<GeoDtos.PlaceSuggestion>> autocomplete(
        String query, Double biasLat, Double biasLng) {
      autocompleteCalls.incrementAndGet();
      if (throwOnAutocomplete) throw new IllegalStateException("upstream returned garbage");
      return autocomplete;
    }

    @Override
    public Optional<GeoDtos.PlaceDetail> placeDetails(String providerPlaceId) {
      placeDetailsCalls.incrementAndGet();
      lastPlaceId = providerPlaceId;
      return placeDetails;
    }

    @Override
    public Optional<GeoDtos.ReverseGeocode> reverseGeocode(double lat, double lng) {
      return reverse;
    }

    @Override
    public Optional<GeoDtos.Route> route(double fromLat, double fromLng, double toLat, double toLng) {
      routeCalls.incrementAndGet();
      return route;
    }

    @Override
    public Optional<List<Integer>> etaMatrixToDestination(
        List<double[]> origins, double destLat, double destLng) {
      return etaMatrix;
    }
  }
}
