package com.helpinminutes.api.geo.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.geo.GeoHttp;
import com.helpinminutes.api.geo.GeoProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards on the self-hosted OSRM provider.
 *
 * <p>Both guards exist because the deployed extract is greater-Hyderabad only, and both
 * failure modes they prevent are quiet rather than loud:
 *
 * <ul>
 *   <li>An out-of-area route makes OSRM answer {@code NoSegment} — so the chain waits a
 *       full timeout to learn what a bbox check answers for free.
 *   <li>A {@code /table} request larger than the server's {@code --max-table-size} fails
 *       <em>entirely</em>, so candidate ranking silently degrades to straight-line
 *       distance with nothing in the logs to explain it.
 * </ul>
 */
class OsrmGeoProviderTest {

  private GeoProperties props;
  private GeoHttp http;
  private OsrmGeoProvider provider;

  /** Somewhere in Hyderabad, comfortably inside the default extract. */
  private static final double HYD_LAT = 17.3850;
  private static final double HYD_LNG = 78.4867;

  /** Vijayawada — same state, well outside a city extract. */
  private static final double VIJAYAWADA_LAT = 16.5062;
  private static final double VIJAYAWADA_LNG = 80.6480;

  @BeforeEach
  void setUp() {
    props = new GeoProperties();
    props.getOsrm().setBaseUrl("http://127.0.0.1:5000");
    http = mock(GeoHttp.class);
    provider = new OsrmGeoProvider(props, http);
  }

  // ─── coverage bbox ────────────────────────────────────────────────────────

  @Test
  void routesWithinTheExtract() {
    when(http.getJson(anyString(), anyInt(), anyString()))
        .thenReturn(Optional.of(routeResponse()));

    assertTrue(provider.route(HYD_LAT, HYD_LNG, 17.4483, 78.3915).isPresent());
    verify(http).getJson(anyString(), anyInt(), anyString());
  }

  @Test
  void declinesARouteLeavingTheExtractWithoutCallingOsrm() {
    Optional<?> result = provider.route(HYD_LAT, HYD_LNG, VIJAYAWADA_LAT, VIJAYAWADA_LNG);

    assertTrue(result.isEmpty());
    // The whole point: no HTTP call, so no timeout spent discovering NoSegment.
    verify(http, never()).getJson(anyString(), anyInt(), anyString());
  }

  @Test
  void declinesARouteStartingOutsideTheExtract() {
    assertTrue(provider.route(VIJAYAWADA_LAT, VIJAYAWADA_LNG, HYD_LAT, HYD_LNG).isEmpty());
    verify(http, never()).getJson(anyString(), anyInt(), anyString());
  }

  @Test
  void treatsABlankBboxAsGlobalCoverage() {
    // The day the extract goes national, this is the only change needed.
    props.getOsrm().setCoverageBbox("");
    when(http.getJson(anyString(), anyInt(), anyString()))
        .thenReturn(Optional.of(routeResponse()));

    assertTrue(provider.route(HYD_LAT, HYD_LNG, VIJAYAWADA_LAT, VIJAYAWADA_LNG).isPresent());
  }

  @Test
  void treatsAMalformedBboxAsGlobalRatherThanBlockingEverything() {
    // A typo in config must not silently disable routing platform-wide.
    props.getOsrm().setCoverageBbox("not,a,bbox");
    when(http.getJson(anyString(), anyInt(), anyString()))
        .thenReturn(Optional.of(routeResponse()));

    assertTrue(provider.route(HYD_LAT, HYD_LNG, 17.4483, 78.3915).isPresent());
  }

  // ─── table size ───────────────────────────────────────────────────────────

  @Test
  void answersAMatrixThatFitsTheServerLimit() {
    props.getOsrm().setMaxTableSize(64);
    when(http.getJson(anyString(), anyInt(), anyString()))
        .thenReturn(Optional.of(tableResponse(63)));

    // 63 origins + 1 destination = 64, exactly the limit.
    assertTrue(provider.etaMatrixToDestination(origins(63), HYD_LAT, HYD_LNG).isPresent());
  }

  @Test
  void declinesAMatrixLargerThanTheServerLimit() {
    props.getOsrm().setMaxTableSize(64);

    // 64 origins + 1 destination = 65. OSRM would reject the whole request, leaving
    // every candidate unscored with nothing in the logs to say why.
    assertTrue(provider.etaMatrixToDestination(origins(64), HYD_LAT, HYD_LNG).isEmpty());
    verify(http, never()).getJson(anyString(), anyInt(), anyString());
  }

  @Test
  void declinesAMatrixWhenAnyCandidateIsOutsideTheExtract() {
    List<double[]> mixed = new ArrayList<>(origins(3));
    mixed.add(new double[] {VIJAYAWADA_LAT, VIJAYAWADA_LNG});

    // Scoring one partner on a fallback estimate while the rest use real travel time
    // would compare them on different scales, so the whole set goes elsewhere.
    assertTrue(provider.etaMatrixToDestination(mixed, HYD_LAT, HYD_LNG).isEmpty());
    verify(http, never()).getJson(anyString(), anyInt(), anyString());
  }

  @Test
  void declinesAMatrixWhenTheDestinationIsOutsideTheExtract() {
    assertTrue(provider.etaMatrixToDestination(origins(3), VIJAYAWADA_LAT, VIJAYAWADA_LNG).isEmpty());
    verify(http, never()).getJson(anyString(), anyInt(), anyString());
  }

  @Test
  void declinesEverythingWhenNoBaseUrlIsConfigured() {
    props.getOsrm().setBaseUrl("");

    assertFalse(provider.isEnabled());
    assertTrue(provider.route(HYD_LAT, HYD_LNG, 17.4483, 78.3915).isEmpty());
    assertTrue(provider.etaMatrixToDestination(origins(2), HYD_LAT, HYD_LNG).isEmpty());
    verify(http, never()).getJson(anyString(), anyInt(), anyString());
  }

  @Test
  void declinesTheTextCapabilitiesOutright() {
    // OSRM has no geocoder. Saying so keeps the chain from spending a timeout on it.
    assertFalse(provider.supportsAutocomplete());
    assertFalse(provider.supportsPlaceDetails());
    assertFalse(provider.supportsReverseGeocode());
    assertTrue(provider.supportsRouting());
    assertTrue(provider.supportsEtaMatrix());
  }

  // ─── fixtures ─────────────────────────────────────────────────────────────

  /** `count` distinct points spread across Hyderabad, all inside the extract. */
  private static List<double[]> origins(int count) {
    List<double[]> origins = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      origins.add(new double[] {HYD_LAT + i * 0.001, HYD_LNG + i * 0.001});
    }
    return origins;
  }

  private static com.fasterxml.jackson.databind.JsonNode routeResponse() {
    return read("{\"routes\":[{\"geometry\":\"_p~iF~ps|U\",\"duration\":420.5,\"distance\":2100.0}]}");
  }

  private static com.fasterxml.jackson.databind.JsonNode tableResponse(int rows) {
    StringBuilder json = new StringBuilder("{\"durations\":[");
    for (int i = 0; i < rows; i++) {
      if (i > 0) json.append(',');
      json.append("[").append(120 + i).append("]");
    }
    return read(json.append("]}").toString());
  }

  private static com.fasterxml.jackson.databind.JsonNode read(String json) {
    try {
      return new ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
