package com.helpinminutes.api.common;

import com.helpinminutes.api.errors.BadRequestException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class InputValidators {
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}$");
  private static final Pattern INDIA_PHONE_PATTERN = Pattern.compile("^[6-9]\\d{9}$");

  private InputValidators() {}

  public static String requireEmail(String email) {
    String normalized = normalizeEmailOrNull(email);
    if (normalized == null) {
      throw new BadRequestException("Email is required");
    }
    return normalized;
  }

  public static String normalizeEmailOrNull(String email) {
    if (email == null) return null;
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) return null;
    if (normalized.length() > 254 || normalized.contains("..")) {
      throw new BadRequestException("Invalid email format");
    }
    if (!EMAIL_PATTERN.matcher(normalized.toUpperCase(Locale.ROOT)).matches()) {
      throw new BadRequestException("Invalid email format");
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
