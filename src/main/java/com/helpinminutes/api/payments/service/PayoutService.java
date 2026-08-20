package com.helpinminutes.api.payments.service;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ConflictException;
import com.helpinminutes.api.errors.ServiceUnavailableException;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.repo.HelperPayoutAccountRepository;
import com.helpinminutes.api.helpers.security.BankAccountCipher;
import com.helpinminutes.api.payments.dto.PayoutDtos.LedgerLine;
import com.helpinminutes.api.payments.dto.PayoutDtos.PayoutItemResponse;
import com.helpinminutes.api.payments.dto.PayoutDtos.PayoutSummary;
import com.helpinminutes.api.payments.gateway.RazorpayXGateway;
import com.helpinminutes.api.payments.gateway.RazorpayGatewayException;
import com.helpinminutes.api.payments.model.LedgerEntryEntity;
import com.helpinminutes.api.payments.model.PayoutItemEntity;
import com.helpinminutes.api.payments.model.PayoutStatus;
import com.helpinminutes.api.payments.repo.PayoutItemRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partner withdrawals.
 *
 * <h2>Shipping dark</h2>
 *
 * Every entry point checks {@code app.payments.payoutsEnabled} first and refuses
 * with a 503 when it is off — the shipped default. The app hides the withdraw screen
 * from the same flag delivered on {@code /me}, but that is presentation; this is the
 * enforcement (rule 5).
 *
 * <h2>Paying twice is the only unrecoverable mistake</h2>
 *
 * Four things stop it, in order of when they act:
 *
 * <ol>
 *   <li>A partial unique index allows one PENDING-or-PROCESSING item per partner, so
 *       a double tap loses at the database rather than in a check that raced.
 *   <li>The idempotency key is generated <em>before</em> the provider is called and
 *       stored on the row, so a retry reuses it.
 *   <li>RazorpayX deduplicates on that key for 24 hours, so a socket timeout —
 *       indistinguishable from a failure — resolves to the payout it already made.
 *   <li>The ledger entry is written in the same transaction as the item, so a balance
 *       can never be spent twice even if the provider call is repeated.
 * </ol>
 *
 * <h2>A timeout is not a failure</h2>
 *
 * If the provider call throws, the item stays PROCESSING and the reconciliation job
 * asks RazorpayX what actually happened. Marking it FAILED here would put the money
 * back on the balance while it was still in flight.
 */
@Service
public class PayoutService {
  private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

  private final PayoutItemRepository items;
  private final LedgerService ledger;
  private final RazorpayXGateway razorpayx;
  private final HelperPayoutAccountRepository payoutAccounts;
  private final BankAccountCipher cipher;
  private final UserRepository users;
  private final AppProperties props;

  public PayoutService(
      PayoutItemRepository items,
      LedgerService ledger,
      RazorpayXGateway razorpayx,
      HelperPayoutAccountRepository payoutAccounts,
      BankAccountCipher cipher,
      UserRepository users,
      AppProperties props) {
    this.items = items;
    this.ledger = ledger;
    this.razorpayx = razorpayx;
    this.payoutAccounts = payoutAccounts;
    this.cipher = cipher;
    this.users = users;
    this.props = props;
  }

  @Transactional(readOnly = true)
  public PayoutSummary summary(UUID userId) {
    long balance = ledger.balancePaise(userId);
    List<LedgerLine> recent = ledger.recent(userId, 20).stream()
        .map(PayoutService::toLine)
        .toList();
    return new PayoutSummary(
        Math.max(0L, balance),
        // Shown separately rather than as a negative balance. During the cash-only
        // period this is the normal state — the partner collected directly and owes
        // commission — and "your balance is -₹67" reads as a bug, not as a bill.
        Math.max(0L, -balance),
        ledger.lifetimeEarningsPaise(userId),
        props.payments().minPayoutPaise(),
        props.payments().payoutsEnabled(),
        // "Ready" means ready to receive money, which an unverified account is not.
        currentAccount(userId).filter(PayoutService::isVerified).isPresent(),
        items.existsByUserIdAndStatusIn(userId, List.of(PayoutStatus.PENDING, PayoutStatus.PROCESSING)),
        recent);
  }

  @Transactional(readOnly = true)
  public List<PayoutItemResponse> history(UUID userId, int limit) {
    return items.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, limit))).stream()
        .map(PayoutService::toResponse)
        .toList();
  }

  /**
   * Requests a withdrawal.
   *
   * @param amountPaise null means "everything available"
   */
  @Transactional
  public PayoutItemResponse requestPayout(UUID userId, UserRole role, Long amountPaise) {
    requireEnabled();
    if (role != UserRole.HELPER && role != UserRole.MEDIATOR) {
      throw new BadRequestException("Only partners can withdraw earnings.");
    }

    long available = ledger.balancePaise(userId);
    long requested = amountPaise == null ? available : amountPaise;
    if (requested <= 0) {
      throw new BadRequestException("There is nothing available to withdraw.");
    }
    if (requested > available) {
      throw new BadRequestException("That is more than your available balance.");
    }
    long minimum = props.payments().minPayoutPaise();
    if (requested < minimum) {
      throw new BadRequestException(
          "The smallest withdrawal is " + rupees(minimum) + ".");
    }

    HelperPayoutAccountEntity account = currentAccount(userId)
        .orElseThrow(() -> new BadRequestException("Add a bank account before withdrawing."));
    // The destination must have been proved to exist and to belong to this partner.
    // This check did not exist: the account's verification_status was written once
    // as NOT_STARTED and then never read, so enabling payouts would have sent money
    // to destinations nobody had ever checked. See PayoutAccountValidationService.
    if (!isVerified(account)) {
      throw new BadRequestException(
          "Verify your bank account before withdrawing. We send ₹1 to confirm it is yours.");
    }

    PayoutItemEntity item = new PayoutItemEntity();
    item.setId(UUID.randomUUID());
    item.setUserId(userId);
    item.setPayoutAccountId(account.getId());
    item.setAmountPaise(requested);
    item.setStatus(PayoutStatus.PENDING);
    // Derived from the item id, which exists before the provider is contacted. A key
    // generated at call time would be new on every retry, which is the same as having none.
    item.setIdempotencyKey("payout_" + item.getId());
    item.setRequestedAt(Instant.now());

    try {
      items.saveAndFlush(item);
    } catch (DataIntegrityViolationException e) {
      // The partial unique index on (user_id) where status in (PENDING, PROCESSING).
      throw new ConflictException("A withdrawal is already in progress.");
    }

    // Booked in this transaction, alongside the item: the balance is spent the moment
    // the request exists, so a second request cannot see it as still available.
    ledger.recordPayout(userId, item.getId(), requested, item.getIdempotencyKey());

    send(item, account);
    return toResponse(item);
  }

  /**
   * Hands the item to RazorpayX.
   *
   * <p>Kept out of {@link #requestPayout}'s critical path only in the sense that it
   * cannot roll it back: a provider failure leaves the item PROCESSING for the
   * reconciliation job, never PENDING-and-forgotten and never FAILED-while-in-flight.
   */
  private void send(PayoutItemEntity item, HelperPayoutAccountEntity account) {
    if (!razorpayx.isConfigured()) {
      // Enabled but unconfigured. Refuse loudly rather than leaving an item that will
      // never be sent sitting against a balance the partner can no longer see.
      throw new ServiceUnavailableException("Withdrawals are temporarily unavailable.");
    }
    item.setStatus(PayoutStatus.PROCESSING);
    item.setAttempts(item.getAttempts() + 1);
    items.save(item);

    try {
      UserEntity user = users.findById(item.getUserId()).orElseThrow();
      String contactId = razorpayx.ensureContact(
          user.getId().toString(), user.getDisplayName(), user.getPhone(), user.getEmail());
      String accountNumber = cipher.decrypt(
          account.getId(), account.getAccountNumberKeyId(), account.getAccountNumberCiphertext());
      String fundAccountId = razorpayx.ensureFundAccount(
          contactId, account.getAccountHolderName(), accountNumber, account.getIfscCode());

      RazorpayXGateway.PayoutResult result = razorpayx.createPayout(
          fundAccountId, item.getAmountPaise(), "payout",
          "Superherooo earnings", item.getIdempotencyKey());

      item.setProviderPayoutId(result.id());
      applyProviderStatus(item, result.status(), result.utr(), result.failureReason());
      items.save(item);
    } catch (RazorpayGatewayException e) {
      // Deliberately left PROCESSING. The request may well have reached RazorpayX;
      // marking it failed here would restore a balance that is on its way to a bank.
      log.error("Payout {} could not be submitted and is left for reconciliation: {}",
          item.getId(), e.getMessage());
    }
  }

  /**
   * Maps RazorpayX's vocabulary onto ours, and books the reversal when one is due.
   *
   * <p>An unrecognised status leaves the item where it is on purpose: inventing a
   * terminal state for a word we do not know is how money goes missing in a version
   * upgrade.
   */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyProviderStatus(PayoutItemEntity item, String providerStatus, String utr, String failure) {
    String status = providerStatus == null ? "" : providerStatus.trim().toLowerCase(Locale.ROOT);
    if (utr != null && !utr.isBlank()) item.setUtr(utr);

    switch (status) {
      case "processed" -> {
        item.setStatus(PayoutStatus.PROCESSED);
        item.setSettledAt(Instant.now());
      }
      case "queued", "pending", "processing", "scheduled" -> item.setStatus(PayoutStatus.PROCESSING);
      case "reversed", "cancelled", "rejected", "failed" -> {
        PayoutStatus terminal =
            "reversed".equals(status) ? PayoutStatus.REVERSED : PayoutStatus.FAILED;
        if (!item.getStatus().isTerminal()) {
          item.setStatus(terminal);
          item.setFailureDescription(failure);
          item.setFailureCode(status);
          item.setSettledAt(Instant.now());
          // Only now does the money go back on the balance. Guarded by the terminal
          // check above so a redelivered webhook cannot credit it twice.
          ledger.recordPayoutReversal(item.getUserId(), item.getId(), item.getAmountPaise(),
              failure == null || failure.isBlank()
                  ? "Withdrawal returned by the bank"
                  : "Withdrawal returned: " + failure);
        }
      }
      default -> log.warn("Unrecognised RazorpayX payout status '{}' for {} — left as {}",
          providerStatus, item.getId(), item.getStatus());
    }
  }

  /** Verified by penny drop and active. Anything else is not a payable destination. */
  private static boolean isVerified(HelperPayoutAccountEntity account) {
    return account != null
        && "VERIFIED".equals(account.getVerificationStatus())
        && "ACTIVE".equals(account.getStatus());
  }

  private Optional<HelperPayoutAccountEntity> currentAccount(UUID userId) {
    return payoutAccounts.findByHelperIdAndProviderAndCurrentTrue(
        userId, HelperPayoutAccountEntity.DEFAULT_PROVIDER);
  }

  private void requireEnabled() {
    if (!props.payments().payoutsEnabled()) {
      throw new ServiceUnavailableException(
          "Withdrawals are not available yet. Earnings are settled directly with the citizen.");
    }
  }

  private static String rupees(long paise) {
    return "₹" + (paise / 100);
  }

  private static LedgerLine toLine(LedgerEntryEntity entry) {
    return new LedgerLine(
        entry.getId(),
        entry.getEntryType().name(),
        entry.getAmountPaise(),
        entry.getDescription(),
        entry.getCreatedAt());
  }

  private static PayoutItemResponse toResponse(PayoutItemEntity item) {
    return new PayoutItemResponse(
        item.getId(),
        item.getAmountPaise(),
        item.getStatus().name(),
        item.getUtr(),
        item.getFailureDescription(),
        item.getRequestedAt(),
        item.getSettledAt());
  }
}
