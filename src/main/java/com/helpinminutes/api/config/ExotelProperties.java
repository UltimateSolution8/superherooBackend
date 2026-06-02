package com.helpinminutes.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "exotel")
public record ExotelProperties(
    boolean enabled,
    String apiKey,
    String apiToken,
    String accountSid,
    String subdomain,
    String from
) {
  public boolean canSendSms() {
    return enabled
        && StringUtils.hasText(apiKey)
        && StringUtils.hasText(apiToken)
        && StringUtils.hasText(accountSid)
        && StringUtils.hasText(subdomain)
        && StringUtils.hasText(from);
  }

  public String normalizedSubdomain() {
    if (!StringUtils.hasText(subdomain)) return "api.exotel.com";
    return subdomain.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
  }
}
