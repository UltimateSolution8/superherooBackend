package com.helpinminutes.api.geo.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.helpinminutes.api.geo.GeoDtos;
import com.helpinminutes.api.geo.GeoHttp;
import com.helpinminutes.api.geo.GeoProperties;
import com.helpinminutes.api.geo.GeoProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Google Maps Platform — last resort for the billable text and routing SKUs.
 *
 * <p>Placed last on purpose. Each of these SKUs gives 10k free calls a month
 * (Autocomplete $2.83/1k, Place Details / Geocoding / Directions $5/1k beyond it),
 * so as the residual provider behind Ola and OSRM it should stay comfortably free
 * while still being the thing that answers when they are down.
 *
 * <p>Note what is <i>not</i> here: map rendering. The Maps SDK for Android/iOS map
 * load is a separate SKU billed at nothing, unlimited, so the map canvas stays on
 * Google in the app with a package-restricted key. This class only replaces the
 * calls that cost money.
 */
@Component
public class GoogleGeoProvider implements GeoProvider {

  private static final String NAME = "google";

  private final GeoProperties props;
  private final GeoHttp http;

  public GoogleGeoProvider(GeoProperties props, GeoHttp http) {
    this.props = props;
    this.http = http;
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
    if (!isEnabled() || query == null || query.isBlank()) return Optional.empty();
    double lat = biasLat != null ? biasLat : props.getDefaultBiasLat();
    double lng = biasLng != null ? biasLng : props.getDefaultBiasLng();
    String url = props.getGoogle().getBaseUrl()
        + "/maps/api/place/autocomplete/json?input=" + GeoHttp.encode(query)
        + "&components=country:in"
        + "&location=" + lat + "," + lng
        + "&radius=25000"
        + "&key=" + GeoHttp.encode(props.getGoogle().getApiKey());

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME).map(root -> {
      List<GeoDtos.PlaceSuggestion> out = new ArrayList<>();
      for (JsonNode prediction : root.path("predictions")) {
        String placeId = GeoHttp.asText(prediction.path("place_id"));
        if (placeId == null) continue;
        // Google's autocomplete never returns coordinates; a details call is
        // required. Leaving lat/lng null tells the caller that.
        out.add(new GeoDtos.PlaceSuggestion(
            NAME + ":" + placeId,
            GeoHttp.asText(prediction.path("structured_formatting").path("main_text")),
            GeoHttp.asText(prediction.path("structured_formatting").path("secondary_text")),
            GeoHttp.asText(prediction.path("description")),
            null,
            null,
            null));
      }
      return out;
    }).filter(list -> !list.isEmpty());
  }

  @Override
  public Optional<GeoDtos.PlaceDetail> placeDetails(String providerPlaceId) {
    if (!isEnabled() || providerPlaceId == null || providerPlaceId.isBlank()) return Optional.empty();
    String url = props.getGoogle().getBaseUrl()
        + "/maps/api/place/details/json?place_id=" + GeoHttp.encode(providerPlaceId)
        + "&fields=geometry,formatted_address,name"
        + "&key=" + GeoHttp.encode(props.getGoogle().getApiKey());

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME).flatMap(root -> {
      JsonNode location = root.path("result").path("geometry").path("location");
      Double lat = GeoHttp.asDouble(location.path("lat"));
      Double lng = GeoHttp.asDouble(location.path("lng"));
      if (lat == null || lng == null) return Optional.empty();
      return Optional.of(new GeoDtos.PlaceDetail(
          NAME + ":" + providerPlaceId,
          GeoHttp.asText(root.path("result").path("formatted_address")),
          GeoHttp.asText(root.path("result").path("name")),
          lat,
          lng));
    });
  }

  @Override
  public Optional<GeoDtos.ReverseGeocode> reverseGeocode(double lat, double lng) {
    if (!isEnabled()) return Optional.empty();
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
