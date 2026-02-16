package com.helpinminutes.api.support.service;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.support.ai.SupportAiService;
import com.helpinminutes.api.support.dto.AddMessageRequest;
import com.helpinminutes.api.support.dto.AdminAssignTicketRequest;
import com.helpinminutes.api.support.dto.AdminTicketDetailResponse;
import com.helpinminutes.api.support.dto.AdminTicketResponse;
import com.helpinminutes.api.support.dto.AdminUpdateTicketStatusRequest;
import com.helpinminutes.api.support.dto.AiDraftResponse;
import com.helpinminutes.api.support.dto.CreateTicketRequest;
import com.helpinminutes.api.support.dto.TicketDetailResponse;
import com.helpinminutes.api.support.dto.TicketMessageResponse;
import com.helpinminutes.api.support.dto.TicketResponse;
import com.helpinminutes.api.support.model.SupportAuthorType;
import com.helpinminutes.api.support.model.SupportMessageEntity;
import com.helpinminutes.api.support.model.SupportTicketEntity;
import com.helpinminutes.api.support.model.SupportTicketStatus;
import com.helpinminutes.api.support.repo.SupportMessageRepository;
import com.helpinminutes.api.support.repo.SupportTicketRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupportService {
  private final SupportTicketRepository tickets;
  private final SupportMessageRepository messages;
  private final UserRepository users;
  private final SupportAiService ai;

  public SupportService(
      SupportTicketRepository tickets,
      SupportMessageRepository messages,
      UserRepository users,
      SupportAiService ai) {
    this.tickets = tickets;
    this.messages = messages;
    this.users = users;
    this.ai = ai;
  }

  @Transactional
  public TicketDetailResponse createTicket(UUID userId, UserRole role, CreateTicketRequest req) {
    SupportTicketEntity t = new SupportTicketEntity();
    t.setCreatedByUserId(userId);
    t.setRole(role);
    t.setCategory(req.category());
    t.setSubject(trimOrNull(req.subject()));
    t.setRelatedTaskId(req.relatedTaskId());
    t.setLastMessageAt(Instant.now());
    t = tickets.save(t);

    SupportMessageEntity m = new SupportMessageEntity();
    m.setTicketId(t.getId());
    m.setAuthorType(SupportAuthorType.USER);
    m.setAuthorUserId(userId);
    m.setMessage(req.message().trim());
    m = messages.save(m);

    t.setLastMessageAt(m.getCreatedAt());
    tickets.save(t);

    return getMyTicket(userId, t.getId());
  }

  public List<TicketResponse> listMyTickets(UUID userId) {
    return tickets.findByCreatedByUserIdOrderByLastMessageAtDesc(userId).stream()
        .map(SupportService::toTicketResponse)
        .toList();
  }

  public TicketDetailResponse getMyTicket(UUID userId, UUID ticketId) {
    SupportTicketEntity t = tickets.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found"));
    if (!t.getCreatedByUserId().equals(userId)) {
      throw new ForbiddenException("Not your ticket");
    }
    List<TicketMessageResponse> msgs = messages.findTop200ByTicketIdOrderByCreatedAtAsc(ticketId).stream()
        .map(SupportService::toMessageResponse)
        .toList();
    return toTicketDetailResponse(t, msgs);
  }

  @Transactional
  public TicketMessageResponse addMyMessage(UUID userId, UUID ticketId, AddMessageRequest req) {
    SupportTicketEntity t = tickets.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found"));
    if (!t.getCreatedByUserId().equals(userId)) {
      throw new ForbiddenException("Not your ticket");
    }
    if (t.getStatus() == SupportTicketStatus.CLOSED) {
      throw new BadRequestException("Ticket is closed");
    }

    SupportMessageEntity m = new SupportMessageEntity();
    m.setTicketId(ticketId);
    m.setAuthorType(SupportAuthorType.USER);
    m.setAuthorUserId(userId);
    m.setMessage(req.message().trim());
    m = messages.save(m);

    t.setLastMessageAt(m.getCreatedAt());
    if (t.getStatus() == SupportTicketStatus.RESOLVED) {
      t.setStatus(SupportTicketStatus.OPEN);
    }
    tickets.save(t);

    return toMessageResponse(m);
  }

  public List<AdminTicketResponse> listAdminTickets(SupportTicketStatus status) {
    List<SupportTicketEntity> list = status == null
        ? tickets.findTop50ByOrderByLastMessageAtDesc()
        : tickets.findTop50ByStatusOrderByLastMessageAtDesc(status);

    Set<UUID> userIds = list.stream().map(SupportTicketEntity::getCreatedByUserId).collect(Collectors.toSet());
    Map<UUID, UserEntity> byId = new HashMap<>();
    users.findAllById(userIds).forEach(u -> byId.put(u.getId(), u));

    return list.stream().map(t -> toAdminTicketResponse(t, byId.get(t.getCreatedByUserId()))).toList();
  }

  public AdminTicketDetailResponse getAdminTicket(UUID ticketId) {
    SupportTicketEntity t = tickets.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found"));
    UserEntity u = users.findById(t.getCreatedByUserId()).orElse(null);
    List<TicketMessageResponse> msgs = messages.findTop200ByTicketIdOrderByCreatedAtAsc(ticketId).stream()
        .map(SupportService::toMessageResponse)
        .toList();
    return toAdminTicketDetailResponse(t, u, msgs);
  }

  @Transactional
  public void adminAssign(UUID ticketId, AdminAssignTicketRequest req) {
    SupportTicketEntity t = tickets.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found"));
    t.setAssigneeUserId(req.assigneeUserId());
    tickets.save(t);
  }

  @Transactional
  public void adminUpdateStatus(UUID ticketId, AdminUpdateTicketStatusRequest req) {
    SupportTicketEntity t = tickets.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found"));
    t.setStatus(req.status());
    tickets.save(t);
  }

  @Transactional
  public TicketMessageResponse adminAddMessage(UUID adminId, UUID ticketId, AddMessageRequest req) {
    SupportTicketEntity t = tickets.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found"));

    SupportMessageEntity m = new SupportMessageEntity();
    m.setTicketId(ticketId);
    m.setAuthorType(SupportAuthorType.ADMIN);
    m.setAuthorUserId(adminId);
    m.setMessage(req.message().trim());
    m = messages.save(m);

    t.setLastMessageAt(m.getCreatedAt());
    if (t.getStatus() == SupportTicketStatus.OPEN) {
      t.setStatus(SupportTicketStatus.IN_PROGRESS);
    }
    tickets.save(t);

    return toMessageResponse(m);
  }

  public AiDraftResponse adminAiDraft(UUID ticketId) {
    SupportTicketEntity t = tickets.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found"));
    List<SupportMessageEntity> msgs = messages.findTop200ByTicketIdOrderByCreatedAtAsc(ticketId);
    return ai.draftReply(t, msgs);
  }

  private static TicketResponse toTicketResponse(SupportTicketEntity t) {
    return new TicketResponse(
        t.getId(),
        t.getCategory().name(),
        t.getSubject(),
        t.getStatus().name(),
        t.getPriority().name(),
        t.getRelatedTaskId(),
        t.getLastMessageAt(),
        t.getCreatedAt(),
        t.getUpdatedAt()
    );
  }

  private static TicketDetailResponse toTicketDetailResponse(SupportTicketEntity t, List<TicketMessageResponse> msgs) {
    return new TicketDetailResponse(
        t.getId(),
        t.getCategory().name(),
        t.getSubject(),
        t.getStatus().name(),
        t.getPriority().name(),
        t.getRelatedTaskId(),
        t.getLastMessageAt(),
        t.getCreatedAt(),
        t.getUpdatedAt(),
        msgs
    );
  }

  private static TicketMessageResponse toMessageResponse(SupportMessageEntity m) {
    return new TicketMessageResponse(
        m.getId(),
        m.getAuthorType().name(),
        m.getAuthorUserId(),
        m.getMessage(),
        m.getCreatedAt()
    );
  }

  private static AdminTicketResponse toAdminTicketResponse(SupportTicketEntity t, UserEntity u) {
    String phone = u == null ? null : u.getPhone();
    return new AdminTicketResponse(
        t.getId(),
        t.getCreatedByUserId(),
        t.getRole().name(),
        phone,
        t.getCategory().name(),
        t.getSubject(),
        t.getStatus().name(),
        t.getPriority().name(),
        t.getRelatedTaskId(),
        t.getAssigneeUserId(),
        t.getLastMessageAt(),
        t.getCreatedAt(),
        t.getUpdatedAt()
    );
  }

  private static AdminTicketDetailResponse toAdminTicketDetailResponse(
      SupportTicketEntity t,
      UserEntity u,
      List<TicketMessageResponse> msgs) {
    String phone = u == null ? null : u.getPhone();
    return new AdminTicketDetailResponse(
        t.getId(),
        t.getCreatedByUserId(),
        t.getRole().name(),
        phone,
        t.getCategory().name(),
        t.getSubject(),
        t.getStatus().name(),
        t.getPriority().name(),
        t.getRelatedTaskId(),
        t.getAssigneeUserId(),
        t.getLastMessageAt(),
        t.getCreatedAt(),
        t.getUpdatedAt(),
        msgs
    );
  }

  private static String trimOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}

