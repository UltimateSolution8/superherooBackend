package com.helpinminutes.api.payments.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.payments.model.LedgerEntryEntity;
import com.helpinminutes.api.payments.model.LedgerEntryType;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.payments.repo.LedgerEntryRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each partner has earned, and what the platform is owed.
 *
 * <h2>Why entries rather than a balance column</h2>
 *
 * A stored balance is wrong the first time two writes interleave, and from then on
 * every later value is wrong with nothing to check it against. An append-only table
 * summed on read cannot drift, and it can answer "why is my balance this?" — which
 * is the question that actually gets asked.
 *
 * <h2>The three entries a completed task books</h2>
 *
 * <pre>
 *   Online prepaid (₹450, 15%):     Cash or UPI, collected directly:
 *     EARNING            +45000       EARNING            +45000
 *     COMMISSION          -6750       COMMISSION          -6750
 *                        ------       DIRECT_COLLECTION  -45000
 *     balance            +38250                          ------
 *                                     balance             -6750
 * </pre>
 *
 * The cash case is the one worth reading twice. The partner already has the money,
 * so the platform owes them nothing — it is owed <em>its commission</em>. Booking
 * only the earning would show a payable that does not exist, and the day payouts
 * went live it would be paid.
 *
 * <h2>Booked once, ever</h2>
 *
 * A unique index on {@code (task_id, user_id, entry_type)} is the guarantee, not a
 * check-then-insert: a redelivered webhook, a retried transaction and a double tap
 * all lose the race at the database rather than in application code that has to
 * remember to look.
 */
@Service
public class LedgerService {
  private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

  private static final int BPS_DIVISOR = 10_000;

  private final LedgerEntryRepository entries;
  private final AppProperties props;
  private final CommissionService commissions;

  public LedgerService(
      LedgerEntryRepository entries, AppProperties props, CommissionService commissions) {
    this.entries = entries;
    this.props = props;
    this.commissions = commissions;
  }

  /**
   * Commission in paise, rounded in the partner's favour.
   *
   * <p>Integer division truncates, which leaves the fraction of a paisa with the
   * partner. That is the right side to lose it on, and it is deterministic — a
   * floating-point rate would give a different answer on a different machine, and
   * money that depends on the machine is a reconciliation problem later (rule 4).
   */
  public long commissionPaise(long grossPaise) {
    return commissionPaise(grossPaise, commissions.globalBps());
  }

  /** Same arithmetic against an explicitly resolved rate. */
  public long commissionPaise(long grossPaise, int bps) {
    if (grossPaise <= 0) return 0L;
    if (bps <= 0) return 0L;
    return Math.multiplyExact(grossPaise, (long) bps) / BPS_DIVISOR;
  }

  /** The rate that applies to a partner and category, in basis points. */
  public int resolveBps(java.util.UUID helperId, String category) {
    return commissions.resolveBps(helperId, category);
  }

  /**
   * Books a completed task.
   *
   * <p>Runs in its own transaction: the booking must not be able to roll back the
   * completion that triggered it. A partner who finished the job has finished it
   * even if the ledger write loses a race, and the missing entry is recoverable —
   * an unmarked completion is not.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordTaskCompletion(TaskEntity task) {
    if (!props.payments().ledgerEnabled()) return;
    if (task == null || task.getAssignedHelperId() == null) return;

    UUID helperId = task.getAssignedHelperId();
    long gross = task.getBudgetPaise() == null ? 0L : task.getBudgetPaise();
    if (gross <= 0) return;

    // Tasks carry no category field today, so only the HELPER and GLOBAL scopes
    // can resolve. The CATEGORY scope exists in the settings table and the admin
    // API and starts working the moment tasks gain a category.
    int bps = commissions.resolveBps(helperId, null);
    long commission = commissionPaise(gross, bps);
    boolean collectedDirectly =
        task.getPaymentCollectionMode() != PaymentCollectionMode.ONLINE_PREPAID;

    try {
      append(helperId, LedgerEntryType.EARNING, gross, task.getId(),
          "Completed: " + safeTitle(task));
      if (commission > 0) {
        // The rate is stored on the entry as well as applied, so a historical row
        // is self-describing and does not have to be re-derived from the settings
        // table as it stood at the time.
        append(helperId, LedgerEntryType.COMMISSION, -commission, task.getId(),
            "Platform commission at " + bps / 100.0 + "%", bps);
      }
      if (collectedDirectly) {
        append(helperId, LedgerEntryType.DIRECT_COLLECTION, -gross, task.getId(),
            "Collected from the citizen directly");
      }
    } catch (DataIntegrityViolationException e) {
      // The unique index did its job: this task was already booked. Not an error.
      log.debug("Ledger entries for task {} already exist", task.getId());
    }
  }

  /** Records money actually sent. Negative, because it leaves the balance. */
  @Transactional(propagation = Propagation.MANDATORY)
  public LedgerEntryEntity recordPayout(UUID userId, UUID payoutItemId, long amountPaise, String reference) {
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setUserId(userId);
    entry.setEntryType(LedgerEntryType.PAYOUT);
    entry.setAmountPaise(-Math.abs(amountPaise));
    entry.setPayoutItemId(payoutItemId);
    entry.setReference(reference);
    entry.setDescription("Payout to bank account");
    return entries.save(entry);
  }

  /** Puts a returned or failed payout back on the balance. */
  @Transactional(propagation = Propagation.MANDATORY)
  public LedgerEntryEntity recordPayoutReversal(
      UUID userId, UUID payoutItemId, long amountPaise, String reason) {
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setUserId(userId);
    entry.setEntryType(LedgerEntryType.PAYOUT_REVERSAL);
    entry.setAmountPaise(Math.abs(amountPaise));
    entry.setPayoutItemId(payoutItemId);
    entry.setDescription(reason == null || reason.isBlank() ? "Payout returned" : reason);
    return entries.save(entry);
  }

  /** An admin correction. Signed, and it must carry a reason. */
  @Transactional
  public LedgerEntryEntity adjust(UUID userId, long amountPaise, String reason) {
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setUserId(userId);
    entry.setEntryType(LedgerEntryType.ADJUSTMENT);
    entry.setAmountPaise(amountPaise);
    entry.setDescription(reason);
    return entries.save(entry);
  }

  @Transactional(readOnly = true)
  public long balancePaise(UUID userId) {
    return entries.balancePaise(userId);
  }

  @Transactional(readOnly = true)
  public long lifetimeEarningsPaise(UUID userId) {
    return entries.totalByType(userId, LedgerEntryType.EARNING);
  }

  @Transactional(readOnly = true)
  public List<LedgerEntryEntity> recent(UUID userId, int limit) {
    return entries.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, limit)));
  }

  private void append(UUID userId, LedgerEntryType type, long amountPaise, UUID taskId, String description) {
    append(userId, type, amountPaise, taskId, description, null);
  }

  private void append(
      UUID userId,
      LedgerEntryType type,
      long amountPaise,
      UUID taskId,
      String description,
      Integer commissionBps) {
    if (entries.existsByTaskIdAndUserIdAndEntryType(taskId, userId, type)) return;
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setUserId(userId);
    entry.setEntryType(type);
    entry.setAmountPaise(amountPaise);
    entry.setTaskId(taskId);
    entry.setDescription(description);
    entry.setCommissionBps(commissionBps);
    entries.save(entry);
  }

  private static String safeTitle(TaskEntity task) {
    String title = task.getTitle();
    if (title == null || title.isBlank()) return "task";
    return title.length() <= 200 ? title : title.substring(0, 200);
  }
}
