package com.helpinminutes.api.common;

import com.helpinminutes.api.errors.BadRequestException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class InputValidators {
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}$");
  private static final Pattern INDIA_PHONE_PATTERN = Pattern.compile("^[6-9]\\d{9}$");

  private static final java.util.Set<String> COMMON_EMAIL_TYPO_DOMAINS = java.util.Set.of(
      "gamil.com", "gmai.com", "gmial.com", "gmail.co", "gmaill.com", "gnail.com",
      "gzail.com", "hotnail.com", "hotmai.com", "yaho.com", "yhaoo.com", "outlok.com", "outllok.com"
  );

  private InputValidators() {}

  public static String requireEmail(String email) {
    return requireEmail(email, true);
  }

  public static String requireEmail(String email, boolean enforceProviderLimit) {
    String normalized = normalizeEmailOrNull(email, enforceProviderLimit);
    if (normalized == null) {
      throw new BadRequestException("Email is required");
    }
    return normalized;
  }

  public static String normalizeEmailOrNull(String email) {
    return normalizeEmailOrNull(email, true);
  }

  public static String normalizeEmailOrNull(String email, boolean enforceProviderLimit) {
    if (email == null) return null;
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) return null;
    if (normalized.length() > 254 || normalized.contains("..")) {
      throw new BadRequestException("Invalid email format");
    }
    if (!EMAIL_PATTERN.matcher(normalized.toUpperCase(Locale.ROOT)).matches()) {
      throw new BadRequestException("Invalid email format");
    }
    String[] parts = normalized.split("@");
    if (parts.length == 2) {
      String domain = parts[1];
      if (COMMON_EMAIL_TYPO_DOMAINS.contains(domain)) {
        throw new BadRequestException("Invalid email domain");
      }
      if (enforceProviderLimit) {
        java.util.Set<String> majorProviders = java.util.Set.of(
            "gmail.com", "yahoo.com", "yahoo.co.in", "outlook.com", "hotmail.com", "icloud.com",
            "aol.com", "zoho.com", "zoho.in", "protonmail.com", "proton.me", "live.com", "msn.com",
            "ymail.com", "rediffmail.com", "gmx.com", "mail.com", "yandex.com", "superheroo.test", "helpinminutes.app"
        );
        if (!majorProviders.contains(domain)) {
          throw new BadRequestException("Only major email providers are allowed (e.g. Gmail, Yahoo, Outlook)");
        }
      }
    }
    return normalized;
  }

  public static String normalizeIndianPhoneOrNull(String phone) {
    if (phone == null) return null;
    String raw = phone.trim();
    if (raw.isBlank()) return null;

    String digits = raw.replaceAll("\\D", "");
    if (digits.length() == 12 && digits.startsWith("91")) {
      digits = digits.substring(2);
    } else if (digits.length() == 11 && digits.startsWith("0")) {
      digits = digits.substring(1);
    }

    if (!INDIA_PHONE_PATTERN.matcher(digits).matches()) {
      throw new BadRequestException("Invalid Indian mobile number");
    }
    return digits;
  }
}
