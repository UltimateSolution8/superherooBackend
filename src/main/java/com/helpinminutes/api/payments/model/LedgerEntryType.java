package com.helpinminutes.api.payments.model;

/**
 * What a ledger row represents, from the platform's point of view.
 *
 * <p>Amounts are signed: positive is money the platform owes the partner, negative
 * is money it does not. A partner's balance is the sum of their entries, which is
 * why the signs have to be right rather than convenient.
 */
public enum LedgerEntryType {
  /** Gross value of completed work. Positive. */
  EARNING,
  /** The platform's take on that work. Negative. */
  COMMISSION,
  /**
   * The partner was paid directly, in cash or UPI, by the citizen. Negative and
   * equal to the gross.
   *
   * <p>This is what keeps the cash-only launch honest. Without it, a cash job would
   * book an earning the platform never held and would appear as a payable. With it,
   * a cash job nets out to exactly minus the commission — which is the truth: the
   * partner has the money and owes us our share.
   */
  DIRECT_COLLECTION,
  /** A manual correction, by an admin, with a reason. Either sign. */
  ADJUSTMENT,
  /** Money actually sent to the partner's bank. Negative. */
  PAYOUT,
  /** A payout the provider returned. Positive, restoring the balance. */
  PAYOUT_REVERSAL
}
