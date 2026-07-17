package com.helpinminutes.api.notifications.service;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.users.model.UserRole;
import java.util.Set;

public final class AdminNotificationTargeting {
  private AdminNotificationTargeting() {}

  public static Set<UserRole> rolesFor(String rawRole) {
    String role = rawRole == null ? "" : rawRole.trim().toUpperCase();
    return switch (role) {
      case "ALL" -> Set.of(UserRole.BUYER, UserRole.HELPER, UserRole.MEDIATOR);
      case "CITIZEN", "BUYER" -> Set.of(UserRole.BUYER);
      case "PARTNER", "HELPER" -> Set.of(UserRole.HELPER);
      case "MEDIATOR" -> Set.of(UserRole.MEDIATOR);
      default -> throw new BadRequestException("Unsupported notification audience");
    };
  }
}
