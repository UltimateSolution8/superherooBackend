package com.helpinminutes.api.geo.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.geo.GeoDtos;
import com.helpinminutes.api.geo.GeoHttp;
import com.helpinminutes.api.geo.GeoProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OlaGeoProviderTest {

  @Test
  void parsesASingleDestinationDistanceMatrix() throws Exception {
    GeoProperties properties = configuredProperties();
    ObjectMapper mapper = new ObjectMapper();
    RecordingGeoHttp http = new RecordingGeoHttp(mapper.readTree("""
        {"status":"SUCCESS","rows":[
          {"elements":[{"status":"OK","duration":120,"distance":840}]},
          {"elements":[{"status":"OK","duration":300,"distance":2100}]}
        ]}
        """));
    OlaGeoProvider provider = new OlaGeoProvider(properties, http);

    Optional<List<Integer>> result = provider.etaMatrixToDestination(
        List.of(new double[] {17.3850, 78.4867}, new double[] {17.4435, 78.3872}),
        17.4000, 78.4100);

    assertEquals(List.of(120, 300), result.orElseThrow());
    assertTrue(http.url.contains("/routing/v1/distanceMatrix/basic?"));
    assertTrue(http.url.contains("route_preference=fastest"));
    assertTrue(http.url.contains("origins=17.385%2C78.4867%7C17.4435%2C78.3872"));
  }

  @Test
  void declinesMatricesOverOlasFiftyPairLimitWithoutMakingARequest() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    RecordingGeoHttp http = new RecordingGeoHttp(mapper.readTree("{}"));
    OlaGeoProvider provider = new OlaGeoProvider(configuredProperties(), http);
    List<double[]> origins = new ArrayList<>();
    for (int i = 0; i < 51; i++) origins.add(new double[] {17.38 + i / 10000d, 78.48});

    assertFalse(provider.etaMatrixToDestination(origins, 17.4, 78.4).isPresent());
    assertEquals(0, http.calls);
  }

  /**
   * Ola matches its allowed-domains list against the Origin header, not the caller's
   * IP. Omitting it made every call in production fail with
   * {@code 403 {"message":"Domain  is not allowed."}} — which the chain then reported
   * as "no suggestions", so the apps showed an empty dropdown and nothing else.
   */
  @Test
  void sendsTheConfiguredOriginOnEveryCall() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    RecordingGeoHttp autocomplete = new RecordingGeoHttp(mapper.readTree("""
        {"predictions":[{"place_id":"abc","description":"Madhapur, Hyderabad",
          "geometry":{"location":{"lat":17.44,"lng":78.39}}}]}
        """));
    new OlaGeoProvider(configuredProperties(), autocomplete).autocomplete("madhapur", null, null);
    assertEquals("https://origin.test", autocomplete.headers.get("Origin"));

    RecordingGeoHttp reverse = new RecordingGeoHttp(mapper.readTree("""
        {"results":[{"formatted_address":"Madhapur, Hyderabad","address_components":[]}]}
        """));
    new OlaGeoProvider(configuredProperties(), reverse).reverseGeocode(17.44, 78.39);
    assertEquals("https://origin.test", reverse.headers.get("Origin"));

    RecordingGeoHttp details = new RecordingGeoHttp(mapper.readTree("""
        {"result":{"name":"Inorbit Mall","formatted_address":"Madhapur",
          "geometry":{"location":{"lat":17.43,"lng":78.38}}}}
        """));
    new OlaGeoProvider(configuredProperties(), details).placeDetails("abc");
    assertEquals("https://origin.test", details.headers.get("Origin"));

    RecordingGeoHttp matrix = new RecordingGeoHttp(mapper.readTree("""
        {"rows":[{"elements":[{"status":"OK","duration":120,"distance":840}]}]}
        """));
    new OlaGeoProvider(configuredProperties(), matrix)
        .etaMatrixToDestination(List.of(new double[] {17.38, 78.48}), 17.4, 78.4);
    assertEquals("https://origin.test", matrix.headers.get("Origin"));
  }

  /** A blank origin must send no header at all rather than an empty one. */
  @Test
  void omitsTheOriginHeaderWhenNotConfigured() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    RecordingGeoHttp http = new RecordingGeoHttp(mapper.readTree("""
        {"results":[{"formatted_address":"Madhapur","address_components":[]}]}
        """));
    GeoProperties properties = configuredProperties();
    properties.getOla().setOrigin("");

    new OlaGeoProvider(properties, http).reverseGeocode(17.44, 78.39);

    assertTrue(http.headers.isEmpty());
  }

  /**
   * Ola's directions endpoint answers only to POST. A GET of the same URL returns
   * {@code 404 "Route Not Found"}, which is indistinguishable from a genuinely
   * unroutable pair of points.
   */
  @Test
  void requestsDirectionsWithPost() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    RecordingGeoHttp http = new RecordingGeoHttp(mapper.readTree("""
        {"status":"SUCCESS","routes":[{"overview_polyline":"ufmiBuqk}M_@b@",
          "legs":[{"duration":900,"distance":5200}]}]}
        """));

    Optional<GeoDtos.Route> route =
        new OlaGeoProvider(configuredProperties(), http).route(17.44, 78.38, 17.38, 78.48);

    assertEquals("POST", http.method);
    assertEquals("https://origin.test", http.headers.get("Origin"));
    assertTrue(http.url.contains("/routing/v1/directions/basic?"));
    assertEquals(900, route.orElseThrow().etaSeconds().intValue());
    assertEquals(5200, route.orElseThrow().distanceMeters().intValue());
    assertEquals("ufmiBuqk}M_@b@", route.orElseThrow().encodedPolyline());
  }

  private static GeoProperties configuredProperties() {
    GeoProperties properties = new GeoProperties();
    properties.getOla().setApiKey("test-key");
    properties.getOla().setBaseUrl("https://ola.test");
    properties.getOla().setOrigin("https://origin.test");
    return properties;
  }

  private static final class RecordingGeoHttp extends GeoHttp {
    private final JsonNode response;
    private String url;
    private String method;
    private Map<String, String> headers = Map.of();
    private int calls;

    RecordingGeoHttp(JsonNode response) {
      super(new ObjectMapper());
      this.response = response;
    }

    @Override
    public Optional<JsonNode> getJson(
        String requestUrl, int timeoutMs, String providerName, Map<String, String> requestHeaders) {
      return record("GET", requestUrl, requestHeaders);
    }

    @Override
    public Optional<JsonNode> postJson(
        String requestUrl, int timeoutMs, String providerName, Map<String, String> requestHeaders) {
      return record("POST", requestUrl, requestHeaders);
    }

    private Optional<JsonNode> record(
        String requestMethod, String requestUrl, Map<String, String> requestHeaders) {
      calls++;
      method = requestMethod;
      url = requestUrl;
      headers = requestHeaders;
      return Optional.of(response);
    }
  }
}
