package com.helpinminutes.api.payments.model;

/**
 * PENDING → PROCESSING → PROCESSED | FAILED | REVERSED.
 *
 * <p>PROCESSING means the provider has accepted it and we are waiting; it is the
 * state in which we must never re-send, because the money may already be moving.
 * REVERSED is the provider returning a payout that had reached PROCESSED — a wrong
 * account number usually — and is why the ledger has PAYOUT_REVERSAL.
 */
public enum PayoutStatus {
  PENDING,
  PROCESSING,
  PROCESSED,
  FAILED,
  REVERSED;

  public boolean isTerminal() {
    return this == PROCESSED || this == FAILED || this == REVERSED;
  }

  public boolean isInFlight() {
    return this == PENDING || this == PROCESSING;
  }
}
