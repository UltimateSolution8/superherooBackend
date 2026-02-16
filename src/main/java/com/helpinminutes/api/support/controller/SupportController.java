package com.helpinminutes.api.support.controller;

import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.support.dto.AddMessageRequest;
import com.helpinminutes.api.support.dto.CreateTicketRequest;
import com.helpinminutes.api.support.dto.TicketDetailResponse;
import com.helpinminutes.api.support.dto.TicketMessageResponse;
import com.helpinminutes.api.support.dto.TicketResponse;
import com.helpinminutes.api.support.service.SupportService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/support")
public class SupportController {
  private final SupportService support;

  public SupportController(SupportService support) {
    this.support = support;
  }

  @PostMapping("/tickets")
  public TicketDetailResponse create(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody CreateTicketRequest req) {
    return support.createTicket(principal.userId(), principal.role(), req);
  }

  @GetMapping("/tickets")
  public List<TicketResponse> myTickets(@AuthenticationPrincipal UserPrincipal principal) {
    return support.listMyTickets(principal.userId());
  }

  @GetMapping("/tickets/{ticketId}")
  public TicketDetailResponse myTicket(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID ticketId) {
    return support.getMyTicket(principal.userId(), ticketId);
  }

  @PostMapping("/tickets/{ticketId}/messages")
  public TicketMessageResponse addMessage(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID ticketId,
      @Valid @RequestBody AddMessageRequest req) {
    return support.addMyMessage(principal.userId(), ticketId, req);
  }
}

