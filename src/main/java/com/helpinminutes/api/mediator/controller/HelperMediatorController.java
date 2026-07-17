package com.helpinminutes.api.mediator.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.mediator.dto.MediatorDtos.LinkMediatorRequest;
import com.helpinminutes.api.mediator.dto.MediatorDtos.LinkedMediatorResponse;
import com.helpinminutes.api.mediator.service.MediatorService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/helper/mediators")
public class HelperMediatorController {
  private final MediatorService mediatorService;

  public HelperMediatorController(MediatorService mediatorService) {
    this.mediatorService = mediatorService;
  }

  private void checkHelper(UserPrincipal principal) {
    if (principal.role() != UserRole.HELPER) {
      throw new ForbiddenException("Only helpers can manage mediator links");
    }
  }

  @GetMapping
  public List<LinkedMediatorResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
    checkHelper(principal);
    return mediatorService.listLinkedMediators(principal.userId());
  }

  @PostMapping
  public LinkedMediatorResponse link(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody LinkMediatorRequest req) {
    checkHelper(principal);
    return mediatorService.linkMediatorForHelper(principal.userId(), req);
  }

  @DeleteMapping("/{mediatorId}")
  public void unlink(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID mediatorId) {
    checkHelper(principal);
    mediatorService.unlinkMediatorForHelper(principal.userId(), mediatorId);
  }
}
