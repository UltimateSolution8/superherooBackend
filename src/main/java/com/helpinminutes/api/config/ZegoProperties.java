package com.helpinminutes.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zego")
public record ZegoProperties(
    @NotNull Long appId,
    @NotBlank String serverSecret,
    String callbackSecret,
    @Min(60) long tokenTtlSeconds,
    @NotBlank String recordApiBaseUrl
) {}
