package com.helpinminutes.api.notifications.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.users.model.UserRole;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminNotificationTargetingTest {
  @Test
  void allIncludesEveryMobileAppRole() {
    assertEquals(
        Set.of(UserRole.BUYER, UserRole.HELPER, UserRole.MEDIATOR),
        AdminNotificationTargeting.rolesFor("ALL"));
  }

  @Test
  void aliasesResolveToTheCorrectAudience() {
    assertEquals(Set.of(UserRole.BUYER), AdminNotificationTargeting.rolesFor("citizen"));
    assertEquals(Set.of(UserRole.HELPER), AdminNotificationTargeting.rolesFor("partner"));
    assertEquals(Set.of(UserRole.MEDIATOR), AdminNotificationTargeting.rolesFor("mediator"));
  }

  @Test
  void unsupportedAudienceIsRejected() {
    assertThrows(BadRequestException.class, () -> AdminNotificationTargeting.rolesFor("ADMIN"));
  }
}
