package com.helpinminutes.api.payments.dto;

import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PayoutDtos {
  private PayoutDtos() {}

  /**
   * What a partner sees on the earnings screen.
   *
   * @param availablePaise what can be withdrawn right now — never negative in the
   *     response even when the ledger balance is, which happens legitimately during
   *     the cash-only period: the partner has collected directly and owes commission
   * @param owedToPlatformPaise the other side of that, shown as its own number
   *     rather than a negative balance, because "your balance is -₹67" reads as an error
   * @param payoutsEnabled whether the feature is switched on at all; the app hides
   *     withdrawal entirely when false
   */
  public record PayoutSummary(
      long availablePaise,
      long owedToPlatformPaise,
      long lifetimeEarningsPaise,
      long minPayoutPaise,
      boolean payoutsEnabled,
      boolean bankAccountReady,
      boolean payoutInFlight,
      List<LedgerLine> recentEntries) {}

  public record LedgerLine(
      UUID id,
      String type,
      long amountPaise,
      String description,
      Instant createdAt) {}

  public record PayoutRequest(@Min(1) Long amountPaise) {}

  public record PayoutItemResponse(
      UUID id,
      long amountPaise,
      String status,
      String utr,
      String failureDescription,
      Instant requestedAt,
      Instant settledAt) {}
}
