package com.helpinminutes.api.users.controller;

import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class MeController {
  private final UserRepository users;

  public MeController(UserRepository users) {
    this.users = users;
  }

  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal UserPrincipal principal) {
    UUID userId = principal.userId();
    UserEntity u = users.findById(userId).orElseThrow();
    return new MeResponse(u.getId(), u.getRole().name(), u.getPhone(), u.getEmail(), u.getDisplayName(), u.getDemoBalancePaise());
  }

  @PutMapping("/me")
  public MeResponse updateMe(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody UpdateMeRequest req) {
    UUID userId = principal.userId();
    UserEntity u = users.findById(userId).orElseThrow();
    if (req.displayName() != null && !req.displayName().isBlank()) {
      u.setDisplayName(req.displayName().trim());
    }
    users.save(u);
    return new MeResponse(u.getId(), u.getRole().name(), u.getPhone(), u.getEmail(), u.getDisplayName(), u.getDemoBalancePaise());
  }

  public record UpdateMeRequest(@NotBlank String displayName) {}

  public record MeResponse(UUID id, String role, String phone, String email, String displayName, Long demoBalancePaise) {}
}
