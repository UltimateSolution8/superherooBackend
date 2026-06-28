package com.helpinminutes.api.users.controller;

import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.WaitlistEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.users.repo.WaitlistRepository;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {
  private final WaitlistRepository waitlistRepo;
  private final UserRepository userRepo;

  public WaitlistController(WaitlistRepository waitlistRepo, UserRepository userRepo) {
    this.waitlistRepo = waitlistRepo;
    this.userRepo = userRepo;
  }

  @PostMapping
  public WaitlistResponse joinWaitlist(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody JoinWaitlistRequest req) {

    UserEntity user = userRepo.findById(principal.userId()).orElseThrow();

    String name = req.name() != null && !req.name().isBlank() ? req.name().trim() : user.getDisplayName();
    String email = req.email() != null && !req.email().isBlank() ? req.email().trim() : user.getEmail();
    String phone = req.phone() != null && !req.phone().isBlank() ? req.phone().trim() : user.getPhone();

    if (name == null || name.isBlank()) name = "User";
    if (email == null || email.isBlank()) email = "no-email@superherooo.in";
    if (phone == null || phone.isBlank()) phone = "0000000000";

    WaitlistEntity entity = new WaitlistEntity();
    entity.setName(name);
    entity.setEmail(email);
    entity.setPhone(phone);
    entity.setCity(req.city() != null ? req.city().trim() : null);
    entity.setRole(principal.role().name());
    entity.setLat(req.lat());
    entity.setLng(req.lng());

    waitlistRepo.save(entity);

    return new WaitlistResponse(entity.getId(), true, "Successfully joined waitlist");
  }

  public record JoinWaitlistRequest(
      String name,
      String email,
      String phone,
      String city,
      Double lat,
      Double lng
  ) {}

  public record WaitlistResponse(
      java.util.UUID id,
      boolean success,
      String message
  ) {}
}
