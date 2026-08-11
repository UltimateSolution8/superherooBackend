package com.helpinminutes.api.helpers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.helpers.dto.HelperPayoutAccountRequest;
import com.helpinminutes.api.helpers.dto.PayoutAccountUpdateRequest;
import com.helpinminutes.api.helpers.dto.IfscLookupResponse;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.model.PayoutBeneficiaryLinkEntity;
import com.helpinminutes.api.helpers.model.PayoutAccountChangeEventEntity;
import com.helpinminutes.api.helpers.repo.HelperPayoutAccountRepository;
import com.helpinminutes.api.helpers.repo.PayoutAccountChangeEventRepository;
import com.helpinminutes.api.helpers.repo.PayoutBeneficiaryLinkRepository;
import com.helpinminutes.api.helpers.security.BankAccountCipher;
import com.helpinminutes.api.reports.service.AuditLogService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class PayoutAccountServiceTest {
  @Test
  void legacyLastFourAccountRemainsReadableButCannotReceivePayouts() {
    HelperPayoutAccountEntity legacy = new HelperPayoutAccountEntity();
    legacy.setId(UUID.randomUUID());
    legacy.setAccountHolderName("Legacy Partner");
    legacy.setBankName("Legacy Bank");
    legacy.setBankAccountLast4("9876");
    legacy.setIfscCode("HDFC0000001");
    legacy.setVerificationStatus("DETAILS_INCOMPLETE");
    legacy.setStatus("ACTIVE");

    var response = PayoutAccountService.toResponse(legacy);

    assertEquals("••••9876", response.maskedAccountNumber());
    assertEquals("DETAILS_INCOMPLETE", response.accountVerificationStatus());
    assertFalse(response.payoutEligible());
  }

  @Test
  void replacementSupersedesOldAccountAndNeverReturnsPlaintext() throws Exception {
    UUID userId = UUID.randomUUID();
    HelperPayoutAccountRepository accounts = mock(HelperPayoutAccountRepository.class);
    PayoutBeneficiaryLinkRepository links = mock(PayoutBeneficiaryLinkRepository.class);
    UserRepository users = mock(UserRepository.class);
    IfscLookupService ifsc = mock(IfscLookupService.class);
    BankAccountCipher cipher = mock(BankAccountCipher.class);
    AuditLogService audit = mock(AuditLogService.class);
    PayoutAccountChangeEventRepository changeEvents = mock(PayoutAccountChangeEventRepository.class);
    BankChangeChallengeService challenges = mock(BankChangeChallengeService.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    PayoutAccountService service = new PayoutAccountService(
        accounts, links, users, ifsc, cipher, audit, changeEvents, challenges, events);

    HelperPayoutAccountEntity old = new HelperPayoutAccountEntity();
    old.setId(UUID.randomUUID());
    old.setHelperId(userId);
    old.setCurrent(true);
    old.setStatus("ACTIVE");
    PayoutBeneficiaryLinkEntity link = new PayoutBeneficiaryLinkEntity();
    link.setPayoutAccountId(old.getId());
    link.setProvider("RAZORPAY_ROUTE");
    link.setStatus("ACTIVE");
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setRole(UserRole.HELPER);

    when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
    when(ifsc.lookup("HDFC0000001")).thenReturn(new IfscLookupResponse(
        "HDFC0000001", "HDFC Bank", "TEST", "HYDERABAD", "HYDERABAD", "TELANGANA"));
    when(accounts.findByHelperIdAndProviderAndCurrentTrue(userId, HelperPayoutAccountEntity.DEFAULT_PROVIDER))
        .thenReturn(Optional.of(old));
    when(links.findByPayoutAccountId(old.getId())).thenReturn(List.of(link));
    when(cipher.encrypt(any(UUID.class), org.mockito.ArgumentMatchers.eq("12345678901234")))
        .thenReturn(new BankAccountCipher.EncryptedValue("v1", "v1:ciphertext"));
    when(accounts.save(any(HelperPayoutAccountEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var response = service.replace(userId, UserRole.HELPER,
        new PayoutAccountUpdateRequest("Test Partner", "12345678901234", "HDFC0000001", "change-token"),
        "203.0.113.10");

    assertFalse(old.isCurrent());
    assertEquals("SUPERSEDED", old.getStatus());
    assertEquals("DISABLED", link.getStatus());
    assertEquals("1234", response.bankAccountLast4());
    assertEquals("••••1234", response.maskedAccountNumber());
    assertEquals("HDFC Bank", response.bankName());
    assertEquals("NOT_STARTED", response.accountVerificationStatus());
    assertFalse(response.payoutEligible());
    ArgumentCaptor<HelperPayoutAccountEntity> accountCaptor = ArgumentCaptor.forClass(HelperPayoutAccountEntity.class);
    verify(accounts, times(2)).save(accountCaptor.capture());
    HelperPayoutAccountEntity created = accountCaptor.getAllValues().get(1);
    assertEquals("v1:ciphertext", created.getAccountNumberCiphertext());
    assertEquals(old.getId(), created.getSupersedesAccountId());
    assertEquals("PROFILE", created.getChangeSource());
    assertNotEquals("12345678901234", created.getAccountNumberCiphertext());
    String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
    assertFalse(serialized.contains("12345678901234"));
    assertFalse(serialized.contains("bankAccountNumber"));
    assertTrue(serialized.contains("••••1234"));
    verify(accounts).flush();
    verify(challenges).consume(userId, UserRole.HELPER, "change-token");
    verify(changeEvents).save(any());
    ArgumentCaptor<PayoutAccountChangeEventEntity> changeCaptor = ArgumentCaptor.forClass(PayoutAccountChangeEventEntity.class);
    verify(changeEvents).save(changeCaptor.capture());
    assertEquals(old.getId(), changeCaptor.getValue().getPreviousAccountId());
    assertEquals(created.getId(), changeCaptor.getValue().getNewAccountId());
    assertEquals("203.0.113.10", changeCaptor.getValue().getIpAddress());
    assertEquals("PROFILE", changeCaptor.getValue().getChangeSource());
    verify(events).publishEvent(any(BankAccountChangedEvent.class));
  }
}
