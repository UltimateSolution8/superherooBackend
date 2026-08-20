package com.helpinminutes.api.appversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.helpinminutes.api.config.AppProperties;
import org.junit.jupiter.api.Test;

class AppVersionServiceTest {

  private static AppVersionService withVersions(AppProperties.AppVersions versions) {
    AppProperties props =
        new AppProperties(
            "test",
            new AppProperties.Jwt(
                "an-access-secret-that-is-long-enough-32",
                "a-refresh-secret-that-is-long-enough-32",
                900,
                2_592_000),
            null,
            null,
            null,
            null,
            versions);
    return new AppVersionService(props);
  }

  @Test
  void noGateWhenNoMinimumIsConfigured() {
    AppVersionService service = withVersions(AppProperties.AppVersions.unconfigured());
    // An unconfigured deployment must never lock every user out of every app.
    assertFalse(service.isBelowMinimum("buyer", "0.0.1"));
    assertNull(service.policyFor("buyer").minimumVersion());
  }

  @Test
  void blocksBuildsBelowTheMinimumAndAllowsTheRest() {
    AppVersionService service =
        withVersions(
            new AppProperties.AppVersions(
                "1.1.0", null, null, "1.2.0", null, null, null, null, null, null));

    assertTrue(service.isBelowMinimum("buyer", "1.0.9"));
    // Missing components read as zero, so "1.1" is exactly "1.1.0", not below it.
    assertFalse(service.isBelowMinimum("buyer", "1.1"));
    assertTrue(service.isBelowMinimum("buyer", "1.0"));
    assertFalse(service.isBelowMinimum("buyer", "1.1.0"));
    assertFalse(service.isBelowMinimum("buyer", "1.1.1"));
    assertFalse(service.isBelowMinimum("buyer", "2.0.0"));
  }

  @Test
  void appliesTheMinimumForTheClientsOwnVariant() {
    AppVersionService service =
        withVersions(
            new AppProperties.AppVersions(
                "1.0.0", "2.0.0", null, null, null, null, null, null, null, null));

    // Version codes carry a per-variant offset and are not comparable, so the gate
    // has to look up the threshold for the app that is actually asking.
    assertFalse(service.isBelowMinimum("buyer", "1.5.0"));
    assertTrue(service.isBelowMinimum("helper", "1.5.0"));
    // "partner" is what the helper variant is called in places.
    assertTrue(service.isBelowMinimum("partner", "1.5.0"));
  }

  @Test
  void letsThroughAnythingItCannotParse() {
    AppVersionService service =
        withVersions(
            new AppProperties.AppVersions(
                "1.1.0", null, null, null, null, null, null, null, null, null));

    // Failing closed here would block clients whose header we simply failed to read.
    assertFalse(service.isBelowMinimum("buyer", "not-a-version"));
    assertFalse(service.isBelowMinimum("buyer", ""));
    assertFalse(service.isBelowMinimum("buyer", null));
    // A build suffix is tolerated; only the numeric core is compared.
    assertTrue(service.isBelowMinimum("buyer", "1.0.0-rc3"));
  }

  @Test
  void unknownVariantFallsBackToTheCitizenApp() {
    assertEquals("buyer", AppVersionService.normalizeVariant(null));
    assertEquals("buyer", AppVersionService.normalizeVariant("something-else"));
    assertEquals("helper", AppVersionService.normalizeVariant("HELPER"));
    assertEquals("mediator", AppVersionService.normalizeVariant("Mediator"));
  }
}
