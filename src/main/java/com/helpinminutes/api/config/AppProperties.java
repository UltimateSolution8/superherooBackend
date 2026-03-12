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
  ) {}

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
      String redisPubSubChannel
  ) {
      public Realtime {
          if (redisPubSubChannel == null || redisPubSubChannel.isBlank()) {
              redisPubSubChannel = "him:rt:events";
          }
      }
  }
}
