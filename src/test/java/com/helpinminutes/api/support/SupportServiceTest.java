package com.helpinminutes.api.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.support.ai.SupportAiService;
import com.helpinminutes.api.support.dto.CreateTicketRequest;
import com.helpinminutes.api.support.dto.TicketDetailResponse;
import com.helpinminutes.api.support.model.SupportMessageEntity;
import com.helpinminutes.api.support.model.SupportTicketCategory;
import com.helpinminutes.api.support.model.SupportTicketEntity;
import com.helpinminutes.api.support.model.SupportTicketPriority;
import com.helpinminutes.api.support.model.SupportTicketStatus;
import com.helpinminutes.api.support.repo.SupportMessageRepository;
import com.helpinminutes.api.support.repo.SupportTicketRepository;
import com.helpinminutes.api.support.service.SupportService;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupportServiceTest {

  @Test
  void accountDeletionIsEscalatedToAdminWithoutAiReply() {
    SupportTicketRepository tickets = mock(SupportTicketRepository.class);
    SupportMessageRepository messages = mock(SupportMessageRepository.class);
    UserRepository users = mock(UserRepository.class);
    SupportAiService ai = mock(SupportAiService.class);
    TaskRepository tasks = mock(TaskRepository.class);
    List<SupportMessageEntity> savedMessages = new ArrayList<>();

    when(tickets.save(any(SupportTicketEntity.class))).thenAnswer(invocation -> {
      SupportTicketEntity ticket = invocation.getArgument(0);
      ticket.prePersist();
      return ticket;
    });
    when(messages.save(any(SupportMessageEntity.class))).thenAnswer(invocation -> {
      SupportMessageEntity message = invocation.getArgument(0);
      message.prePersist();
      savedMessages.add(message);
      return message;
    });
    when(messages.findTop200ByTicketIdOrderByCreatedAtAsc(any())).thenAnswer(invocation ->
        savedMessages.stream()
            .filter(message -> invocation.getArgument(0).equals(message.getTicketId()))
            .toList());
    when(tickets.findById(any())).thenAnswer(invocation -> Optional.of(
        findTicketForId(invocation.getArgument(0), tickets)));

    SupportService service = new SupportService(tickets, messages, users, ai, tasks);
    UUID userId = UUID.randomUUID();
    TicketDetailResponse response = service.createTicket(
        userId,
        UserRole.BUYER,
        new CreateTicketRequest(
            SupportTicketCategory.ACCOUNT_DELETION,
            "Delete my account",
            "Please delete my account and associated personal data.",
            null));

    assertEquals(SupportTicketStatus.IN_PROGRESS.name(), response.status());
    assertEquals(SupportTicketPriority.HIGH.name(), response.priority());
    assertEquals(2, response.messages().size());
    assertTrue(response.messages().get(1).message().contains("routed directly to admin"));
    verify(ai, never()).draftReply(any(), any());
  }

  private static SupportTicketEntity findTicketForId(
      UUID id,
      SupportTicketRepository tickets) {
    return org.mockito.Mockito.mockingDetails(tickets).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("save"))
        .map(invocation -> (SupportTicketEntity) invocation.getArgument(0))
        .filter(ticket -> id.equals(ticket.getId()))
        .reduce((first, second) -> second)
        .orElseThrow();
  }
}
