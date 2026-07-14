package com.helpinminutes.api.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.helpinminutes.api.admin.dto.AdminCreateUserRequest;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

public class AdminServiceTest {
  private HelperProfileRepository helperProfiles;
  private UserRepository users;
  private PasswordEncoder passwordEncoder;
  private TaskRepository tasks;
  private NotificationQueueService notificationQueue;
  private AdminService service;

  @BeforeEach
  public void setUp() {
    helperProfiles = mock(HelperProfileRepository.class);
    users = mock(UserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    tasks = mock(TaskRepository.class);
    notificationQueue = mock(NotificationQueueService.class);
    service = new AdminService(helperProfiles, users, passwordEncoder, tasks, notificationQueue);
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
}
