package com.helpinminutes.api.geo;

import java.util.List;

/**
 * Provider-neutral shapes for the geo proxy.
 *
 * <p>Deliberately narrower than any single provider's response. The apps used to
 * parse Google, Nominatim and Photon shapes inline in six screens, so a provider
 * change meant an app release. These types are the contract instead: adding a
 * provider is a backend change only.
 */
public final class GeoDtos {

  private GeoDtos() {}

  /**
   * One autocomplete prediction.
   *
   * @param placeId opaque, provider-prefixed (e.g. {@code ola:ChIJ...}) so a later
   *     details lookup is routed back to the provider that issued it
   * @param lat latitude when the provider returned one inline; null means the
   *     client must call details to resolve coordinates
   */
  public record PlaceSuggestion(
      String placeId,
      String primaryText,
      String secondaryText,
      String description,
      Double lat,
      Double lng,
      Double distanceMeters) {}

  /** Resolved coordinates and formatted address for a place. */
  public record PlaceDetail(
      String placeId,
      String formattedAddress,
      String name,
      double lat,
      double lng) {}

  /** A reverse-geocoded address. Never null-valued: callers render it directly. */
  public record ReverseGeocode(
      String formattedAddress,
      String locality,
      String postalCode,
      double lat,
      double lng) {}

  /**
   * A driving route.
   *
   * @param encodedPolyline Google-encoded polyline, precision 5, so the existing
   *     {@code decodePolyline} in the app works unchanged for every provider
   * @param etaSeconds null when the provider gave geometry but no duration
   */
  public record Route(
      String encodedPolyline,
      Integer etaSeconds,
      Integer distanceMeters) {}

  /**
   * Response envelope for every geo endpoint.
   *
   * <p>{@code provider} and {@code degraded} exist so the app can show a subtle
   * hint and so we can see in logs which provider actually served a request
   * without correlating two systems. {@code degraded} true means every configured
   * provider failed and this is a last-resort local answer — the app must still
   * render, never block.
   */
  public record GeoEnvelope<T>(T result, String provider, boolean degraded) {
    public static <T> GeoEnvelope<T> served(T result, String provider) {
      return new GeoEnvelope<>(result, provider, false);
    }

    public static <T> GeoEnvelope<T> degraded(T result) {
      return new GeoEnvelope<>(result, "local", true);
    }
  }

  /** Autocomplete response. */
  public record SuggestionsResponse(
      List<PlaceSuggestion> suggestions,
      String provider,
      boolean degraded) {}
}
