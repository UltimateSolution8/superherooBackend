package com.helpinminutes.api.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The online-payment kill switch.
 *
 * At launch the gateway is off: jobs settle in cash or UPI between citizen and
 * partner, confirmed in-app. Turning this on with live keys restores the prepaid
 * flow with no code change.
 */
class PaymentsFlagTest {

  private static AppProperties props(AppProperties.Payments payments) {
    return new AppProperties(
        "test",
        new AppProperties.Jwt(
            "test-access-secret-0123456789abcdef0123456789abcdef",
            "test-refresh-secret-0123456789abcdef0123456789abcdef",
            900, 3600),
        new AppProperties.Otp(300, false),
        new AppProperties.Matching(9, 3, 5, 120, 120),
        new AppProperties.Realtime("him:rt:events", "", "", 500),
        payments);
  }

  @Test
  void onlinePaymentsAreOffWhenTheSectionIsAbsent() {
    // A config file with no `app.payments` block must not silently enable the
    // gateway. Fail safe: no money can be taken unless someone opted in.
    assertFalse(props(null).payments().onlineEnabled());
  }

  @Test
  void flagRoundTripsWhenSet() {
    assertTrue(props(new AppProperties.Payments(true)).payments().onlineEnabled());
    assertFalse(props(new AppProperties.Payments(false)).payments().onlineEnabled());
  }
}
