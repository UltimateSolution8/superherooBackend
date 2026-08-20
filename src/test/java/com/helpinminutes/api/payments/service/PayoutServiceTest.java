package com.helpinminutes.api.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ServiceUnavailableException;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.repo.HelperPayoutAccountRepository;
import com.helpinminutes.api.helpers.security.BankAccountCipher;
import com.helpinminutes.api.payments.dto.PayoutDtos.PayoutItemResponse;
import com.helpinminutes.api.payments.dto.PayoutDtos.PayoutSummary;
import com.helpinminutes.api.payments.gateway.RazorpayGatewayException;
import com.helpinminutes.api.payments.gateway.RazorpayXGateway;
import com.helpinminutes.api.payments.model.PayoutItemEntity;
import com.helpinminutes.api.payments.model.PayoutStatus;
import com.helpinminutes.api.payments.repo.PayoutItemRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Withdrawals.
 *
 * <p>The feature ships switched off, so the first thing to pin down is that it is
 * genuinely off at the endpoint and not merely hidden in the app (rule 5). After
 * that, everything here is about the one mistake that cannot be undone: paying twice,
 * or restoring a balance while the money is still moving.
 */
class PayoutServiceTest {

  private final UUID helper = UUID.randomUUID();
  private final List<PayoutItemEntity> stored = new ArrayList<>();

  private PayoutItemRepository items;
  private LedgerService ledger;
  private RazorpayXGateway razorpayx;
  private HelperPayoutAccountRepository accounts;

  @BeforeEach
  void setUp() {
    stored.clear();
    items = mock(PayoutItemRepository.class);
    when(items.saveAndFlush(any(PayoutItemEntity.class))).thenAnswer(save());
    when(items.save(any(PayoutItemEntity.class))).thenAnswer(save());
    when(items.existsByUserIdAndStatusIn(any(), any())).thenReturn(false);

    ledger = mock(LedgerService.class);
    razorpayx = mock(RazorpayXGateway.class);
    when(razorpayx.isConfigured()).thenReturn(true);
    when(razorpayx.ensureContact(anyString(), any(), any(), any())).thenReturn("cont_1");
    when(razorpayx.ensureFundAccount(anyString(), any(), any(), any())).thenReturn("fa_1");

    accounts = mock(HelperPayoutAccountRepository.class);
    when(accounts.findByHelperIdAndProviderAndCurrentTrue(any(), anyString()))
        .thenReturn(Optional.of(account()));
  }

  private org.mockito.stubbing.Answer<PayoutItemEntity> save() {
    return invocation -> {
      PayoutItemEntity item = invocation.getArgument(0);
      stored.removeIf(existing -> existing.getId().equals(item.getId()));
      stored.add(item);
      return item;
    };
  }

  /** A bank account that has passed penny-drop verification — the payable case. */
  private HelperPayoutAccountEntity account() {
    HelperPayoutAccountEntity account = unverifiedAccount();
    account.setVerificationStatus("VERIFIED");
    account.setStatus("ACTIVE");
    return account;
  }

  private HelperPayoutAccountEntity unverifiedAccount() {
    HelperPayoutAccountEntity account = new HelperPayoutAccountEntity();
    account.setId(UUID.randomUUID());
    account.setHelperId(helper);
    account.setAccountHolderName("Vikram Singh");
    account.setIfscCode("HDFC0001234");
    account.setVerificationStatus("NOT_STARTED");
    account.setStatus("PENDING_ACCOUNT_VERIFICATION");
    return account;
  }

  private PayoutService service(boolean payoutsEnabled, long balancePaise) {
    AppProperties props = mock(AppProperties.class);
    when(props.payments())
        .thenReturn(new AppProperties.Payments(false, true, payoutsEnabled, 1500, 10_000L));
    when(ledger.balancePaise(helper)).thenReturn(balancePaise);
    when(ledger.lifetimeEarningsPaise(helper)).thenReturn(Math.max(0L, balancePaise));
    when(ledger.recent(eq(helper), anyInt())).thenReturn(List.of());

    BankAccountCipher cipher = mock(BankAccountCipher.class);
    when(cipher.decrypt(any(), any(), any())).thenReturn("000111222333");

    UserRepository users = mock(UserRepository.class);
    UserEntity user = new UserEntity();
    user.setId(helper);
    user.setDisplayName("Vikram Singh");
    user.setPhone("9000000102");
    when(users.findById(helper)).thenReturn(Optional.of(user));

    return new PayoutService(items, ledger, razorpayx, accounts, cipher, users, props);
  }

  private static int anyInt() {
    return org.mockito.ArgumentMatchers.anyInt();
  }

  @Test
  void theEndpointRefusesWhilePayoutsAreSwitchedOff() {
    PayoutService payouts = service(false, 100_000L);

    assertThrows(ServiceUnavailableException.class,
        () -> payouts.requestPayout(helper, UserRole.HELPER, 50_000L));
    verify(razorpayx, never()).createPayout(any(), anyLong(), any(), any(), any());
  }

  @Test
  void theSummaryIsStillReadableWhilePayoutsAreOff() {
    // A partner should be able to see what they have earned during the cash-only
    // period even though nothing can be withdrawn yet.
    PayoutSummary summary = service(false, 38_250L).summary(helper);

    assertEquals(38_250L, summary.availablePaise());
    assertFalse(summary.payoutsEnabled());
  }

  @Test
  void aNegativeBalanceIsShownAsOwedRatherThanAsANegativeAvailable() {
    // The normal cash-only state: the partner collected directly and owes commission.
    PayoutSummary summary = service(true, -6_750L).summary(helper);

    assertEquals(0L, summary.availablePaise());
    assertEquals(6_750L, summary.owedToPlatformPaise());
  }

  @Test
  void aPartnerCannotWithdrawMoreThanTheyHave() {
    PayoutService payouts = service(true, 20_000L);

    assertThrows(BadRequestException.class,
        () -> payouts.requestPayout(helper, UserRole.HELPER, 20_001L));
  }

  @Test
  void amountsBelowTheMinimumAreRefused() {
    PayoutService payouts = service(true, 50_000L);

    assertThrows(BadRequestException.class,
        () -> payouts.requestPayout(helper, UserRole.HELPER, 9_999L));
  }

  @Test
  void aCitizenCannotWithdrawAtAll() {
    PayoutService payouts = service(true, 50_000L);

    assertThrows(BadRequestException.class,
        () -> payouts.requestPayout(helper, UserRole.BUYER, 50_000L));
  }

  @Test
  void withoutABankAccountThereIsNowhereToSendIt() {
    when(accounts.findByHelperIdAndProviderAndCurrentTrue(any(), anyString()))
        .thenReturn(Optional.empty());

    assertThrows(BadRequestException.class,
        () -> service(true, 50_000L).requestPayout(helper, UserRole.HELPER, 50_000L));
  }

  @Test
  void aSuccessfulRequestSpendsTheBalanceInTheSameTransaction() {
    when(razorpayx.createPayout(any(), anyLong(), any(), any(), any()))
        .thenReturn(new RazorpayXGateway.PayoutResult("pout_1", 50_000L, "processing", null, null));

    PayoutItemResponse response = service(true, 50_000L).requestPayout(helper, UserRole.HELPER, 50_000L);

    assertEquals(50_000L, response.amountPaise());
    // Booked when the request is created, not when it settles: otherwise a second
    // request a moment later sees the same balance and spends it again.
    verify(ledger).recordPayout(eq(helper), any(), eq(50_000L), anyString());
  }

  @Test
  void theIdempotencyKeyExistsBeforeTheProviderIsCalled() {
    when(razorpayx.createPayout(any(), anyLong(), any(), any(), any()))
        .thenReturn(new RazorpayXGateway.PayoutResult("pout_1", 50_000L, "processed", "UTR1", null));

    service(true, 50_000L).requestPayout(helper, UserRole.HELPER, 50_000L);

    PayoutItemEntity item = stored.get(0);
    assertNotNull(item.getIdempotencyKey());
    assertTrue(item.getIdempotencyKey().contains(item.getId().toString()),
        "the key must be derived from the row, so a retry sends the same one");
    verify(razorpayx).createPayout(any(), eq(50_000L), any(), any(), eq(item.getIdempotencyKey()));
  }

  @Test
  void aProviderFailureLeavesThePayoutInFlightRatherThanRestoringTheBalance() {
    when(razorpayx.createPayout(any(), anyLong(), any(), any(), any()))
        .thenThrow(new RazorpayGatewayException("connection reset"));

    service(true, 50_000L).requestPayout(helper, UserRole.HELPER, 50_000L);

    // A timeout is not a failure: the money may already be moving. Marking it FAILED
    // here would credit the balance back while the bank transfer completes.
    assertEquals(PayoutStatus.PROCESSING, stored.get(0).getStatus());
    verify(ledger, never()).recordPayoutReversal(any(), any(), anyLong(), any());
  }

  @Test
  void refusesToPayAnAccountThatHasNotPassedPennyDropVerification() {
    when(accounts.findByHelperIdAndProviderAndCurrentTrue(any(), any()))
        .thenReturn(Optional.of(unverifiedAccount()));

    // Before this guard existed, verification_status was written once as
    // NOT_STARTED and never read again — so enabling payouts would have sent money
    // to destinations nobody had ever confirmed were real, let alone the partner's.
    assertThrows(BadRequestException.class,
        () -> service(true, 50_000L).requestPayout(helper, UserRole.HELPER, 50_000L));
    verify(razorpayx, never()).createPayout(any(), anyLong(), any(), any(), any());
  }

  @Test
  void anUnconfiguredProviderRefusesRatherThanStrandingTheBalance() {
    when(razorpayx.isConfigured()).thenReturn(false);

    assertThrows(ServiceUnavailableException.class,
        () -> service(true, 50_000L).requestPayout(helper, UserRole.HELPER, 50_000L));
  }

  @Test
  void aReversedPayoutRestoresTheBalanceExactlyOnce() {
    PayoutService payouts = service(true, 50_000L);
    PayoutItemEntity item = new PayoutItemEntity();
    item.setId(UUID.randomUUID());
    item.setUserId(helper);
    item.setAmountPaise(50_000L);
    item.setStatus(PayoutStatus.PROCESSING);

    payouts.applyProviderStatus(item, "reversed", null, "Account closed");
    payouts.applyProviderStatus(item, "reversed", null, "Account closed");

    assertEquals(PayoutStatus.REVERSED, item.getStatus());
    verify(ledger, org.mockito.Mockito.times(1))
        .recordPayoutReversal(eq(helper), eq(item.getId()), eq(50_000L), anyString());
  }

  @Test
  void anUnknownProviderStatusChangesNothing() {
    PayoutService payouts = service(true, 50_000L);
    PayoutItemEntity item = new PayoutItemEntity();
    item.setId(UUID.randomUUID());
    item.setUserId(helper);
    item.setAmountPaise(50_000L);
    item.setStatus(PayoutStatus.PROCESSING);

    payouts.applyProviderStatus(item, "some_new_state_razorpay_invented", null, null);

    // Inventing a terminal state for a word we do not know is how money goes missing
    // in a provider version upgrade.
    assertEquals(PayoutStatus.PROCESSING, item.getStatus());
    verify(ledger, never()).recordPayoutReversal(any(), any(), anyLong(), any());
  }

  @Test
  void aProcessedPayoutKeepsItsBankReference() {
    PayoutService payouts = service(true, 50_000L);
    PayoutItemEntity item = new PayoutItemEntity();
    item.setId(UUID.randomUUID());
    item.setUserId(helper);
    item.setAmountPaise(50_000L);
    item.setStatus(PayoutStatus.PROCESSING);

    payouts.applyProviderStatus(item, "processed", "UTR123456", null);

    assertEquals(PayoutStatus.PROCESSED, item.getStatus());
    assertEquals("UTR123456", item.getUtr());
    assertNotNull(item.getSettledAt());
  }
}
