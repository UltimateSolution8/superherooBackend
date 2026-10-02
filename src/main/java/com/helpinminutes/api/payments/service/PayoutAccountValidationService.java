package com.helpinminutes.api.payments.service;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.errors.ServiceUnavailableException;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.repo.HelperPayoutAccountRepository;
import com.helpinminutes.api.helpers.security.BankAccountCipher;
import com.helpinminutes.api.payments.gateway.RazorpayGatewayException;
import com.helpinminutes.api.payments.gateway.RazorpayXGateway;
import com.helpinminutes.api.payments.model.PayoutAccountValidationEntity;
import com.helpinminutes.api.payments.repo.PayoutAccountValidationRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verification of a partner's bank account or UPI VPA.
 *
 * <p>Uses Razorpay's Hybrid FAV stack (penniless + penny-drop fallback) via the
 * composite API. Penniless verification is free, nearly instant (~3 seconds), and
 * covers 50-60% of banks. For the rest, RazorpayX falls back to a ₹1 penny drop
 * automatically, maintaining a 99%+ success rate across the board.
 *
 * <p>Two rules make this safe to automate:
 * <ul>
 *   <li>a name mismatch never auto-verifies — it goes to MANUAL_REVIEW, because a
 *       drop that succeeds against <em>someone else's</em> account is exactly the
 *       fraud this exists to catch;
 *   <li>attempts are capped per account per day, because every one costs money.
 * </ul>
 *
 * <p>Name matching uses Razorpay's provider score (0-100) when available, falling
 * back to our own local word-set comparison. Either way, below 80 goes to review.
 */
@Service
public class PayoutAccountValidationService {

  private static final Logger log = LoggerFactory.getLogger(PayoutAccountValidationService.class);

  /** Each validation costs real money (hybrid ~₹2+taxes). Three a day is generous. */
  static final int MAX_ATTEMPTS_PER_DAY = 3;
  /** Below this the names are treated as different people. */
  static final int NAME_MATCH_THRESHOLD = 80;
  /** Default validation mode: hybrid (penniless + penny-drop fallback). */
  private static final String DEFAULT_VALIDATION_TYPE = PayoutAccountValidationEntity.TYPE_OPTIMIZED;

  private final PayoutAccountValidationRepository validations;
  private final HelperPayoutAccountRepository accounts;
  private final UserRepository users;
  private final RazorpayXGateway razorpayx;
  private final BankAccountCipher cipher;

  public PayoutAccountValidationService(
      PayoutAccountValidationRepository validations,
      HelperPayoutAccountRepository accounts,
      UserRepository users,
      RazorpayXGateway razorpayx,
      BankAccountCipher cipher) {
    this.validations = validations;
    this.accounts = accounts;
    this.users = users;
    this.razorpayx = razorpayx;
    this.cipher = cipher;
  }

  /** The most recent validation for a partner's current account, if any. */
  @Transactional(readOnly = true)
  public Optional<PayoutAccountValidationEntity> latestFor(UUID helperId) {
    return currentAccount(helperId)
        .flatMap(a -> validations.findFirstByPayoutAccountIdOrderByCreatedAtDesc(a.getId()));
  }

  /**
   * Starts hybrid FAV against the partner's current bank account.
   *
   * <p>Idempotent while one is in flight: the caller gets the existing row back
   * rather than paying for a second verification.
   *
   * <p>Uses the composite API: Razorpay creates or reuses a Contact + Fund Account
   * and runs the Hybrid FAV stack atomically in one call.
   */
  @Transactional
  public PayoutAccountValidationEntity startValidation(UUID helperId) {
    HelperPayoutAccountEntity account =
        currentAccount(helperId)
            .orElseThrow(() -> new NotFoundException("No bank account on file"));

    if ("VERIFIED".equals(account.getVerificationStatus())) {
      throw new BadRequestException("This account is already verified");
    }

    Optional<PayoutAccountValidationEntity> inFlight = validations.findInFlight(account.getId());
    if (inFlight.isPresent()) return inFlight.get();

    long recent = validations.countSince(account.getId(), Instant.now().minus(Duration.ofDays(1)));
    if (recent >= MAX_ATTEMPTS_PER_DAY) {
      throw new BadRequestException(
          "Too many verification attempts for this account today. Try again tomorrow.");
    }

    if (!razorpayx.isConfigured()) {
      throw new ServiceUnavailableException("Bank verification is temporarily unavailable.");
    }

    UserEntity user = users.findById(helperId).orElseThrow();
    boolean isVpa = "vpa".equalsIgnoreCase(account.getAccountType());
    String accountNumber = null;
    String vpaAddress = null;
    if (isVpa) {
      accountNumber = null;
      vpaAddress = cipher.decrypt(
          account.getId(),
          account.getUpiIdKeyId(),
          account.getUpiIdCiphertext());
    } else {
      accountNumber = cipher.decrypt(
          account.getId(),
          account.getAccountNumberKeyId(),
          account.getAccountNumberCiphertext());
      vpaAddress = null;
    }

    PayoutAccountValidationEntity validation = new PayoutAccountValidationEntity();
    validation.setPayoutAccountId(account.getId());
    validation.setHelperId(helperId);
    validation.setStatus(PayoutAccountValidationEntity.PENDING);
    validation.setAccountType(isVpa ? "vpa" : "bank_account");
    validation.setValidationType(DEFAULT_VALIDATION_TYPE);
    validation.setAttempts(1);
    try {
      validation = validations.saveAndFlush(validation);
    } catch (DataIntegrityViolationException e) {
      // The one-in-flight index fired: another request got there first. Returning
      // theirs is the right answer, and it saves the partner a second verification cost.
      return validations.findInFlight(account.getId()).orElseThrow(() -> e);
    }

    account.setVerificationStatus("PENDING");
    accounts.save(account);

    try {
      // Composite API: creates Contact + Fund Account + Validation in one call,
      // running the Hybrid FAV (penniless-first, penny-drop fallback) stack atomically.
      RazorpayXGateway.CompositeValidationRequest request =
          new RazorpayXGateway.CompositeValidationRequest(
              isVpa ? "vpa" : "bank_account",
              account.getAccountHolderName(),
              accountNumber,
              isVpa ? null : account.getIfscCode(),
              vpaAddress,
              user.getDisplayName(),
              user.getPhone(),
              user.getEmail(),
              user.getId().toString(),
              DEFAULT_VALIDATION_TYPE);

      RazorpayXGateway.FundAccountValidationResult result =
          razorpayx.createCompositeValidation(request);
      validation.setProviderValidationId(result.id());
      apply(validation, account, result);
    } catch (RazorpayGatewayException e) {
      // Left PENDING on purpose. The verification may well have been created;
      // marking it failed here would let the partner immediately buy another one.
      log.error(
          "Hybrid FAV for account {} could not be submitted and is left for polling: {}",
          account.getId(),
          e.getMessage());
    }

    return validations.save(validation);
  }

  /**
   * Starts hybrid FAV against the partner's UPI VPA.
   *
   * <p>UPI validation is penniless-only (no penny drop to a VPA). The provider
   * confirms the VPA exists and returns the registered name for name matching.
   * Uses the composite API to create the contact and VPA fund account atomically.
   */
  @Transactional
  public PayoutAccountValidationEntity startUpiValidation(UUID helperId, String upiVpa, String accountHolderName) {
    HelperPayoutAccountEntity account =
        currentAccount(helperId)
            .orElseThrow(() -> new NotFoundException("No payout account on file"));

    if (!razorpayx.isConfigured()) {
      throw new ServiceUnavailableException("UPI verification is temporarily unavailable.");
    }

    long recent = validations.countSince(account.getId(), Instant.now().minus(Duration.ofDays(1)));
    if (recent >= MAX_ATTEMPTS_PER_DAY) {
      throw new BadRequestException(
          "Too many verification attempts today. Try again tomorrow.");
    }

    UserEntity user = users.findById(helperId).orElseThrow();

    PayoutAccountValidationEntity validation = new PayoutAccountValidationEntity();
    validation.setPayoutAccountId(account.getId());
    validation.setHelperId(helperId);
    validation.setStatus(PayoutAccountValidationEntity.PENDING);
    validation.setAccountType("vpa");
    validation.setValidationType(DEFAULT_VALIDATION_TYPE);
    validation.setAttempts(1);
    try {
      validation = validations.saveAndFlush(validation);
    } catch (DataIntegrityViolationException e) {
      return validations.findInFlight(account.getId()).orElseThrow(() -> e);
    }

    account.setVerificationStatus("PENDING");
    accounts.save(account);

    try {
      RazorpayXGateway.CompositeValidationRequest request =
          new RazorpayXGateway.CompositeValidationRequest(
              "vpa",
              accountHolderName,
              null, // accountNumber not used for VPA
              null, // ifsc not used for VPA
              upiVpa,
              user.getDisplayName(),
              user.getPhone(),
              user.getEmail(),
              user.getId().toString(),
              DEFAULT_VALIDATION_TYPE);

      RazorpayXGateway.FundAccountValidationResult result =
          razorpayx.createCompositeValidation(request);
      validation.setProviderValidationId(result.id());
      apply(validation, account, result);
    } catch (RazorpayGatewayException e) {
      log.error(
          "UPI FAV for account {} could not be submitted and is left for polling: {}",
          account.getId(),
          e.getMessage());
    }

    return validations.save(validation);
  }

  /**
   * Applies a provider result — from the poll or from a webhook.
   *
   * <p>Terminal rows are never revisited, so a redelivered webhook is a no-op.
   */
  @Transactional
  public void applyProviderResult(
      PayoutAccountValidationEntity validation, RazorpayXGateway.FundAccountValidationResult result) {
    HelperPayoutAccountEntity account =
        accounts.findById(validation.getPayoutAccountId()).orElse(null);
    if (account == null) return;
    apply(validation, account, result);
    validations.save(validation);
    accounts.save(account);
  }

  private void apply(
      PayoutAccountValidationEntity validation,
      HelperPayoutAccountEntity account,
      RazorpayXGateway.FundAccountValidationResult result) {
    if (!PayoutAccountValidationEntity.PENDING.equals(validation.getStatus())) return;

    String status = result.status() == null ? "" : result.status().trim().toLowerCase(Locale.ROOT);
    if (result.utr() != null && !result.utr().isBlank()) validation.setUtr(result.utr());
    if (result.registeredName() != null) validation.setRegisteredName(result.registeredName());

    // Store structured failure details from status_details.
    if (result.failureSource() != null) validation.setFailureSource(result.failureSource());
    if (result.failureReasonCode() != null) validation.setFailureReasonCode(result.failureReasonCode());
    if (result.failureReason() != null) validation.setFailureReason(result.failureReason());

    // Store provider name match score when available (preferred over local computation).
    if (result.nameMatchScore() != null) {
      validation.setProviderNameMatchScore(result.nameMatchScore());
    }

    switch (status) {
      case "completed" -> {
        // Prefer Razorpay's score; fall back to our local word-set comparison.
        int score = result.nameMatchScore() != null
            ? result.nameMatchScore()
            : nameMatchScore(account.getAccountHolderName(), result.registeredName());
        validation.setNameMatchScore(score);
        validation.setCompletedAt(Instant.now());
        if (score >= NAME_MATCH_THRESHOLD) {
          validation.setStatus(PayoutAccountValidationEntity.VERIFIED);
          account.setVerificationStatus("VERIFIED");
          account.setStatus("ACTIVE");
        } else {
          // The verification reached the bank — but the account belongs to someone
          // with a different name. That is precisely the fraud this exists to catch,
          // so a human looks at it before any payout can go out.
          validation.setStatus(PayoutAccountValidationEntity.MANUAL_REVIEW);
          if (validation.getFailureReason() == null) {
            validation.setFailureReason("Account holder name does not match our records");
          }
          account.setVerificationStatus("MANUAL_REVIEW");
          log.warn(
              "FAV name mismatch account={} score={} accountType={} — held for review",
              account.getId(),
              score,
              validation.getAccountType());
        }
      }
      case "failed" -> {
        validation.setStatus(PayoutAccountValidationEntity.FAILED);
        validation.setCompletedAt(Instant.now());
        account.setVerificationStatus("FAILED");
      }
      case "created", "pending", "processing" -> {
        // Still in flight. Nothing to do.
      }
      default ->
          log.warn(
              "Unrecognised validation status '{}' for {} — left PENDING",
              result.status(),
              validation.getId());
    }
  }

  /**
   * How closely the bank's registered name matches the one we hold.
   *
   * <p>Banks reformat names heavily — initials, honorifics, dropped middle names,
   * a spouse's name appended. Exact equality would send almost every genuine
   * account to manual review, so this compares normalised word sets: the score is
   * the share of the shorter name's words that appear in the longer one.
   *
   * <p>Used as fallback when Razorpay does not return a provider score.
   */
  static int nameMatchScore(String expected, String actual) {
    String[] a = normalizeName(expected);
    String[] b = normalizeName(actual);
    if (a.length == 0 || b.length == 0) return 0;

    String[] shorter = a.length <= b.length ? a : b;
    java.util.Set<String> longer =
        new java.util.HashSet<>(java.util.Arrays.asList(a.length <= b.length ? b : a));

    int matched = 0;
    for (String word : shorter) {
      if (longer.contains(word)) matched++;
    }
    return (int) Math.round((matched * 100.0) / shorter.length);
  }

  private static String[] normalizeName(String name) {
    if (name == null || name.isBlank()) return new String[0];
    String cleaned =
        name.toLowerCase(Locale.ROOT)
            // Honorifics carry no identity and appear inconsistently.
            .replaceAll("\\b(mr|mrs|ms|miss|dr|shri|smt|sri)\\b", " ")
            .replaceAll("[^a-z ]", " ")
            .trim();
    if (cleaned.isEmpty()) return new String[0];
    return java.util.Arrays.stream(cleaned.split("\\s+"))
        // Single letters are initials — "R" against "Ramesh" is not evidence either way.
        .filter(w -> w.length() > 1)
        .toArray(String[]::new);
  }

  private Optional<HelperPayoutAccountEntity> currentAccount(UUID helperId) {
    return accounts.findByHelperIdAndProviderAndCurrentTrue(
        helperId, HelperPayoutAccountEntity.DEFAULT_PROVIDER);
  }
}
