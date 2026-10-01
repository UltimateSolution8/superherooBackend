package com.helpinminutes.api.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServiceAreaTest {

  @Test
  void hyderabadLocationsAreWithinServiceArea() {
    // Hyderabad center
    assertTrue(ServiceArea.isWithinHyderabad(17.3850, 78.4867));
    assertTrue(ServiceArea.isWithinServiceArea(17.3850, 78.4867));
    assertEquals("HYDERABAD", ServiceArea.resolveCity(17.3850, 78.4867));

    // Madhapur
    assertTrue(ServiceArea.isWithinHyderabad(17.4483, 78.3915));
    assertTrue(ServiceArea.isWithinServiceArea(17.4483, 78.3915));
    assertEquals("HYDERABAD", ServiceArea.resolveCity(17.4483, 78.3915));

    // Shamshabad airport
    assertTrue(ServiceArea.isWithinHyderabad(17.2403, 78.4294));
    assertTrue(ServiceArea.isWithinServiceArea(17.2403, 78.4294));
    assertEquals("HYDERABAD", ServiceArea.resolveCity(17.2403, 78.4294));
  }

  @Test
  void bangaloreLocationsAreWithinServiceArea() {
    // Bangalore center (Cubbon Park / MG Road)
    assertTrue(ServiceArea.isWithinBangalore(12.9716, 77.5946));
    assertTrue(ServiceArea.isWithinServiceArea(12.9716, 77.5946));
    assertEquals("BANGALORE", ServiceArea.resolveCity(12.9716, 77.5946));

    // Koramangala
    assertTrue(ServiceArea.isWithinBangalore(12.9352, 77.6245));
    assertTrue(ServiceArea.isWithinServiceArea(12.9352, 77.6245));
    assertEquals("BANGALORE", ServiceArea.resolveCity(12.9352, 77.6245));

    // Whitefield
    assertTrue(ServiceArea.isWithinBangalore(12.9698, 77.7500));
    assertTrue(ServiceArea.isWithinServiceArea(12.9698, 77.7500));
    assertEquals("BANGALORE", ServiceArea.resolveCity(12.9698, 77.7500));

    // Electronic City
    assertTrue(ServiceArea.isWithinBangalore(12.8452, 77.6602));
    assertTrue(ServiceArea.isWithinServiceArea(12.8452, 77.6602));
    assertEquals("BANGALORE", ServiceArea.resolveCity(12.8452, 77.6602));

    // Bangalore is NOT within Hyderabad
    assertFalse(ServiceArea.isWithinHyderabad(12.9716, 77.5946));
  }

  @Test
  void locationsOutsideServiceAreaAreRejected() {
    // Mumbai
    assertFalse(ServiceArea.isWithinServiceArea(19.0760, 72.8777));
    assertNull(ServiceArea.resolveCity(19.0760, 72.8777));

    // Delhi
    assertFalse(ServiceArea.isWithinServiceArea(28.7041, 77.1025));
    assertNull(ServiceArea.resolveCity(28.7041, 77.1025));

    // Chennai
    assertFalse(ServiceArea.isWithinServiceArea(13.0827, 80.2707));
    assertNull(ServiceArea.resolveCity(13.0827, 80.2707));

    // Indian Ocean / international
    assertFalse(ServiceArea.isWithinServiceArea(0.0, 0.0));
    assertFalse(ServiceArea.isWithinIndia(0.0, 0.0));
    assertNull(ServiceArea.resolveCity(0.0, 0.0));
  }
}
