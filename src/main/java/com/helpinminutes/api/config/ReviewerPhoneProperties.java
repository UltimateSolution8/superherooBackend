package com.helpinminutes.api.config;

import com.helpinminutes.api.common.LogMasking;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phone numbers whose OTP is provisioned rather than texted.
 *
 * <h2>Why this exists</h2>
 *
 * Google Play reviewers cannot receive an Indian SMS. The app's primary sign-in is
 * phone plus OTP, so without something here a reviewer can only use the email and
 * password path — which is enough to review the app, but not to review the flow
 * every real user takes.
 *
 * <h2>What it does, and what it does not</h2>
 *
 * For an allowlisted number, {@code startOtp} writes the configured code into Redis
 * instead of a random one, and sends no SMS. Verification is <em>completely
 * unchanged</em>: the same comparison, the same five-attempt cap, the same TTL, the
 * same per-phone rate limit. The only difference is which value was stored. This is
 * the mechanism Firebase Auth ships as "fictional phone numbers" for exactly this
 * situation.
 *
 * <p>It grants nothing else. No KYC change, no geofence waiver, no moderation
 * bypass, no role escalation — the reviewer walks the same code path as everybody
 * else from the moment they are signed in. Compare the bypass this replaced, which
 * skipped verification entirely for hardcoded numbers and forced KYC to APPROVED.
 *
 * <h2>Guards</h2>
 *
 * <ul>
 *   <li>Empty unless {@code REVIEWER_OTP_PHONES} is set, so nothing is enabled by
 *       default and a fresh deployment has no allowlist at all.
 *   <li>Additionally requires {@code REVIEWER_SEED_ENABLED=true}, the same switch
 *       that provisions the reviewer accounts — one flag turns the whole review
 *       arrangement off after the review completes.
 *   <li>Numbers and codes come from the environment, never from source.
 *   <li>Every use is logged at WARN, so it is visible in production rather than
 *       quietly effective.
 * </ul>
 *
 * Format: {@code REVIEWER_OTP_PHONES=9000000101:472913,9000000102:658204}
 */
@Component
public class ReviewerPhoneProperties {
  private static final Logger log = LoggerFactory.getLogger(ReviewerPhoneProperties.class);

  private final Map<String, String> codesByPhone;

  /** Annotated so Spring picks this one without relying on constructor-resolution rules. */
  @org.springframework.beans.factory.annotation.Autowired
  public ReviewerPhoneProperties() {
    this(System.getenv("REVIEWER_SEED_ENABLED"), System.getenv("REVIEWER_OTP_PHONES"));
  }

  /**
   * Explicit values, for tests. Spring always uses the no-arg constructor above;
   * the environment is not settable from inside a unit test.
   */
  public ReviewerPhoneProperties(String seedEnabled, String rawAllowlist) {
    this.codesByPhone = parse(seedEnabled, rawAllowlist);
    if (!codesByPhone.isEmpty()) {
      log.warn("Reviewer OTP allowlist active for {} number(s). Unset REVIEWER_SEED_ENABLED "
          + "once Play review completes.", codesByPhone.size());
    }
  }

  private static Map<String, String> parse(String seedEnabled, String rawAllowlist) {
    Map<String, String> parsed = new LinkedHashMap<>();
    if (!"true".equalsIgnoreCase(seedEnabled)) return Map.copyOf(parsed);
    if (rawAllowlist == null || rawAllowlist.isBlank()) return Map.copyOf(parsed);

    for (String entry : rawAllowlist.split(",")) {
      String[] parts = entry.trim().split(":", 2);
      if (parts.length != 2) continue;
      String phone = normalise(parts[0]);
      String code = parts[1].trim();
      // A short or non-numeric code would weaken the same comparison every other
      // user goes through. Refuse it rather than quietly accepting a weak one.
      if (phone.matches("^[6-9]\\d{9}$") && code.matches("^\\d{6}$")) {
        parsed.put(phone, code);
      } else {
        log.warn("Ignoring malformed REVIEWER_OTP_PHONES entry for {}", LogMasking.phone(parts[0]));
      }
    }
    return Map.copyOf(parsed);
  }

  /** Strips +91/91 and spacing so the allowlist matches however the app sends it. */
  private static String normalise(String phone) {
    String digits = phone == null ? "" : phone.replaceAll("\\D", "");
    if (digits.length() > 10) digits = digits.substring(digits.length() - 10);
    return digits.toLowerCase(Locale.ROOT);
  }

  public boolean isEmpty() {
    return codesByPhone.isEmpty();
  }

  /** The provisioned code for this number, or null when it is an ordinary user. */
  public String codeFor(String phone) {
    if (codesByPhone.isEmpty()) return null;
    return codesByPhone.get(normalise(phone));
  }
}
