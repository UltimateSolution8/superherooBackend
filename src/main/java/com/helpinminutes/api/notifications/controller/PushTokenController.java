package com.helpinminutes.api.notifications.controller;

import com.helpinminutes.api.notifications.dto.RegisterPushTokenRequest;
import com.helpinminutes.api.notifications.service.PushTokenService;
import com.helpinminutes.api.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class PushTokenController {
  private final PushTokenService tokens;

  public PushTokenController(PushTokenService tokens) {
    this.tokens = tokens;
  }

  @PostMapping("/token")
  public void register(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody RegisterPushTokenRequest req) {
    tokens.register(principal.userId(), req.token().trim(), req.platform().trim());
  }
}
