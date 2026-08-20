package com.helpinminutes.api.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.payments.model.LedgerEntryEntity;
import com.helpinminutes.api.payments.model.LedgerEntryType;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.payments.repo.LedgerEntryRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The books.
 *
 * <p>Two things have to be exactly right, and neither is obvious:
 *
 * <ol>
 *   <li><b>A cash job must not create a payable.</b> The partner already has the
 *       money; what the platform holds is a <em>receivable</em> for its commission.
 *       Booking only the earning would show a balance owed to the partner, and on
 *       the day payouts went live it would be paid out — money the platform never
 *       received, sent to somebody who was already paid.
 *   <li><b>A completion books once.</b> Retries, redelivered webhooks and double
 *       taps all reach this code, and each extra booking is real money.
 * </ol>
 */
class LedgerServiceTest {

  private static final long GROSS = 45_000L;      // ₹450
  private static final long COMMISSION = 6_750L;  // 15%

  private final List<LedgerEntryEntity> saved = new ArrayList<>();
  private final UUID helper = UUID.randomUUID();
  private LedgerEntryRepository entries;
  private LedgerService ledger;

  @BeforeEach
  void setUp() {
    saved.clear();
    entries = mock(LedgerEntryRepository.class);
    when(entries.save(any(LedgerEntryEntity.class))).thenAnswer(invocation -> {
      LedgerEntryEntity entry = invocation.getArgument(0);
      saved.add(entry);
      return entry;
    });
    when(entries.existsByTaskIdAndUserIdAndEntryType(any(), any(), any())).thenAnswer(invocation ->
        saved.stream().anyMatch(e ->
            java.util.Objects.equals(e.getTaskId(), invocation.getArgument(0))
                && e.getUserId().equals(invocation.getArgument(1))
                && e.getEntryType() == invocation.getArgument(2)));
    ledger = new LedgerService(entries, propsWith(true, 1500), commissionsAt(1500));
  }

  /** A CommissionService that always resolves to the given rate. */
  private static com.helpinminutes.api.payments.service.CommissionService commissionsAt(int bps) {
    com.helpinminutes.api.payments.service.CommissionService commissions =
        mock(com.helpinminutes.api.payments.service.CommissionService.class);
    when(commissions.globalBps()).thenReturn(bps);
    when(commissions.resolveBps(any(), any())).thenReturn(bps);
    return commissions;
  }

  private static AppProperties propsWith(boolean ledgerEnabled, int commissionBps) {
    AppProperties props = mock(AppProperties.class);
    when(props.payments()).thenReturn(
        new AppProperties.Payments(false, ledgerEnabled, false, commissionBps, 10_000L));
    return props;
  }

  private TaskEntity task(PaymentCollectionMode mode) {
    TaskEntity task = new TaskEntity();
    task.setId(UUID.randomUUID());
    task.setAssignedHelperId(helper);
    task.setBudgetPaise(GROSS);
    task.setTitle("Move a sofa");
    task.setPaymentCollectionMode(mode);
    return task;
  }

  private long balance() {
    return saved.stream().mapToLong(LedgerEntryEntity::getAmountPaise).sum();
  }

  @Test
  void aPrepaidJobLeavesTheNetPayableToThePartner() {
    ledger.recordTaskCompletion(task(PaymentCollectionMode.ONLINE_PREPAID));

    assertEquals(2, saved.size());
    assertEquals(GROSS, amountOf(LedgerEntryType.EARNING));
    assertEquals(-COMMISSION, amountOf(LedgerEntryType.COMMISSION));
    assertEquals(GROSS - COMMISSION, balance());
  }

  @Test
  void aCashJobLeavesThePartnerOwingTheCommission() {
    ledger.recordTaskCompletion(task(PaymentCollectionMode.PAY_AFTER_SERVICE));

    assertEquals(3, saved.size());
    assertEquals(GROSS, amountOf(LedgerEntryType.EARNING));
    assertEquals(-COMMISSION, amountOf(LedgerEntryType.COMMISSION));
    assertEquals(-GROSS, amountOf(LedgerEntryType.DIRECT_COLLECTION));
    // Negative: the platform is owed, not owing. Paying this out would be paying a
    // partner who was already paid in cash.
    assertEquals(-COMMISSION, balance());
  }

  @Test
  void aCompletionProcessedTwiceBooksOnce() {
    TaskEntity task = task(PaymentCollectionMode.ONLINE_PREPAID);

    ledger.recordTaskCompletion(task);
    ledger.recordTaskCompletion(task);
    ledger.recordTaskCompletion(task);

    assertEquals(2, saved.size());
    assertEquals(GROSS - COMMISSION, balance());
  }

  @Test
  void nothingIsBookedWhenTheLedgerIsSwitchedOff() {
    LedgerService disabled = new LedgerService(entries, propsWith(false, 1500), commissionsAt(1500));

    disabled.recordTaskCompletion(task(PaymentCollectionMode.ONLINE_PREPAID));

    assertTrue(saved.isEmpty());
    verify(entries, never()).save(any());
  }

  @Test
  void commissionRoundsInThePartnersFavour() {
    // ₹100.01 at 15% is 150.15 paise. Integer division keeps the fraction with the
    // partner, and does so identically on every machine — a floating-point rate
    // would not, and money that depends on the machine is a reconciliation problem.
    assertEquals(1_501L, ledger.commissionPaise(10_010L));
    assertEquals(0L, ledger.commissionPaise(6L));
    assertEquals(0L, ledger.commissionPaise(0L));
    assertEquals(0L, ledger.commissionPaise(-100L));
  }

  @Test
  void aZeroCommissionRateBooksNoCommissionRow() {
    LedgerService free = new LedgerService(entries, propsWith(true, 0), commissionsAt(0));

    free.recordTaskCompletion(task(PaymentCollectionMode.ONLINE_PREPAID));

    assertEquals(1, saved.size());
    assertEquals(GROSS, balance());
  }

  @Test
  void anUnassignedOrUnpricedTaskBooksNothing() {
    TaskEntity unassigned = task(PaymentCollectionMode.ONLINE_PREPAID);
    unassigned.setAssignedHelperId(null);
    ledger.recordTaskCompletion(unassigned);

    TaskEntity free = task(PaymentCollectionMode.ONLINE_PREPAID);
    free.setBudgetPaise(0L);
    ledger.recordTaskCompletion(free);

    ledger.recordTaskCompletion(null);

    assertTrue(saved.isEmpty());
  }

  @Test
  void aPayoutAndItsReversalCancelOut() {
    ledger.recordTaskCompletion(task(PaymentCollectionMode.ONLINE_PREPAID));
    long afterEarning = balance();
    UUID payoutId = UUID.randomUUID();

    ledger.recordPayout(helper, payoutId, afterEarning, "payout_1");
    assertEquals(0L, balance());

    ledger.recordPayoutReversal(helper, payoutId, afterEarning, "Bank returned it");
    assertEquals(afterEarning, balance(), "a returned payout must restore the exact balance");
  }

  @Test
  void aPayoutIsRecordedAsNegativeHoweverTheAmountIsPassed() {
    // Callers hold a positive amount; the sign is this service's business, not theirs.
    ledger.recordPayout(helper, UUID.randomUUID(), 5_000L, "ref");
    assertEquals(-5_000L, saved.get(saved.size() - 1).getAmountPaise());

    ledger.recordPayout(helper, UUID.randomUUID(), -5_000L, "ref");
    assertEquals(-5_000L, saved.get(saved.size() - 1).getAmountPaise());
  }

  private long amountOf(LedgerEntryType type) {
    return saved.stream()
        .filter(e -> e.getEntryType() == type)
        .mapToLong(LedgerEntryEntity::getAmountPaise)
        .sum();
  }
}
