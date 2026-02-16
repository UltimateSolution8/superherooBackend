package com.helpinminutes.api.auth.dto;

import com.helpinminutes.api.users.model.UserRole;
import java.util.UUID;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    User user
) {
  public record User(
      UUID id,
      UserRole role,
      String phone,
      String displayName
  ) {}
}
