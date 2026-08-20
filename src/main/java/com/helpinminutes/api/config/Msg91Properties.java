package com.helpinminutes.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "msg91")
public record Msg91Properties(
    boolean enabled,
    String authKey,
    String templateId,
    String senderId,
    String dltTemplateId,
    int otpExpiryMinutes
) {
  public boolean canSendSms() {
    return enabled
        && StringUtils.hasText(authKey)
        && StringUtils.hasText(templateId);
  }

  public int normalizedOtpExpiryMinutes() {
    return otpExpiryMinutes > 0 ? otpExpiryMinutes : 5;
  }
}
