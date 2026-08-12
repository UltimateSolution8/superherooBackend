package com.helpinminutes.api.helpers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.helpers.dto.HelperBankDetailsResponse;
import com.helpinminutes.api.helpers.dto.HelperPayoutAccountRequest;
import com.helpinminutes.api.helpers.dto.PayoutAccountUpdateRequest;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

public class HelperServiceTest {
  @Test
  public void savePayoutAccountStoresOnlyMaskedBankDetails() {
    UUID helperId = UUID.randomUUID();
    HelperProfileRepository profiles = mock(HelperProfileRepository.class);
    PayoutAccountService payoutAccounts = mock(PayoutAccountService.class);
    HelperService service = new HelperService(
        profiles,
        mock(HelperPresenceService.class),
        mock(SupabaseStorageService.class),
        mock(UserRepository.class),
        mock(TaskRepository.class),
        mock(NotificationQueueService.class),
        Runnable::run,
        payoutAccounts,
        com.helpinminutes.api.config.TestAppProperties.defaults());
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(helperId);

    when(profiles.findById(helperId)).thenReturn(Optional.of(profile));
    PayoutAccountUpdateRequest request = new PayoutAccountUpdateRequest(
        "Test Partner", "12345678901234", "HDFC0000001", "change-token");
    when(payoutAccounts.replace(helperId, com.helpinminutes.api.users.model.UserRole.HELPER, request, "203.0.113.10"))
        .thenReturn(new HelperBankDetailsResponse(
            UUID.randomUUID(), "Test Partner", "HDFC Bank", "1234", "••••1234",
            "HDFC0000001", Instant.now(), "NOT_STARTED", "PENDING_ACCOUNT_VERIFICATION", false, Instant.now()));

    var response = service.savePayoutAccount(helperId, request, "203.0.113.10");

    assertEquals("Test Partner", response.accountHolderName());
    assertEquals("HDFC Bank", response.bankName());
    assertEquals("1234", response.bankAccountLast4());
    assertEquals("HDFC0000001", response.ifscCode());
    verify(payoutAccounts).replace(helperId, com.helpinminutes.api.users.model.UserRole.HELPER, request, "203.0.113.10");
  }

  @Test
  public void submitKycValidatesBankDetailsBeforeUploadingIdentityDocuments() {
    UUID helperId = UUID.randomUUID();
    HelperProfileRepository profiles = mock(HelperProfileRepository.class);
    SupabaseStorageService storage = mock(SupabaseStorageService.class);
    PayoutAccountService payoutAccounts = mock(PayoutAccountService.class);
    HelperService service = new HelperService(
        profiles,
        mock(HelperPresenceService.class),
        storage,
        mock(UserRepository.class),
        mock(TaskRepository.class),
        mock(NotificationQueueService.class),
        Runnable::run,
        payoutAccounts,
        com.helpinminutes.api.config.TestAppProperties.defaults());
    MultipartFile front = mock(MultipartFile.class);
    MultipartFile back = mock(MultipartFile.class);
    MultipartFile selfie = mock(MultipartFile.class);
    when(back.isEmpty()).thenReturn(false);
    when(payoutAccounts.prepare(any(HelperPayoutAccountRequest.class)))
        .thenThrow(new BadRequestException("IFSC code was not found"));

    assertThrows(BadRequestException.class, () -> service.submitKyc(
        helperId, "Test Partner", "AADHAAR", "123456789012",
        front, back, selfie, "Test Partner", "12345678901234", "XXXX0000000"));

    verify(storage, never()).uploadHelperKycDoc(any(), any(), any());
    verify(profiles, never()).save(any());
  }

  @Test
  public void kycResubmissionPreservesExistingSecureBankAccount() {
    UUID helperId = UUID.randomUUID();
    HelperProfileRepository profiles = mock(HelperProfileRepository.class);
    SupabaseStorageService storage = mock(SupabaseStorageService.class);
    PayoutAccountService payoutAccounts = mock(PayoutAccountService.class);
    HelperService service = new HelperService(
        profiles, mock(HelperPresenceService.class), storage, mock(UserRepository.class),
        mock(TaskRepository.class), mock(NotificationQueueService.class), Runnable::run, payoutAccounts,
        com.helpinminutes.api.config.TestAppProperties.defaults());
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(helperId);
    when(profiles.findById(helperId)).thenReturn(Optional.of(profile));
    when(payoutAccounts.hasSecureCurrent(helperId)).thenReturn(true);
    MultipartFile front = mock(MultipartFile.class);
    MultipartFile back = mock(MultipartFile.class);
    MultipartFile selfie = mock(MultipartFile.class);
    when(back.isEmpty()).thenReturn(false);
    when(storage.uploadHelperKycDoc(helperId, "id-front", front)).thenReturn("front");
    when(storage.uploadHelperKycDoc(helperId, "id-back", back)).thenReturn("back");
    when(storage.uploadHelperKycDoc(helperId, "selfie", selfie)).thenReturn("selfie");

    service.submitKyc(helperId, "Test Partner", "AADHAAR", "123456789012",
        front, back, selfie, null, null, null);

    verify(payoutAccounts, never()).prepare(any());
    verify(payoutAccounts, never()).replacePrepared(any(), any(), any());
    verify(profiles).save(profile);
  }
}
