package com.helpinminutes.api.geo.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocalHyderabadGeoProviderTest {

  private final LocalHyderabadGeoProvider provider = new LocalHyderabadGeoProvider();

  @Test
  void findsMadhapurWithoutExternalCredentials() {
    var result = provider.autocomplete("madhapur", 17.4401, 78.3489);

    assertTrue(result.isPresent());
    assertEquals("Madhapur", result.orElseThrow().getFirst().primaryText());
    assertEquals("local:madhapur", result.orElseThrow().getFirst().placeId());
  }

  @Test
  void matchesNaturalSpacingAndResolvesTheSelectedPlace() {
    var suggestions = provider.autocomplete("banjara hills", null, null).orElseThrow();
    assertEquals("Banjara Hills", suggestions.getFirst().primaryText());

    var place = provider.placeDetails("banjara-hills").orElseThrow();
    assertEquals("Banjara Hills", place.name());
    assertTrue(place.formattedAddress().contains("Hyderabad"));
  }

  @Test
  void doesNotPretendAnUnknownStreetIsAResult() {
    assertTrue(provider.autocomplete("a street that is not in the gazetteer", null, null).isEmpty());
  }
}
