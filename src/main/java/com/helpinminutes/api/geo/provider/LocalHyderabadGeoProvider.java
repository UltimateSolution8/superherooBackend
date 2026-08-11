package com.helpinminutes.api.geo.provider;

import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.geo.GeoDtos;
import com.helpinminutes.api.geo.GeoProvider;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A credential-free last resort for Hyderabad locality search.
 *
 * <p>Ola and Google remain the primary providers and supply street/building-level
 * results. This deliberately small gazetteer prevents a missing key or provider
 * outage from turning common searches such as "Madhapur" into an empty screen.
 * Results are locality centres, so the citizen can refine the exact destination
 * with the synchronized map pin and landmark fields.
 */
@Component
public final class LocalHyderabadGeoProvider implements GeoProvider {

  private static final String NAME = "local";
  private static final String SECONDARY = "Hyderabad, Telangana";

  private static final List<Locality> LOCALITIES = List.of(
      locality("madhapur", "Madhapur", 17.4483, 78.3915),
      locality("hitech-city", "HITEC City", 17.4435, 78.3772),
      locality("gachibowli", "Gachibowli", 17.4401, 78.3489),
      locality("kondapur", "Kondapur", 17.4698, 78.3634),
      locality("jubilee-hills", "Jubilee Hills", 17.4326, 78.4071),
      locality("banjara-hills", "Banjara Hills", 17.4138, 78.4398),
      locality("begumpet", "Begumpet", 17.4440, 78.4621),
      locality("ameerpet", "Ameerpet", 17.4374, 78.4482),
      locality("secunderabad", "Secunderabad", 17.4399, 78.4983),
      locality("kukatpally", "Kukatpally", 17.4948, 78.3996),
      locality("miyapur", "Miyapur", 17.4968, 78.3614),
      locality("manikonda", "Manikonda", 17.4062, 78.3763),
      locality("nanakramguda", "Nanakramguda", 17.4164, 78.3428),
      locality("financial-district", "Financial District", 17.4141, 78.3420),
      locality("raidurg", "Raidurg", 17.4411, 78.3810),
      locality("tolichowki", "Tolichowki", 17.3984, 78.4151),
      locality("mehdipatnam", "Mehdipatnam", 17.3952, 78.4400),
      locality("attapur", "Attapur", 17.3691, 78.4292),
      locality("uppal", "Uppal", 17.4058, 78.5591),
      locality("lb-nagar", "LB Nagar", 17.3457, 78.5522),
      locality("dilsukhnagar", "Dilsukhnagar", 17.3688, 78.5247),
      locality("kothapet", "Kothapet", 17.3734, 78.5476),
      locality("somajiguda", "Somajiguda", 17.4237, 78.4584),
      locality("lakdikapul", "Lakdikapul", 17.4038, 78.4615),
      locality("abids", "Abids", 17.3930, 78.4760),
      locality("charminar", "Charminar", 17.3616, 78.4747),
      locality("shamshabad", "Shamshabad", 17.2512, 78.4377),
      locality("kompally", "Kompally", 17.5414, 78.4841),
      locality("alwal", "Alwal", 17.5047, 78.5038),
      locality("sainikpuri", "Sainikpuri", 17.4907, 78.5426)
  );

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled() {
    return true;
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
  public Optional<List<GeoDtos.PlaceSuggestion>> autocomplete(
      String query, Double biasLat, Double biasLng) {
    String normalized = normalize(query);
    if (normalized.length() < 2) return Optional.empty();

    List<GeoDtos.PlaceSuggestion> matches = LOCALITIES.stream()
        .filter(locality -> locality.searchText().contains(normalized))
        .sorted(Comparator
            .comparing((Locality locality) -> !normalize(locality.name()).startsWith(normalized))
            .thenComparingDouble(locality -> distance(locality, biasLat, biasLng)))
        .limit(8)
        .map(locality -> new GeoDtos.PlaceSuggestion(
            NAME + ":" + locality.id(),
            locality.name(),
            SECONDARY,
            locality.name() + ", " + SECONDARY,
            locality.lat(),
            locality.lng(),
            biasLat == null || biasLng == null ? null : distance(locality, biasLat, biasLng)))
        .toList();
    return matches.isEmpty() ? Optional.empty() : Optional.of(matches);
  }

  @Override
  public Optional<GeoDtos.PlaceDetail> placeDetails(String providerPlaceId) {
    if (providerPlaceId == null) return Optional.empty();
    String normalizedId = normalize(providerPlaceId);
    return LOCALITIES.stream()
        .filter(locality -> normalize(locality.id()).equals(normalizedId))
        .findFirst()
        .map(locality -> new GeoDtos.PlaceDetail(
            NAME + ":" + locality.id(),
            locality.name() + ", " + SECONDARY,
            locality.name(),
            locality.lat(),
            locality.lng()));
  }

  private static double distance(Locality locality, Double biasLat, Double biasLng) {
    if (biasLat == null || biasLng == null) return 0d;
    return GeoUtils.distanceMeters(biasLat, biasLng, locality.lat(), locality.lng());
  }

  private static Locality locality(String id, String name, double lat, double lng) {
    return new Locality(id, name, normalize(name + " Hyderabad Telangana"), lat, lng);
  }

  private static String normalize(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", " ")
        .trim();
  }

  private record Locality(String id, String name, String searchText, double lat, double lng) {}
}
