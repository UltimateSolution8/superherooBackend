package com.helpinminutes.api.support.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.support.dto.AddMessageRequest;
import com.helpinminutes.api.support.dto.AdminAssignTicketRequest;
import com.helpinminutes.api.support.dto.AdminTicketDetailResponse;
import com.helpinminutes.api.support.dto.AdminTicketResponse;
import com.helpinminutes.api.support.dto.AdminUpdateTicketStatusRequest;
import com.helpinminutes.api.support.dto.AiDraftResponse;
import com.helpinminutes.api.support.dto.TicketMessageResponse;
import com.helpinminutes.api.support.model.SupportTicketStatus;
import com.helpinminutes.api.support.service.SupportService;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support")
public class AdminSupportController {
  private final SupportService support;

  public AdminSupportController(SupportService support) {
    this.support = support;
  }

  private static void requireAdmin(UserPrincipal principal) {
    if (principal.role() != UserRole.ADMIN) {
      throw new ForbiddenException("Admin only");
    }
  }

  @GetMapping("/tickets")
  public List<AdminTicketResponse> tickets(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false) SupportTicketStatus status) {
    requireAdmin(principal);
    return support.listAdminTickets(status);
  }

  @GetMapping("/tickets/{ticketId}")
  public AdminTicketDetailResponse ticket(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID ticketId) {
    requireAdmin(principal);
    return support.getAdminTicket(ticketId);
  }

  @PostMapping("/tickets/{ticketId}/assign")
  public void assign(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID ticketId,
      @Valid @RequestBody AdminAssignTicketRequest req) {
    requireAdmin(principal);
    support.adminAssign(ticketId, req);
  }

  @PostMapping("/tickets/{ticketId}/status")
  public void updateStatus(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID ticketId,
      @Valid @RequestBody AdminUpdateTicketStatusRequest req) {
    requireAdmin(principal);
    support.adminUpdateStatus(ticketId, req);
  }

  @PostMapping("/tickets/{ticketId}/messages")
  public TicketMessageResponse addMessage(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID ticketId,
      @Valid @RequestBody AddMessageRequest req) {
    requireAdmin(principal);
    return support.adminAddMessage(principal.userId(), ticketId, req);
  }

  @PostMapping("/tickets/{ticketId}/ai-draft")
  public AiDraftResponse aiDraft(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID ticketId) {
    requireAdmin(principal);
    return support.adminAiDraft(ticketId);
  }
}

