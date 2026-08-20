package com.helpinminutes.api.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.repo.HelperPayoutAccountRepository;
import com.helpinminutes.api.helpers.security.BankAccountCipher;
import com.helpinminutes.api.payments.gateway.RazorpayXGateway;
import com.helpinminutes.api.payments.model.PayoutAccountValidationEntity;
import com.helpinminutes.api.payments.repo.PayoutAccountValidationRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayoutAccountValidationServiceTest {

  private PayoutAccountValidationRepository validations;
  private HelperPayoutAccountRepository accounts;
  private RazorpayXGateway razorpayx;
  private PayoutAccountValidationService service;

  private final UUID helperId = UUID.randomUUID();
  private final UUID accountId = UUID.randomUUID();
  private HelperPayoutAccountEntity account;
  private final List<PayoutAccountValidationEntity> saved = new ArrayList<>();

  @BeforeEach
  void setUp() {
    saved.clear();

    account = new HelperPayoutAccountEntity();
    account.setId(accountId);
    account.setHelperId(helperId);
    account.setProvider(HelperPayoutAccountEntity.DEFAULT_PROVIDER);
    account.setStatus("PENDING_ACCOUNT_VERIFICATION");
    account.setVerificationStatus("NOT_STARTED");
    account.setAccountHolderName("Ramesh Kumar");
    account.setIfscCode("HDFC0001234");
    account.setAccountNumberKeyId("k1");
    account.setAccountNumberCiphertext("cipher");

    accounts = mock(HelperPayoutAccountRepository.class);
    when(accounts.findByHelperIdAndProviderAndCurrentTrue(any(), any()))
        .thenReturn(Optional.of(account));
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));
    when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));

    validations = mock(PayoutAccountValidationRepository.class);
    when(validations.findInFlight(any())).thenReturn(Optional.empty());
    when(validations.countSince(any(), any())).thenReturn(0L);
    when(validations.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              PayoutAccountValidationEntity v = i.getArgument(0);
              if (v.getId() == null) v.setId(UUID.randomUUID());
              saved.add(v);
              return v;
            });
    when(validations.save(any())).thenAnswer(i -> i.getArgument(0));

    UserRepository users = mock(UserRepository.class);
    UserEntity user = new UserEntity();
    user.setId(helperId);
    user.setDisplayName("Ramesh Kumar");
    user.setPhone("9000000001");
    when(users.findById(helperId)).thenReturn(Optional.of(user));

    BankAccountCipher cipher = mock(BankAccountCipher.class);
    when(cipher.decrypt(any(), anyString(), anyString())).thenReturn("123456789012");

    razorpayx = mock(RazorpayXGateway.class);
    when(razorpayx.isConfigured()).thenReturn(true);
    when(razorpayx.ensureContact(any(), any(), any(), any())).thenReturn("cont_1");
    when(razorpayx.ensureFundAccount(any(), any(), any(), any())).thenReturn("fa_1");

    service = new PayoutAccountValidationService(validations, accounts, users, razorpayx, cipher);
  }

  private void respondWith(String status, String registeredName) {
    when(razorpayx.createFundAccountValidation(any(), any()))
        .thenReturn(
            new RazorpayXGateway.FundAccountValidationResult(
                "fav_1", status, registeredName, "UTR123", 100L, null));
  }

  @Test
  void aMatchingNameVerifiesTheAccount() {
    respondWith("completed", "RAMESH KUMAR");

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.VERIFIED, result.getStatus());
    // This is the transition that was previously unreachable — nothing in the
    // codebase ever moved an account off NOT_STARTED.
    assertEquals("VERIFIED", account.getVerificationStatus());
    assertEquals("ACTIVE", account.getStatus());
  }

  @Test
  void aMismatchedNameIsHeldForReviewAndNeverAutoVerified() {
    // The drop succeeded — against an account held under someone else's name. That
    // is precisely the fraud this mechanism exists to catch.
    respondWith("completed", "Suresh Reddy");

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.MANUAL_REVIEW, result.getStatus());
    assertEquals("MANUAL_REVIEW", account.getVerificationStatus());
    assertTrue(!"ACTIVE".equals(account.getStatus()));
  }

  @Test
  void aFailedDropMarksTheAccountFailed() {
    when(razorpayx.createFundAccountValidation(any(), any()))
        .thenReturn(
            new RazorpayXGateway.FundAccountValidationResult(
                "fav_2", "failed", null, null, 100L, "Invalid account number"));

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.FAILED, result.getStatus());
    assertEquals("FAILED", account.getVerificationStatus());
  }

  @Test
  void returnsTheInFlightValidationRatherThanBuyingASecondDrop() {
    PayoutAccountValidationEntity existing = new PayoutAccountValidationEntity();
    existing.setId(UUID.randomUUID());
    existing.setPayoutAccountId(accountId);
    existing.setHelperId(helperId);
    when(validations.findInFlight(accountId)).thenReturn(Optional.of(existing));

    assertEquals(existing, service.startValidation(helperId));
    verify(razorpayx, never()).createFundAccountValidation(any(), any());
  }

  @Test
  void capsAttemptsPerAccountPerDay() {
    when(validations.countSince(any(), any()))
        .thenReturn((long) PayoutAccountValidationService.MAX_ATTEMPTS_PER_DAY);

    // Every drop is a real ₹1 transfer plus a fee.
    assertThrows(BadRequestException.class, () -> service.startValidation(helperId));
    verify(razorpayx, never()).createFundAccountValidation(any(), any());
  }

  @Test
  void refusesToRevalidateAnAlreadyVerifiedAccount() {
    account.setVerificationStatus("VERIFIED");
    assertThrows(BadRequestException.class, () -> service.startValidation(helperId));
  }

  @Test
  void aRedeliveredResultIsANoOpOnATerminalRow() {
    respondWith("completed", "RAMESH KUMAR");
    PayoutAccountValidationEntity result = service.startValidation(helperId);
    account.setVerificationStatus("VERIFIED");

    service.applyProviderResult(
        result,
        new RazorpayXGateway.FundAccountValidationResult(
            "fav_1", "failed", null, null, 100L, "late failure"));

    // A terminal row is never revisited, so a redelivered webhook cannot undo a
    // verification — or, worse, re-verify one a human had rejected.
    assertEquals(PayoutAccountValidationEntity.VERIFIED, result.getStatus());
    assertEquals("VERIFIED", account.getVerificationStatus());
  }

  @Test
  void nameMatchingToleratesHowBanksActuallyFormatNames() {
    // Genuine accounts: honorifics, initials, reordering, extra middle names.
    assertTrue(PayoutAccountValidationService.nameMatchScore("Ramesh Kumar", "RAMESH KUMAR") >= 80);
    assertTrue(PayoutAccountValidationService.nameMatchScore("Ramesh Kumar", "Mr Ramesh Kumar") >= 80);
    assertTrue(
        PayoutAccountValidationService.nameMatchScore("Ramesh Kumar", "Ramesh Babu Kumar") >= 80);
    assertTrue(PayoutAccountValidationService.nameMatchScore("Ramesh Kumar", "Kumar Ramesh") >= 80);

    // Different people.
    assertTrue(PayoutAccountValidationService.nameMatchScore("Ramesh Kumar", "Suresh Reddy") < 80);
    assertTrue(PayoutAccountValidationService.nameMatchScore("Ramesh Kumar", "") < 80);
    assertTrue(PayoutAccountValidationService.nameMatchScore("Ramesh Kumar", null) < 80);
  }
}
