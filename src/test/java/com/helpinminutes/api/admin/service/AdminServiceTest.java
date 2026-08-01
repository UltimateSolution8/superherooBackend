package com.helpinminutes.api.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.helpinminutes.api.admin.dto.AdminCreateUserRequest;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperPayoutAccountRepository;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

public class AdminServiceTest {
  private HelperProfileRepository helperProfiles;
  private UserRepository users;
  private PasswordEncoder passwordEncoder;
  private TaskRepository tasks;
  private NotificationQueueService notificationQueue;
  private HelperPayoutAccountRepository payoutAccounts;
  private AdminService service;

  @BeforeEach
  public void setUp() {
    helperProfiles = mock(HelperProfileRepository.class);
    users = mock(UserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    tasks = mock(TaskRepository.class);
    notificationQueue = mock(NotificationQueueService.class);
    payoutAccounts = mock(HelperPayoutAccountRepository.class);
    service = new AdminService(helperProfiles, users, passwordEncoder, tasks, notificationQueue, payoutAccounts);
  }

  @Test
  public void createMediatorRequiresPhoneForOtpLogin() {
    AdminCreateUserRequest req = new AdminCreateUserRequest(null, "mediator@helpinminutes.app", "Mediator", null, "ACTIVE");
    assertThrows(BadRequestException.class, () -> service.createUser(UserRole.MEDIATOR, req));
    verify(users, never()).save(any());
  }

  @Test
  public void createMediatorWithPhoneSavesMediatorRole() {
    AdminCreateUserRequest req = new AdminCreateUserRequest("9876543210", null, "Mediator", null, "ACTIVE");
    when(users.findByPhoneAndRole("9876543210", UserRole.MEDIATOR)).thenReturn(Optional.empty());
    when(users.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var response = service.createUser(UserRole.MEDIATOR, req);

    assertEquals(UserRole.MEDIATOR, response.role());
    assertEquals(UserStatus.ACTIVE, response.status());
    assertEquals("9876543210", response.phone());
    verify(users).save(argThat(u -> u.getRole() == UserRole.MEDIATOR && "9876543210".equals(u.getPhone())));
  }

  @Test
  public void pendingHelpersIncludesPayoutSummaryWhenPresent() {
    UUID helperId = UUID.randomUUID();
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(helperId);
    profile.setKycStatus(HelperKycStatus.PENDING);
    profile.setKycFullName("Test Partner");
    profile.setKycIdNumber("ABCDE1234F");
    profile.prePersist();

    UserEntity user = new UserEntity();
    user.setId(helperId);
    user.setRole(UserRole.HELPER);
    user.setPhone("9876543210");
    user.setEmail("partner@example.com");
    user.setDisplayName("Partner Display");

    HelperPayoutAccountEntity payout = new HelperPayoutAccountEntity();
    payout.setHelperId(helperId);
    payout.setAccountHolderName("Test Partner");
    payout.setBankName("HDFC Bank");
    payout.setBankAccountLast4("1234");
    payout.setIfscCode("HDFC0000001");
    payout.setUpiIdMasked("tes***@upi");

    when(helperProfiles.findAllByKycStatusOrderByCreatedAtAsc(HelperKycStatus.PENDING)).thenReturn(List.of(profile));
    when(users.findAllById(List.of(helperId))).thenReturn(List.of(user));
    when(payoutAccounts.findByHelperIdInAndProvider(List.of(helperId), HelperPayoutAccountEntity.DEFAULT_PROVIDER)).thenReturn(List.of(payout));

    var pending = service.listPendingHelpers();

    assertEquals(1, pending.size());
    assertEquals("Partner Display", pending.get(0).displayName());
    assertEquals("partner@example.com", pending.get(0).email());
    assertEquals("HDFC Bank", pending.get(0).payoutBankName());
    assertEquals("1234", pending.get(0).payoutBankAccountLast4());
    assertEquals("tes***@upi", pending.get(0).payoutUpiIdMasked());
  }
}
