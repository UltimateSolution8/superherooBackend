package com.helpinminutes.api.payments.gateway;

/**
 * Money out: contact → fund account → payout, RazorpayX's three-step model.
 *
 * <p>Separate from {@link RazorpayGateway} because it is a different product with
 * different credentials — money in and money out should not share a key, and an
 * account that can only collect cannot be used to drain a balance.
 *
 * <p>Every method throws {@link RazorpayGatewayException} rather than returning a
 * failure, because a payout that "quietly did not work" is the one outcome that must
 * never be mistaken for success.
 */
public interface RazorpayXGateway {

  /** False when no credentials are configured, which is the shipped state. */
  boolean isConfigured();

  /**
   * The partner as a RazorpayX contact, created once and reused.
   *
   * @param referenceId our user id, so a contact can be traced back without a lookup table
   */
  String ensureContact(String referenceId, String name, String phone, String email);

  /** The partner's bank account, attached to their contact. Created once and reused. */
  String ensureFundAccount(String contactId, String accountHolderName, String accountNumber, String ifsc);

  /**
   * Sends money.
   *
   * @param idempotencyKey passed as {@code X-Payout-Idempotency}. A retry after a
   *     timeout returns the payout the first attempt created rather than making a
   *     second one — the single most important property of this call, because the
   *     failure mode is paying twice.
   */
  PayoutResult createPayout(
      String fundAccountId, long amountPaise, String purpose, String narration, String idempotencyKey);

  /** Current state, for the reconciliation job when no webhook arrived. */
  PayoutResult fetchPayout(String payoutId);

  /** Verifies a webhook came from RazorpayX and not from someone who guessed the URL. */
  boolean verifyWebhookSignature(String rawBody, String signature);

  /**
   * Starts a penny drop: a ₹1 credit to the destination account, which comes back
   * with the name the bank actually holds it under.
   *
   * <p>This is the only way to know a bank account is real and belongs to the person
   * claiming it. Without it {@code verification_status} could never leave
   * NOT_STARTED, so turning payouts on would have sent money to destinations that
   * had never been checked — an IFSC and a plausible account number are not evidence
   * that the account exists.
   *
   * <p>Each call costs real money, so callers must rate-limit.
   */
  FundAccountValidationResult createFundAccountValidation(String fundAccountId, String currency);

  /** Current state of a validation, for the polling job when no webhook arrived. */
  FundAccountValidationResult fetchFundAccountValidation(String validationId);

  /**
   * @param status RazorpayX's vocabulary — created, completed, failed. Mapped by the
   *     caller so an unrecognised value cannot silently become terminal.
   * @param registeredName the account holder's name as the bank has it. Compared
   *     against our KYC name; a mismatch is never auto-verified.
   */
  record FundAccountValidationResult(
      String id,
      String status,
      String registeredName,
      String utr,
      long amountPaise,
      String failureReason) {}

  /**
   * @param status RazorpayX's own vocabulary — queued, processing, processed,
   *     reversed, cancelled, failed. Mapped to {@code PayoutStatus} by the caller so
   *     an unrecognised value cannot silently become a terminal state.
   * @param utr the bank reference, present once processed
   */
  record PayoutResult(
      String id,
      long amountPaise,
      String status,
      String utr,
      String failureReason) {}
}
