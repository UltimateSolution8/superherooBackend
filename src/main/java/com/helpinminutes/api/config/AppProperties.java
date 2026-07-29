package com.helpinminutes.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    @NotBlank String env,
    @NotNull Jwt jwt,
    @NotNull Otp otp,
    @NotNull Matching matching,
    @NotNull Realtime realtime
) {
  public record Jwt(
      @NotBlank String accessSecret,
      @NotBlank String refreshSecret,
      @Min(60) long accessTtlSeconds,
      @Min(300) long refreshTtlSeconds
  ) {
    /** Secrets that shipped as defaults at some point and are therefore public knowledge. */
    private static final java.util.Set<String> KNOWN_WEAK_SECRETS = java.util.Set.of(
        "dev_access_secret_change_me", "dev_refresh_secret_change_me", "changeme", "secret");

    private static final int MIN_SECRET_LENGTH = 32;

    public Jwt {
      requireStrongSecret("app.jwt.accessSecret", "JWT_ACCESS_SECRET", accessSecret);
      requireStrongSecret("app.jwt.refreshSecret", "JWT_REFRESH_SECRET", refreshSecret);
      if (accessSecret != null && accessSecret.equals(refreshSecret)) {
        throw new IllegalStateException(
            "JWT_ACCESS_SECRET and JWT_REFRESH_SECRET must differ, otherwise a refresh token "
                + "can be replayed as an access token.");
      }
    }

    private static void requireStrongSecret(String property, String envVar, String value) {
      // @NotBlank already rejects null/empty, but bean validation runs after the
      // canonical constructor, so re-check here to keep the message actionable.
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(
            envVar + " is not set. Refusing to start: there is no safe default for a signing key.");
      }
      if (KNOWN_WEAK_SECRETS.contains(value)) {
        throw new IllegalStateException(
            envVar + " is set to a publicly-known placeholder value. Generate a new secret, e.g. "
                + "`openssl rand -hex 32`.");
      }
      if (value.length() < MIN_SECRET_LENGTH) {
        throw new IllegalStateException(
            envVar + " must be at least " + MIN_SECRET_LENGTH + " characters (got " + value.length()
                + "). Generate one with `openssl rand -hex 32`.");
      }
    }
  }

  public record Otp(
      @Min(60) @Max(3600) long ttlSeconds,
      boolean returnOtpInResponse
  ) {}

  public record Matching(
      @Min(0) @Max(15) int h3Resolution,
      @Min(0) @Max(10) int maxKRing,
      @Min(1) @Max(50) int offerFanout,
      @Min(5) @Max(300) int helperStaleAfterSeconds,
      @Min(10) @Max(600) int offerTtlSeconds
  ) {}

  public record Realtime(
      // the channel used for redis pub/sub.  if the property is absent we fall back
      // to the same default used by the Node realtime gateway so the app can still
      // start (otherwise Spring validation would reject the configuration and the
      // service would refuse to boot, which manifests as a 502 from the load
      // balancer).
      String redisPubSubChannel,
      String publishHttpUrl,
      String publishHttpSecret,
      @Min(200) @Max(10000) int publishHttpTimeoutMs
  ) {
      public Realtime {
          if (redisPubSubChannel == null || redisPubSubChannel.isBlank()) {
              redisPubSubChannel = "him:rt:events";
          }
          if (publishHttpTimeoutMs <= 0) {
              publishHttpTimeoutMs = 1500;
          }
      }
  }
}
