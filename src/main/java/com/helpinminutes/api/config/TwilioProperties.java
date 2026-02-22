package com.helpinminutes.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "twilio")
public record TwilioProperties(
    String accountSid,
    String authToken,
    String verifyServiceSid
) {
  public boolean enabled() {
    return StringUtils.hasText(accountSid) && StringUtils.hasText(authToken) && StringUtils.hasText(verifyServiceSid);
  }
}
