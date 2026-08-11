package com.helpinminutes.api.geo;

import java.util.List;
import java.util.Optional;

/**
 * One upstream maps provider.
 *
 * <p>Every method returns an {@link Optional} and must never throw: a provider
 * that is down, rate-limited or unconfigured returns {@code Optional.empty()} and
 * {@link GeoProviderChain} moves to the next one. Nothing a provider does may
 * surface as an error to the app — maps are an assist, not a gate.
 *
 * <p>A provider that does not implement a capability returns empty for it and
 * reports {@code false} from the matching {@code supports*} method, so the chain
 * does not spend a timeout budget discovering that.
 */
public interface GeoProvider {

  /** Stable short name used in logs, metrics and the response envelope. */
  String name();

  /** False when the provider has no credentials or is disabled by config. */
  boolean isEnabled();

  default boolean supportsAutocomplete() {
    return false;
  }

  default boolean supportsPlaceDetails() {
    return false;
  }

  default boolean supportsReverseGeocode() {
    return false;
  }

  default boolean supportsRouting() {
    return false;
  }

  /** True when the provider can answer a whole origin×destination matrix in one call. */
  default boolean supportsEtaMatrix() {
    return false;
  }

  default Optional<List<GeoDtos.PlaceSuggestion>> autocomplete(
      String query, Double biasLat, Double biasLng) {
    return Optional.empty();
  }

  /**
   * Autocomplete within a billing session.
   *
   * <p>{@code sessionToken} groups the keystrokes of one address entry and the
   * details lookup that concludes it, which is how Google stops billing each
   * keystroke separately. Only providers that charge per request care; the default
   * discards it, so Ola, OSRM and the local provider need no session concept.
   *
   * @param sessionToken opaque, may be null when the caller has no session
   */
  default Optional<List<GeoDtos.PlaceSuggestion>> autocomplete(
      String query, Double biasLat, Double biasLng, String sessionToken) {
    return autocomplete(query, biasLat, biasLng);
  }

  default Optional<GeoDtos.PlaceDetail> placeDetails(String providerPlaceId) {
    return Optional.empty();
  }

  /** Place details closing the session opened by {@code sessionToken}. See above. */
  default Optional<GeoDtos.PlaceDetail> placeDetails(String providerPlaceId, String sessionToken) {
    return placeDetails(providerPlaceId);
  }

  default Optional<GeoDtos.ReverseGeocode> reverseGeocode(double lat, double lng) {
    return Optional.empty();
  }

  default Optional<GeoDtos.Route> route(double fromLat, double fromLng, double toLat, double toLng) {
    return Optional.empty();
  }

  /**
   * Travel time in seconds from each origin to one destination.
   *
   * <p>Used by matching to rank candidates by ETA rather than straight-line
   * distance. Returns a list parallel to {@code origins}; an entry is null when
   * the provider could not route that pair. Returning empty (rather than a list
   * of nulls) tells the caller to fall back entirely.
   */
  default Optional<List<Integer>> etaMatrixToDestination(
      List<double[]> origins, double destLat, double destLng) {
    return Optional.empty();
  }
}
