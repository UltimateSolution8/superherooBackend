package com.helpinminutes.api.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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

  /**
   * Real BankAccountCipher instance with a test key using the public constructor.
   * This avoids the Mockito/Java 25 incompatibility that prevents mocking it.
   */
  private static BankAccountCipher realCipher() {
    return new BankAccountCipher("k1:YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=", "k1");
  }

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

    // Encrypt a real account number so BankAccountCipher.decrypt() can read it.
    BankAccountCipher cipher = realCipher();
    BankAccountCipher.EncryptedValue enc = cipher.encrypt(accountId, "123456789012");
    account.setAccountNumberKeyId(enc.keyId());
    account.setAccountNumberCiphertext(enc.ciphertext());

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

    razorpayx = mock(RazorpayXGateway.class);
    when(razorpayx.isConfigured()).thenReturn(true);

    service = new PayoutAccountValidationService(validations, accounts, users, razorpayx, cipher);
  }

  /** Simulates composite validation returning a given status and registered name. */
  private void compositeRespondsWith(String status, String registeredName) {
    when(razorpayx.createCompositeValidation(any()))
        .thenReturn(
            new RazorpayXGateway.FundAccountValidationResult(
                "fav_1", status, registeredName, "UTR123", 100L, null));
  }

  /** Simulates composite validation returning a status with a provider name match score. */
  private void compositeRespondsWith(String status, String registeredName, Integer providerScore) {
    when(razorpayx.createCompositeValidation(any()))
        .thenReturn(
            new RazorpayXGateway.FundAccountValidationResult(
                "fav_1", status, registeredName, "UTR123", 100L, null,
                providerScore, "bank_account", null, null));
  }

  // ---------------------------------------------------------------------------
  // Happy path — name matches
  // ---------------------------------------------------------------------------

  @Test
  void aMatchingNameVerifiesTheAccount() {
    compositeRespondsWith("completed", "RAMESH KUMAR");

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.VERIFIED, result.getStatus());
    assertEquals("VERIFIED", account.getVerificationStatus());
    assertEquals("ACTIVE", account.getStatus());
    assertEquals("bank_account", result.getAccountType());
    assertEquals("optimized", result.getValidationType());
  }

  @Test
  void hybridFavUsesCompositeApiNotOldThreeStepFlow() {
    compositeRespondsWith("completed", "RAMESH KUMAR");
    service.startValidation(helperId);

    // Composite API must be used; the old fund account endpoint must NOT be called.
    verify(razorpayx).createCompositeValidation(any());
    verify(razorpayx, never()).createFundAccountValidation(any(), any());
    verify(razorpayx, never()).ensureContact(any(), any(), any(), any());
    verify(razorpayx, never()).ensureFundAccount(any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Provider name match score (Razorpay's own score) is preferred over local
  // ---------------------------------------------------------------------------

  @Test
  void providerNameMatchScoreIsPreferredOverLocalComputation() {
    compositeRespondsWith("completed", "RAMESH KUMAR", 92);

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.VERIFIED, result.getStatus());
    assertEquals(92, result.getNameMatchScore()); // used for threshold decision
    assertEquals(92, result.getProviderNameMatchScore()); // stored separately
  }

  @Test
  void lowProviderScoreSendsToManualReviewEvenIfLocalScoreWouldPass() {
    // Provider returns score 60 — below threshold. Account must NOT auto-verify.
    compositeRespondsWith("completed", "RAMESH KUMAR", 60);

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.MANUAL_REVIEW, result.getStatus());
    assertEquals("MANUAL_REVIEW", account.getVerificationStatus());
    assertEquals(60, result.getNameMatchScore());
    assertEquals(60, result.getProviderNameMatchScore());
  }

  @Test
  void whenProviderScoreIsNullLocalComputationIsUsed() {
    // No provider score — falls back to local word-set comparison.
    compositeRespondsWith("completed", "RAMESH KUMAR", null);

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.VERIFIED, result.getStatus());
    assertNull(result.getProviderNameMatchScore()); // null because provider didn't return one
    assertNotNull(result.getNameMatchScore()); // computed locally
    assertTrue(result.getNameMatchScore() >= 80);
  }

  // ---------------------------------------------------------------------------
  // Mismatched name goes to manual review
  // ---------------------------------------------------------------------------

  @Test
  void aMismatchedNameIsHeldForReviewAndNeverAutoVerified() {
    // Drop succeeded against an account held under someone else's name.
    compositeRespondsWith("completed", "Suresh Reddy");

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.MANUAL_REVIEW, result.getStatus());
    assertEquals("MANUAL_REVIEW", account.getVerificationStatus());
    assertTrue(!"ACTIVE".equals(account.getStatus()));
  }

  // ---------------------------------------------------------------------------
  // Failed verification
  // ---------------------------------------------------------------------------

  @Test
  void aFailedValidationMarksTheAccountFailed() {
    when(razorpayx.createCompositeValidation(any()))
        .thenReturn(
            new RazorpayXGateway.FundAccountValidationResult(
                "fav_2", "failed", null, null, 100L, "Account number does not exist",
                0, "bank_account", "beneficiary_bank", "invalid_account_number"));

    PayoutAccountValidationEntity result = service.startValidation(helperId);

    assertEquals(PayoutAccountValidationEntity.FAILED, result.getStatus());
    assertEquals("FAILED", account.getVerificationStatus());
    assertEquals("beneficiary_bank", result.getFailureSource());
    assertEquals("invalid_account_number", result.getFailureReasonCode());
  }

  // ---------------------------------------------------------------------------
  // Idempotency / rate limiting
  // ---------------------------------------------------------------------------

  @Test
  void returnsTheInFlightValidationRatherThanBuyingASecond() {
    PayoutAccountValidationEntity existing = new PayoutAccountValidationEntity();
    existing.setId(UUID.randomUUID());
    existing.setPayoutAccountId(accountId);
    existing.setHelperId(helperId);
    when(validations.findInFlight(accountId)).thenReturn(Optional.of(existing));

    assertEquals(existing, service.startValidation(helperId));
    verify(razorpayx, never()).createCompositeValidation(any());
  }

  @Test
  void capsAttemptsPerAccountPerDay() {
    when(validations.countSince(any(), any()))
        .thenReturn((long) PayoutAccountValidationService.MAX_ATTEMPTS_PER_DAY);

    assertThrows(BadRequestException.class, () -> service.startValidation(helperId));
    verify(razorpayx, never()).createCompositeValidation(any());
  }

  @Test
  void refusesToRevalidateAnAlreadyVerifiedAccount() {
    account.setVerificationStatus("VERIFIED");
    assertThrows(BadRequestException.class, () -> service.startValidation(helperId));
  }

  // ---------------------------------------------------------------------------
  // Webhook idempotency — redelivered result is a no-op on terminal row
  // ---------------------------------------------------------------------------

  @Test
  void aRedeliveredResultIsANoOpOnATerminalRow() {
    compositeRespondsWith("completed", "RAMESH KUMAR");
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

  // ---------------------------------------------------------------------------
  // UPI VPA validation
  // ---------------------------------------------------------------------------

  @Test
  void upiValidationWithMatchingNameVerifiesAccount() {
    when(razorpayx.createCompositeValidation(any()))
        .thenReturn(
            new RazorpayXGateway.FundAccountValidationResult(
                "fav_vpa_1", "completed", "RAMESH KUMAR", null, 0L, null,
                95, "vpa", null, null));

    PayoutAccountValidationEntity result =
        service.startUpiValidation(helperId, "ramesh@oksbi", "Ramesh Kumar");

    assertEquals(PayoutAccountValidationEntity.VERIFIED, result.getStatus());
    assertEquals("VERIFIED", account.getVerificationStatus());
    assertEquals("vpa", result.getAccountType());
    assertEquals("optimized", result.getValidationType());
    assertEquals(95, result.getProviderNameMatchScore());
  }

  @Test
  void upiValidationWithMismatchedNameSendsToReview() {
    when(razorpayx.createCompositeValidation(any()))
        .thenReturn(
            new RazorpayXGateway.FundAccountValidationResult(
                "fav_vpa_2", "completed", "Different Person", null, 0L, null,
                20, "vpa", null, null));

    PayoutAccountValidationEntity result =
        service.startUpiValidation(helperId, "someone@oksbi", "Ramesh Kumar");

    assertEquals(PayoutAccountValidationEntity.MANUAL_REVIEW, result.getStatus());
    assertEquals("MANUAL_REVIEW", account.getVerificationStatus());
  }
}
