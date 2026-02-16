package com.helpinminutes.api.helpers.controller;

import com.helpinminutes.api.helpers.dto.SetOnlineRequest;
import com.helpinminutes.api.helpers.dto.HelperProfileResponse;
import com.helpinminutes.api.helpers.service.HelperService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/helper")
public class HelperController {
  private final HelperService helpers;

  public HelperController(HelperService helpers) {
    this.helpers = helpers;
  }

  @PutMapping("/online")
  public void setOnline(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody SetOnlineRequest req) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    if (Boolean.TRUE.equals(req.online())) {
      if (req.lat() == null || req.lng() == null) {
        throw new com.helpinminutes.api.errors.BadRequestException("lat/lng required to go online");
      }
      helpers.setOnline(principal.userId(), req.lat(), req.lng());
    } else {
      helpers.setOffline(principal.userId());
    }
  }

  @GetMapping("/profile")
  public HelperProfileResponse profile(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    return helpers.getProfile(principal.userId());
  }

  @PostMapping(value = "/kyc/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public HelperProfileResponse submitKyc(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam String fullName,
      @RequestParam String idNumber,
      @RequestParam("idFront") MultipartFile idFront,
      @RequestParam("idBack") MultipartFile idBack,
      @RequestParam("selfie") MultipartFile selfie) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    return helpers.submitKyc(principal.userId(), fullName, idNumber, idFront, idBack, selfie);
  }
}
