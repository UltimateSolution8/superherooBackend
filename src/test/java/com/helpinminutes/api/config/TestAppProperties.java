package com.helpinminutes.api.config;

/**
 * A valid {@link AppProperties} for unit tests that only need one injected.
 *
 * <p>Several services take {@code AppProperties} for one or two knobs. Building the
 * whole nested record inline in each fixture meant every new config field broke a
 * handful of unrelated tests; this is the single place to update instead.
 */
public final class TestAppProperties {

  private TestAppProperties() {}

  public static AppProperties defaults() {
    return new AppProperties(
        "test",
        new AppProperties.Jwt(
            "test-access-secret-0123456789abcdef0123456789abcdef",
            "test-refresh-secret-0123456789abcdef0123456789abcdef",
            900,
            3600),
        new AppProperties.Otp(300),
        new AppProperties.Matching(9, 6, 8, 120, 45),
        new AppProperties.Realtime("him:rt:events", "", "", 500),
        new AppProperties.Payments(false));
  }
}
