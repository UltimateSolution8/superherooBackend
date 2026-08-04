package com.helpinminutes.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "livekit")
public record LiveKitProperties(
    @NotBlank String url,
    @NotBlank String apiKey,
    @NotBlank String apiSecret,
    @Min(60) @Max(900) long tokenTtlSeconds
) {}
