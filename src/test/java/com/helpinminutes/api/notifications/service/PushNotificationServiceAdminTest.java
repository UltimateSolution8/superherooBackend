package com.helpinminutes.api.notifications.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.batches.repo.BookingBatchItemRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class PushNotificationServiceAdminTest {
  private PushTokenService tokens;
  private UserRepository users;
  private PushNotificationService service;

  @BeforeEach
  void setUp() {
    tokens = mock(PushTokenService.class);
    users = mock(UserRepository.class);
    service = new PushNotificationService(
        tokens,
        users,
        mock(BookingBatchItemRepository.class),
        mock(StringRedisTemplate.class),
        new ObjectMapper(),
        Runnable::run,
        "",
        "",
        "missing-firebase-service-account.json",
        com.helpinminutes.api.config.TestAppProperties.defaults());
    when(tokens.getTokensForUsers(anyList())).thenReturn(List.of());
  }

  @Test
  void allAudienceIncludesActiveMediatorAndExcludesInactiveUsers() {
    UserEntity buyer = user(UserRole.BUYER, UserStatus.ACTIVE);
    UserEntity helper = user(UserRole.HELPER, UserStatus.BLOCKED);
    UserEntity mediator = user(UserRole.MEDIATOR, UserStatus.ACTIVE);
    when(users.findAllByRole(UserRole.BUYER)).thenReturn(List.of(buyer));
    when(users.findAllByRole(UserRole.HELPER)).thenReturn(List.of(helper));
    when(users.findAllByRole(UserRole.MEDIATOR)).thenReturn(List.of(mediator));

    var result = service.sendAdminNotification("ALL", null, "Update", "Message");

    assertEquals(2, result.targetedUsers());
    assertEquals(0, result.deviceTokens());
    assertFalse(result.queued());
  }

  @Test
  void specificAudienceCannotLeakAcrossRoles() {
    UserEntity buyer = user(UserRole.BUYER, UserStatus.ACTIVE);
    UserEntity mediator = user(UserRole.MEDIATOR, UserStatus.ACTIVE);
    when(users.findAllById(any())).thenReturn(List.of(buyer, mediator));

    var result = service.sendAdminNotification(
        "MEDIATOR", List.of(buyer.getId(), mediator.getId()), "Update", "Message");

    assertEquals(1, result.targetedUsers());
  }

  private static UserEntity user(UserRole role, UserStatus status) {
    UserEntity user = new UserEntity();
    user.setId(UUID.randomUUID());
    user.setRole(role);
    user.setStatus(status);
    return user;
  }
}
