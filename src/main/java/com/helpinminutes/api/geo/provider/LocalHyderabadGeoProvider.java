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
  private static final String HYDERABAD_SECONDARY = "Hyderabad, Telangana";
  private static final String BANGALORE_SECONDARY = "Bengaluru, Karnataka";

  private static final List<Locality> LOCALITIES = List.of(
      // Hyderabad
      hydLocality("madhapur", "Madhapur", 17.4483, 78.3915),
      hydLocality("hitech-city", "HITEC City", 17.4435, 78.3772),
      hydLocality("gachibowli", "Gachibowli", 17.4401, 78.3489),
      hydLocality("kondapur", "Kondapur", 17.4698, 78.3634),
      hydLocality("jubilee-hills", "Jubilee Hills", 17.4326, 78.4071),
      hydLocality("banjara-hills", "Banjara Hills", 17.4138, 78.4398),
      hydLocality("begumpet", "Begumpet", 17.4440, 78.4621),
      hydLocality("ameerpet", "Ameerpet", 17.4374, 78.4482),
      hydLocality("secunderabad", "Secunderabad", 17.4399, 78.4983),
      hydLocality("kukatpally", "Kukatpally", 17.4948, 78.3996),
      hydLocality("miyapur", "Miyapur", 17.4968, 78.3614),
      hydLocality("manikonda", "Manikonda", 17.4062, 78.3763),
      hydLocality("nanakramguda", "Nanakramguda", 17.4164, 78.3428),
      hydLocality("financial-district", "Financial District", 17.4141, 78.3420),
      hydLocality("raidurg", "Raidurg", 17.4411, 78.3810),
      hydLocality("tolichowki", "Tolichowki", 17.3984, 78.4151),
      hydLocality("mehdipatnam", "Mehdipatnam", 17.3952, 78.4400),
      hydLocality("attapur", "Attapur", 17.3691, 78.4292),
      hydLocality("uppal", "Uppal", 17.4058, 78.5591),
      hydLocality("lb-nagar", "LB Nagar", 17.3457, 78.5522),
      hydLocality("dilsukhnagar", "Dilsukhnagar", 17.3688, 78.5247),
      hydLocality("kothapet", "Kothapet", 17.3734, 78.5476),
      hydLocality("somajiguda", "Somajiguda", 17.4237, 78.4584),
      hydLocality("lakdikapul", "Lakdikapul", 17.4038, 78.4615),
      hydLocality("abids", "Abids", 17.3930, 78.4760),
      hydLocality("charminar", "Charminar", 17.3616, 78.4747),
      hydLocality("shamshabad", "Shamshabad", 17.2512, 78.4377),
      hydLocality("kompally", "Kompally", 17.5414, 78.4841),
      hydLocality("alwal", "Alwal", 17.5047, 78.5038),
      hydLocality("sainikpuri", "Sainikpuri", 17.4907, 78.5426),

      // Bangalore / Bengaluru
      blrLocality("koramangala", "Koramangala", 12.9352, 77.6245),
      blrLocality("indiranagar", "Indiranagar", 12.9784, 77.6408),
      blrLocality("hsr-layout", "HSR Layout", 12.9121, 77.6446),
      blrLocality("whitefield", "Whitefield", 12.9698, 77.7500),
      blrLocality("electronic-city", "Electronic City", 12.8452, 77.6602),
      blrLocality("jayanagar", "Jayanagar", 12.9308, 77.5838),
      blrLocality("jp-nagar", "JP Nagar", 12.9063, 77.5857),
      blrLocality("btm-layout", "BTM Layout", 12.9166, 77.6101),
      blrLocality("marathahalli", "Marathahalli", 12.9591, 77.6974),
      blrLocality("bellandur", "Bellandur", 12.9304, 77.6784),
      blrLocality("malleshwaram", "Malleshwaram", 13.0031, 77.5643),
      blrLocality("rajajinagar", "Rajajinagar", 12.9982, 77.5530),
      blrLocality("hebbal", "Hebbal", 13.0358, 77.5970),
      blrLocality("yelahanka", "Yelahanka", 13.1007, 77.5963),
      blrLocality("banashankari", "Banashankari", 12.9255, 77.5468),
      blrLocality("kalyan-nagar", "Kalyan Nagar", 13.0280, 77.6480),
      blrLocality("bannerghatta-road", "Bannerghatta Road", 12.8950, 77.5980),
      blrLocality("mg-road", "MG Road", 12.9756, 77.6066),
      blrLocality("sarjapur-road", "Sarjapur Road", 12.9100, 77.6850),
      blrLocality("domlur", "Domlur", 12.9610, 77.6387),
      blrLocality("sadashivanagar", "Sadashivanagar", 13.0068, 77.5813),
      blrLocality("basavanagudi", "Basavanagudi", 12.9422, 77.5756),
      blrLocality("frazer-town", "Frazer Town", 12.9972, 77.6144),
      blrLocality("richmond-town", "Richmond Town", 12.9632, 77.6033),
      blrLocality("ulsoor", "Ulsoor", 12.9817, 77.6286),
      blrLocality("vasanth-nagar", "Vasanth Nagar", 12.9893, 77.5912),
      blrLocality("rt-nagar", "RT Nagar", 13.0247, 77.5948),
      blrLocality("mahadevapura", "Mahadevapura", 12.9880, 77.6895),
      blrLocality("kadugodi", "Kadugodi", 12.9985, 77.7609),
      blrLocality("nagarbhavi", "Nagarbhavi", 12.9554, 77.5105)
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
            locality.secondary(),
            locality.name() + ", " + locality.secondary(),
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
            locality.name() + ", " + locality.secondary(),
            locality.name(),
            locality.lat(),
            locality.lng()));
  }

  private static double distance(Locality locality, Double biasLat, Double biasLng) {
    if (biasLat == null || biasLng == null) return 0d;
    return GeoUtils.distanceMeters(biasLat, biasLng, locality.lat(), locality.lng());
  }

  private static Locality hydLocality(String id, String name, double lat, double lng) {
    return new Locality(id, name, HYDERABAD_SECONDARY, normalize(name + " Hyderabad Telangana"), lat, lng);
  }

  private static Locality blrLocality(String id, String name, double lat, double lng) {
    return new Locality(id, name, BANGALORE_SECONDARY, normalize(name + " Bangalore Bengaluru Karnataka"), lat, lng);
  }

  private static String normalize(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", " ")
        .trim();
  }

  private record Locality(String id, String name, String secondary, String searchText, double lat, double lng) {}
}
