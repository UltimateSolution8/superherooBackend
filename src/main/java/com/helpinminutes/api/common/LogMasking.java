package com.helpinminutes.api.common;

/**
 * Redacts personal data before it reaches the logs.
 *
 * Logs ship to Sentry ({@code sentry.logs.enabled}), so a phone number or email
 * written at INFO leaves our infrastructure. These helpers keep enough of the
 * value to correlate a support report without storing the identifier itself.
 */
public final class LogMasking {

  private LogMasking() {}

  /** {@code 9876543210} → {@code ******3210} */
  public static String phone(String phone) {
    if (phone == null || phone.isBlank()) return "<none>";
    String digits = phone.trim();
    if (digits.length() <= 4) return "*".repeat(digits.length());
    return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
  }

  /** {@code someone@gmail.com} → {@code s*****e@gmail.com} */
  public static String email(String email) {
    if (email == null || email.isBlank()) return "<none>";
    String value = email.trim();
    int at = value.indexOf('@');
    if (at <= 0) return "<redacted>";
    String local = value.substring(0, at);
    String domain = value.substring(at);
    if (local.length() <= 2) return "*".repeat(local.length()) + domain;
    return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1) + domain;
  }
}
