package com.helpinminutes.api.geo.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.helpinminutes.api.geo.GeoDtos;
import com.helpinminutes.api.geo.GeoHttp;
import com.helpinminutes.api.geo.GeoProperties;
import com.helpinminutes.api.geo.GeoProvider;
import com.helpinminutes.api.geo.GeoSpendGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Google Maps Platform — reached on one screen, and as a fallback everywhere else.
 *
 * <p>Google answers autocomplete only for requests the server has priced as premium
 * (see {@code GeoProperties.premiumContexts} — today, address entry while creating a
 * task), plus the details lookup that resolving one of its suggestions requires.
 * Every other geo call in the product is served by Ola or by self-hosted OSRM, and
 * reaches this class only if those are down.
 *
 * <p>Three deliberate choices keep the resulting bill at or near zero:
 *
 * <ul>
 *   <li><b>Places API (New)</b>, not the legacy {@code /maps/api/place} endpoints.
 *       New projects can no longer enable the legacy Places API at all, so the old
 *       path was a trap waiting for the next key rotation.
 *   <li><b>A field mask of {@code id,location} on place details.</b> Adding
 *       {@code formattedAddress} or {@code displayName} moves that call from the
 *       Essentials SKU to Pro — 3.4× the price and half the free tier — to fetch a
 *       label the autocomplete response already gave us.
 *   <li><b>A session token on both halves of an address entry.</b> Without one,
 *       every keystroke is a separately billed Autocomplete request. With one, the
 *       keystrokes past the twelfth in a session bill as Autocomplete Session Usage,
 *       which is free and unlimited — so a session has a bounded price no matter how
 *       long the citizen types.
 * </ul>
 *
 * <p>Routing stays behind OSRM and Ola: it is our highest-frequency geo call by an
 * order of magnitude, and self-hosted routing is free.
 *
 * <p>Note what is <i>not</i> here: map rendering. The Maps SDK for Android map load
 * is a separate SKU billed at nothing, unlimited, so the map canvas stays on Google
 * in the app with a package-restricted key. This class only covers the calls that
 * cost money.
 */
@Component
public class GoogleGeoProvider implements GeoProvider {

  private static final String NAME = "google";

  /** Places API (New) lives on its own host, not on {@code maps.googleapis.com}. */
  private static final String PLACES_BASE_URL = "https://places.googleapis.com";

  /** Bias radius for autocomplete, in metres. Greater Hyderabad. */
  private static final double BIAS_RADIUS_METERS = 25_000d;

  private final GeoProperties props;
  private final GeoHttp http;
  private final GeoSpendGuard spendGuard;

  public GoogleGeoProvider(GeoProperties props, GeoHttp http, GeoSpendGuard spendGuard) {
    this.props = props;
    this.http = http;
    this.spendGuard = spendGuard;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled() {
    return props.getGoogle().getApiKey() != null && !props.getGoogle().getApiKey().isBlank();
  }

  @Override
  public boolean supportsAutocomplete() {
    return true;
  }

  @Override
  public boolean supportsPlaceDetails() {
    return true;
  }

  @Override
  public boolean supportsReverseGeocode() {
    return true;
  }

  @Override
  public boolean supportsRouting() {
    return true;
  }

  @Override
  public Optional<List<GeoDtos.PlaceSuggestion>> autocomplete(
      String query, Double biasLat, Double biasLng) {
    return autocomplete(query, biasLat, biasLng, null);
  }

  @Override
  public Optional<List<GeoDtos.PlaceSuggestion>> autocomplete(
      String query, Double biasLat, Double biasLng, String sessionToken) {
    if (!isEnabled() || query == null || query.isBlank()) return Optional.empty();
    if (!spendGuard.tryConsume("autocomplete")) return Optional.empty();

    double lat = biasLat != null ? biasLat : props.getDefaultBiasLat();
    double lng = biasLng != null ? biasLng : props.getDefaultBiasLng();

    Map<String, Object> centre = new LinkedHashMap<>();
    centre.put("latitude", lat);
    centre.put("longitude", lng);
    Map<String, Object> circle = new LinkedHashMap<>();
    circle.put("center", centre);
    circle.put("radius", BIAS_RADIUS_METERS);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("input", query);
    body.put("includedRegionCodes", List.of("in"));
    body.put("locationBias", Map.of("circle", circle));
    // Same point as the bias, so each prediction comes back with distanceMeters.
    // The app sorts on it and it costs nothing extra.
    body.put("origin", centre);
    // Omitting this bills every keystroke separately; reusing a spent one is billed
    // as though it were omitted, which is why the app rotates after each pick.
    if (sessionToken != null && !sessionToken.isBlank()) {
      body.put("sessionToken", sessionToken);
    }

    return http.postJson(
            PLACES_BASE_URL + "/v1/places:autocomplete",
            body,
            props.getPerProviderTimeoutMs(),
            NAME,
            authHeaders(null))
        .map(root -> {
          List<GeoDtos.PlaceSuggestion> out = new ArrayList<>();
          for (JsonNode suggestion : root.path("suggestions")) {
            JsonNode prediction = suggestion.path("placePrediction");
            String placeId = GeoHttp.asText(prediction.path("placeId"));
            if (placeId == null) continue;
            JsonNode structured = prediction.path("structuredFormat");
            out.add(new GeoDtos.PlaceSuggestion(
                NAME + ":" + placeId,
                GeoHttp.asText(structured.path("mainText").path("text")),
                GeoHttp.asText(structured.path("secondaryText").path("text")),
                GeoHttp.asText(prediction.path("text").path("text")),
                // Autocomplete never carries coordinates; a details call resolves
                // them. Null lat/lng is how the caller knows that.
                null,
                null,
                GeoHttp.asDouble(prediction.path("distanceMeters"))));
          }
          return out;
        })
        .filter(list -> !list.isEmpty());
  }

  @Override
  public Optional<GeoDtos.PlaceDetail> placeDetails(String providerPlaceId) {
    return placeDetails(providerPlaceId, null);
  }

  @Override
  public Optional<GeoDtos.PlaceDetail> placeDetails(String providerPlaceId, String sessionToken) {
    if (!isEnabled() || providerPlaceId == null || providerPlaceId.isBlank()) {
      return Optional.empty();
    }
    if (!spendGuard.tryConsume("placeDetails")) return Optional.empty();

    // This call is what closes the billing session opened by the autocompletes.
    String url = PLACES_BASE_URL + "/v1/places/" + GeoHttp.encode(providerPlaceId);
    if (sessionToken != null && !sessionToken.isBlank()) {
      url += "?sessionToken=" + GeoHttp.encode(sessionToken);
    }

    // Essentials field mask. See the class comment: asking for the address here
    // would triple the price of a call whose only job is to supply coordinates.
    return http.getJson(
            url,
            props.getPerProviderTimeoutMs(),
            NAME,
            authHeaders("id,location"))
        .flatMap(root -> {
          Double lat = GeoHttp.asDouble(root.path("location").path("latitude"));
          Double lng = GeoHttp.asDouble(root.path("location").path("longitude"));
          if (lat == null || lng == null) return Optional.empty();
          return Optional.of(new GeoDtos.PlaceDetail(
              NAME + ":" + providerPlaceId,
              // The label comes from the suggestion the citizen tapped, which the
              // app already holds; resolveSuggestion falls back to it.
              null,
              null,
              lat,
              lng));
        });
  }

  @Override
  public Optional<GeoDtos.ReverseGeocode> reverseGeocode(double lat, double lng) {
    if (!isEnabled()) return Optional.empty();
    if (!spendGuard.tryConsume("reverseGeocode")) return Optional.empty();

    String url = props.getGoogle().getBaseUrl()
        + "/maps/api/geocode/json?latlng=" + lat + "," + lng
        + "&key=" + GeoHttp.encode(props.getGoogle().getApiKey());

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME).flatMap(root -> {
      JsonNode first = root.path("results").path(0);
      String address = GeoHttp.asText(first.path("formatted_address"));
      if (address == null) return Optional.empty();
      return Optional.of(new GeoDtos.ReverseGeocode(
          address,
          componentOfType(first, "locality"),
          componentOfType(first, "postal_code"),
          lat,
          lng));
    });
  }

  /**
   * Driving route. Last resort only — OSRM and Ola answer first.
   *
   * <p>Not metered by {@link GeoSpendGuard}: the guard exists to bound discretionary
   * search spend, and routing only reaches Google when both cheaper routers are
   * already down. Cutting it off then would leave a partner with no route at all.
   */
  @Override
  public Optional<GeoDtos.Route> route(double fromLat, double fromLng, double toLat, double toLng) {
    if (!isEnabled()) return Optional.empty();
    String url = props.getGoogle().getBaseUrl()
        + "/maps/api/directions/json?origin=" + fromLat + "," + fromLng
        + "&destination=" + toLat + "," + toLng
        + "&mode=driving"
        + "&key=" + GeoHttp.encode(props.getGoogle().getApiKey());

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME).flatMap(root -> {
      JsonNode route = root.path("routes").path(0);
      String polyline = GeoHttp.asText(route.path("overview_polyline").path("points"));
      JsonNode leg = route.path("legs").path(0);
      Double duration = GeoHttp.asDouble(leg.path("duration").path("value"));
      Double distance = GeoHttp.asDouble(leg.path("distance").path("value"));
      if (polyline == null && duration == null) return Optional.empty();
      return Optional.of(new GeoDtos.Route(
          polyline,
          duration == null ? null : (int) Math.round(duration),
          distance == null ? null : (int) Math.round(distance)));
    });
  }

  /**
   * Places API (New) headers.
   *
   * <p>The key travels in a header rather than the query string, which is strictly
   * better: it stays out of URLs, and so out of any log line that carries one.
   */
  private Map<String, String> authHeaders(String fieldMask) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Goog-Api-Key", props.getGoogle().getApiKey());
    if (fieldMask != null) {
      headers.put("X-Goog-FieldMask", fieldMask);
    }
    return headers;
  }

  private static String componentOfType(JsonNode result, String type) {
    for (JsonNode component : result.path("address_components")) {
      for (JsonNode componentType : component.path("types")) {
        if (type.equals(componentType.asText())) {
          return GeoHttp.asText(component.path("long_name"));
        }
      }
    }
    return null;
  }
}
