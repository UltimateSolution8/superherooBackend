package com.helpinminutes.api.helpers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.helpers.dto.HelperPayoutAccountRequest;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.helpers.repo.HelperPayoutAccountRepository;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

public class HelperServiceTest {
  @Test
  public void savePayoutAccountStoresOnlyMaskedBankDetails() {
    UUID helperId = UUID.randomUUID();
    HelperProfileRepository profiles = mock(HelperProfileRepository.class);
    HelperPayoutAccountRepository payoutAccounts = mock(HelperPayoutAccountRepository.class);
    HelperService service = new HelperService(
        profiles,
        mock(HelperPresenceService.class),
        mock(SupabaseStorageService.class),
        mock(UserRepository.class),
        mock(TaskRepository.class),
        mock(MatchingService.class),
        Runnable::run,
        payoutAccounts);
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(helperId);

    when(profiles.findById(helperId)).thenReturn(Optional.of(profile));
    when(payoutAccounts.findByHelperIdAndProvider(helperId, HelperPayoutAccountEntity.DEFAULT_PROVIDER)).thenReturn(Optional.empty());
    when(payoutAccounts.save(any(HelperPayoutAccountEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var response = service.savePayoutAccount(helperId, new HelperPayoutAccountRequest(
        "Test Partner",
        "HDFC Bank",
        "1234",
        "HDFC0000001",
        "tes***@upi"));

    assertEquals("Test Partner", response.accountHolderName());
    assertEquals("HDFC Bank", response.bankName());
    assertEquals("1234", response.bankAccountLast4());
    assertEquals("HDFC0000001", response.ifscCode());
    assertEquals("tes***@upi", response.upiIdMasked());
  }
}
