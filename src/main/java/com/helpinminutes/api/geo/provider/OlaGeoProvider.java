package com.helpinminutes.api.geo.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.helpinminutes.api.geo.GeoDtos;
import com.helpinminutes.api.geo.GeoHttp;
import com.helpinminutes.api.geo.GeoProperties;
import com.helpinminutes.api.geo.GeoProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ola Maps (Krutrim) — the primary provider for the billable text APIs.
 *
 * <p>Chosen over Google for autocomplete, place details and reverse geocoding:
 * 500k calls/month free across all APIs, India-native address data, and roughly
 * half Google's rate beyond the free tier. Google's equivalent SKUs give 10k each.
 *
 * <p>Auth is an {@code api_key} query parameter <i>plus</i> an {@code Origin} header
 * that has to match the credential's allowed-domains list — Ola checks the header,
 * not the caller's IP, so a server that sends no origin is refused. The key never
 * leaves the server; that is the point of proxying these calls rather than letting
 * the APK hold a key with Places permissions.
 */
@Component
public class OlaGeoProvider implements GeoProvider {

  private static final String NAME = "ola";
  /** Ola's Basic Distance Matrix API permits at most 50 origin×destination pairs. */
  private static final int MAX_MATRIX_PAIRS = 50;

  private final GeoProperties props;
  private final GeoHttp http;

  public OlaGeoProvider(GeoProperties props, GeoHttp http) {
    this.props = props;
    this.http = http;
  }

  /**
   * The allowlist header every Ola call must carry.
   *
   * <p>Built per call rather than cached so a config refresh takes effect without a
   * restart; it is a map of one short string, not something worth holding on to.
   */
  private Map<String, String> olaHeaders() {
    String origin = props.getOla().getOrigin();
    return origin == null || origin.isBlank() ? Map.of() : Map.of("Origin", origin);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled() {
    return props.getOla().getApiKey() != null && !props.getOla().getApiKey().isBlank();
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
  public boolean supportsEtaMatrix() {
    return true;
  }

  @Override
  public Optional<List<GeoDtos.PlaceSuggestion>> autocomplete(
      String query, Double biasLat, Double biasLng) {
    if (!isEnabled() || query == null || query.isBlank()) return Optional.empty();
    double lat = biasLat != null ? biasLat : props.getDefaultBiasLat();
    double lng = biasLng != null ? biasLng : props.getDefaultBiasLng();
    String url = props.getOla().getBaseUrl()
        + "/places/v1/autocomplete?input=" + GeoHttp.encode(query)
        + "&location=" + lat + "," + lng
        + "&api_key=" + GeoHttp.encode(props.getOla().getApiKey());

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME, olaHeaders()).map(root -> {
      List<GeoDtos.PlaceSuggestion> out = new ArrayList<>();
      for (JsonNode prediction : root.path("predictions")) {
        String placeId = GeoHttp.asText(prediction.path("place_id"));
        String description = GeoHttp.asText(prediction.path("description"));
        if (placeId == null && description == null) continue;
        JsonNode location = prediction.path("geometry").path("location");
        out.add(new GeoDtos.PlaceSuggestion(
            placeId == null ? null : NAME + ":" + placeId,
            firstNonBlank(
                GeoHttp.asText(prediction.path("structured_formatting").path("main_text")),
                GeoHttp.asText(prediction.path("name")),
                description),
            firstNonBlank(
                GeoHttp.asText(prediction.path("structured_formatting").path("secondary_text")),
                GeoHttp.asText(prediction.path("formatted_address"))),
            description,
            GeoHttp.asDouble(location.path("lat")),
            GeoHttp.asDouble(location.path("lng")),
            GeoHttp.asDouble(prediction.path("distance_meters"))));
      }
      return out;
    }).filter(list -> !list.isEmpty());
  }

  @Override
  public Optional<GeoDtos.PlaceDetail> placeDetails(String providerPlaceId) {
    if (!isEnabled() || providerPlaceId == null || providerPlaceId.isBlank()) return Optional.empty();
    String url = props.getOla().getBaseUrl()
        + "/places/v1/details?place_id=" + GeoHttp.encode(providerPlaceId)
        + "&api_key=" + GeoHttp.encode(props.getOla().getApiKey());

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME, olaHeaders()).flatMap(root -> {
      JsonNode result = root.path("result");
      JsonNode location = result.path("geometry").path("location");
      Double lat = GeoHttp.asDouble(location.path("lat"));
      Double lng = GeoHttp.asDouble(location.path("lng"));
      if (lat == null || lng == null) return Optional.empty();
      return Optional.of(new GeoDtos.PlaceDetail(
          NAME + ":" + providerPlaceId,
          firstNonBlank(
              GeoHttp.asText(result.path("formatted_address")),
              GeoHttp.asText(result.path("name"))),
          GeoHttp.asText(result.path("name")),
          lat,
          lng));
    });
  }

  @Override
  public Optional<GeoDtos.ReverseGeocode> reverseGeocode(double lat, double lng) {
    if (!isEnabled()) return Optional.empty();
    String url = props.getOla().getBaseUrl()
        + "/places/v1/reverse-geocode?latlng=" + lat + "," + lng
        + "&api_key=" + GeoHttp.encode(props.getOla().getApiKey());

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME, olaHeaders()).flatMap(root -> {
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

  @Override
  public Optional<GeoDtos.Route> route(double fromLat, double fromLng, double toLat, double toLng) {
    if (!isEnabled()) return Optional.empty();
    String url = props.getOla().getBaseUrl()
        + "/routing/v1/directions/basic?origin=" + fromLat + "," + fromLng
        + "&destination=" + toLat + "," + toLng
        + "&api_key=" + GeoHttp.encode(props.getOla().getApiKey());

    // POST, not GET. Ola's directions endpoint takes its parameters in the query
    // string but only answers to POST; a GET of the identical URL returns
    // `404 {"error_msg":"404 Route Not Found"}`, which reads like "no route exists
    // between these points" rather than "wrong method". There is no request body.
    return http.postJson(url, props.getPerProviderTimeoutMs(), NAME, olaHeaders()).flatMap(root -> {
      JsonNode route = root.path("routes").path(0);
      String polyline = firstNonBlank(
          GeoHttp.asText(route.path("overview_polyline")),
          GeoHttp.asText(route.path("geometry")));
      JsonNode leg = route.path("legs").path(0);
      Double durationSeconds = firstNumber(
          GeoHttp.asDouble(leg.path("duration")),
          GeoHttp.asDouble(leg.path("readable_duration")),
          GeoHttp.asDouble(route.path("duration")));
      Double distance = firstNumber(
          GeoHttp.asDouble(leg.path("distance")),
          GeoHttp.asDouble(route.path("distance")));
      if (polyline == null && durationSeconds == null) return Optional.empty();
      return Optional.of(new GeoDtos.Route(
          polyline,
          durationSeconds == null ? null : (int) Math.round(durationSeconds),
          distance == null ? null : (int) Math.round(distance)));
    });
  }

  /**
   * Uses Ola's single-destination Distance Matrix endpoint when the local OSRM
   * service is unavailable. Keeping this as one matrix request is important: a
   * dispatch holds a task row lock and must never turn into N directions calls.
   */
  @Override
  public Optional<List<Integer>> etaMatrixToDestination(
      List<double[]> origins, double destLat, double destLng) {
    if (!isEnabled() || origins == null || origins.isEmpty() || origins.size() > MAX_MATRIX_PAIRS) {
      return Optional.empty();
    }

    StringBuilder encodedOrigins = new StringBuilder();
    for (double[] origin : origins) {
      if (origin == null || origin.length < 2
          || !Double.isFinite(origin[0]) || !Double.isFinite(origin[1])) {
        return Optional.empty();
      }
      if (encodedOrigins.length() > 0) encodedOrigins.append('|');
      encodedOrigins.append(origin[0]).append(',').append(origin[1]);
    }
    String destination = destLat + "," + destLng;
    String url = props.getOla().getBaseUrl()
        + "/routing/v1/distanceMatrix/basic?origins=" + GeoHttp.encode(encodedOrigins.toString())
        + "&destinations=" + GeoHttp.encode(destination)
        + "&route_preference=fastest"
        + "&api_key=" + GeoHttp.encode(props.getOla().getApiKey());

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME, olaHeaders()).flatMap(root -> {
      List<Integer> etas = new ArrayList<>(origins.size());
      JsonNode rows = root.path("rows");
      if (!rows.isArray() || rows.size() != origins.size()) return Optional.empty();
      for (JsonNode row : rows) {
        JsonNode element = row.path("elements").path(0);
        if (!"OK".equalsIgnoreCase(GeoHttp.asText(element.path("status")))) return Optional.empty();
        Double duration = GeoHttp.asDouble(element.path("duration"));
        if (duration == null || duration <= 0d) return Optional.empty();
        etas.add((int) Math.round(duration));
      }
      return Optional.of(etas);
    });
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

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  private static Double firstNumber(Double... values) {
    for (Double value : values) {
      if (value != null && value > 0) return value;
    }
    return null;
  }
}
