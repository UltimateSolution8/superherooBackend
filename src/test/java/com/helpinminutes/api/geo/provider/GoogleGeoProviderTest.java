package com.helpinminutes.api.geo.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.geo.GeoDtos;
import com.helpinminutes.api.geo.GeoHttp;
import com.helpinminutes.api.geo.GeoProperties;
import com.helpinminutes.api.geo.GeoSpendGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Google is the only provider we pay per call, so the things that decide the bill
 * are worth pinning down: that we call the Places API (New) endpoints at all, that
 * place details asks only for the Essentials fields, and that both halves of an
 * address entry carry the session token that makes them bill as one.
 */
class GoogleGeoProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void autocompleteCallsPlacesNewAndKeepsTheKeyOutOfTheUrl() throws Exception {
    RecordingGeoHttp http = new RecordingGeoHttp(MAPPER.readTree("""
        {"suggestions":[{"placePrediction":{
          "placeId":"ChIJmadhapur",
          "text":{"text":"Madhapur, Hyderabad, Telangana"},
          "structuredFormat":{"mainText":{"text":"Madhapur"},
                              "secondaryText":{"text":"Hyderabad, Telangana"}},
          "distanceMeters":4200}}]}
        """));

    List<GeoDtos.PlaceSuggestion> suggestions =
        provider(http).autocomplete("madhapur", 17.44, 78.39).orElseThrow();

    assertEquals("POST", http.method);
    assertEquals("https://places.googleapis.com/v1/places:autocomplete", http.url);
    // The key travels in a header, so it cannot leak through a logged URL.
    assertFalse(http.url.contains("key"));
    assertEquals("test-key", http.headers.get("X-Goog-Api-Key"));

    GeoDtos.PlaceSuggestion first = suggestions.get(0);
    assertEquals("google:ChIJmadhapur", first.placeId());
    assertEquals("Madhapur", first.primaryText());
    assertEquals("Hyderabad, Telangana", first.secondaryText());
    assertEquals("Madhapur, Hyderabad, Telangana", first.description());
    assertEquals(4200d, first.distanceMeters());
    // Autocomplete never carries coordinates; null is how the caller knows to
    // resolve them.
    assertNull(first.lat());
  }

  @Test
  void autocompleteBiasesToTheServiceAreaAndAsksForDistances() throws Exception {
    RecordingGeoHttp http = new RecordingGeoHttp(MAPPER.readTree("{\"suggestions\":[]}"));

    provider(http).autocomplete("madhapur", 17.44, 78.39);

    JsonNode body = MAPPER.valueToTree(http.body);
    assertEquals("madhapur", body.path("input").asText());
    assertEquals("in", body.path("includedRegionCodes").path(0).asText());
    assertEquals(17.44, body.path("locationBias").path("circle").path("center")
        .path("latitude").asDouble());
    // origin is what makes each prediction come back with distanceMeters.
    assertEquals(17.44, body.path("origin").path("latitude").asDouble());
  }

  /**
   * The field mask is the price of the call. {@code id,location} bills at Place
   * Details Essentials; adding {@code formattedAddress} or
   * {@code displayName} moves it to Pro ($17/1k, 5k free) to fetch a label the
   * autocomplete response already supplied.
   */
  @Test
  void placeDetailsRequestsOnlyTheEssentialsFields() throws Exception {
    RecordingGeoHttp http = new RecordingGeoHttp(MAPPER.readTree("""
        {"id":"ChIJmadhapur","location":{"latitude":17.4435,"longitude":78.3772}}
        """));

    GeoDtos.PlaceDetail detail = provider(http).placeDetails("ChIJmadhapur").orElseThrow();

    assertEquals("GET", http.method);
    assertEquals("https://places.googleapis.com/v1/places/ChIJmadhapur", http.url);
    assertEquals("id,location", http.headers.get("X-Goog-FieldMask"));
    assertEquals(17.4435, detail.lat());
    assertEquals(78.3772, detail.lng());
    assertEquals("google:ChIJmadhapur", detail.placeId());
  }

  /**
   * The session token is what stops each keystroke being billed on its own.
   *
   * <p>Google groups the autocompletes and the details call that shares their token
   * into one session; past the twelfth request in a session the rest bill as
   * Autocomplete Session Usage, which is free and unlimited. Both halves have to
   * carry it — a details call without the token leaves the session unterminated and
   * every autocomplete in it reverts to per-request pricing.
   */
  @Test
  void carriesTheSessionTokenThroughBothHalvesOfAnAddressEntry() throws Exception {
    RecordingGeoHttp autocompleteHttp =
        new RecordingGeoHttp(MAPPER.readTree("{\"suggestions\":[]}"));
    provider(autocompleteHttp).autocomplete("madhapur", 17.44, 78.39, "session-abc");
    assertEquals(
        "session-abc",
        MAPPER.valueToTree(autocompleteHttp.body).path("sessionToken").asText());

    RecordingGeoHttp detailsHttp = new RecordingGeoHttp(MAPPER.readTree("""
        {"id":"ChIJmadhapur","location":{"latitude":17.4435,"longitude":78.3772}}
        """));
    provider(detailsHttp).placeDetails("ChIJmadhapur", "session-abc");
    assertEquals(
        "https://places.googleapis.com/v1/places/ChIJmadhapur?sessionToken=session-abc",
        detailsHttp.url);
  }

  /**
   * A caller with no session — anything reaching Google as a fallback rather than
   * through the create-task path — must still work, just at per-request pricing.
   */
  @Test
  void omitsTheSessionTokenWhenThereIsNone() throws Exception {
    RecordingGeoHttp http = new RecordingGeoHttp(MAPPER.readTree("{\"suggestions\":[]}"));

    provider(http).autocomplete("madhapur", 17.44, 78.39, "   ");

    assertFalse(MAPPER.valueToTree(http.body).has("sessionToken"));
  }

  /** Past the monthly cap every paid capability declines, so the chain falls back. */
  @Test
  void stopsSpendingOnceTheMonthlyCapIsReached() throws Exception {
    RecordingGeoHttp http = new RecordingGeoHttp(MAPPER.readTree("{\"suggestions\":[]}"));
    GoogleGeoProvider provider =
        new GoogleGeoProvider(configuredProperties(), http, new DeniedSpendGuard());

    assertTrue(provider.autocomplete("madhapur", null, null).isEmpty());
    assertTrue(provider.placeDetails("ChIJmadhapur").isEmpty());
    assertTrue(provider.reverseGeocode(17.44, 78.39).isEmpty());
    assertTrue(provider.route(17.44, 78.38, 17.38, 78.48).isEmpty());
    assertEquals(0, http.calls);
  }

  private static GoogleGeoProvider provider(GeoHttp http) {
    return new GoogleGeoProvider(configuredProperties(), http, new AllowingSpendGuard());
  }

  private static GeoProperties configuredProperties() {
    GeoProperties properties = new GeoProperties();
    properties.getGoogle().setApiKey("test-key");
    properties.getGoogle().setBaseUrl("https://maps.test");
    return properties;
  }

  private static final class AllowingSpendGuard extends GeoSpendGuard {
    AllowingSpendGuard() {
      super(null, new GeoProperties());
    }

    @Override
    public boolean tryConsume(String capability) {
      return true;
    }
  }

  private static final class DeniedSpendGuard extends GeoSpendGuard {
    DeniedSpendGuard() {
      super(null, new GeoProperties());
    }

    @Override
    public boolean tryConsume(String capability) {
      return false;
    }
  }

  private static final class RecordingGeoHttp extends GeoHttp {
    private final JsonNode response;
    private String url;
    private String method;
    private Object body;
    private Map<String, String> headers = Map.of();
    private int calls;

    RecordingGeoHttp(JsonNode response) {
      super(new ObjectMapper());
      this.response = response;
    }

    @Override
    public Optional<JsonNode> getJson(
        String requestUrl, int timeoutMs, String providerName, Map<String, String> requestHeaders) {
      return record("GET", requestUrl, null, requestHeaders);
    }

    @Override
    public Optional<JsonNode> getJson(String requestUrl, int timeoutMs, String providerName) {
      return record("GET", requestUrl, null, Map.of());
    }

    @Override
    public Optional<JsonNode> postJson(
        String requestUrl,
        Object requestBody,
        int timeoutMs,
        String providerName,
        Map<String, String> requestHeaders) {
      return record("POST", requestUrl, requestBody, requestHeaders);
    }

    private Optional<JsonNode> record(
        String requestMethod,
        String requestUrl,
        Object requestBody,
        Map<String, String> requestHeaders) {
      calls++;
      method = requestMethod;
      url = requestUrl;
      body = requestBody;
      headers = requestHeaders;
      return Optional.of(response);
    }
  }
}
