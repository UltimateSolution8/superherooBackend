package com.helpinminutes.api.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.support.model.SupportTicketStatus;
import com.helpinminutes.api.support.repo.SupportTicketRepository;
import com.helpinminutes.api.users.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminActionCenterServiceTest {
  private HelperProfileRepository helpers;
  private BookingBatchRepository batches;
  private SupportTicketRepository tickets;
  private AdminActionCenterService service;

  @BeforeEach
  void setUp() {
    helpers = mock(HelperProfileRepository.class);
    batches = mock(BookingBatchRepository.class);
    tickets = mock(SupportTicketRepository.class);
    service = new AdminActionCenterService(helpers, batches, tickets);
  }

  @Test
  void fullAdminSeesKycMediatorAndSupportActions() {
    when(helpers.countByKycStatus(HelperKycStatus.PENDING)).thenReturn(2L);
    when(batches.countByStatus(BookingBatchStatus.PENDING_AUDIT)).thenReturn(3L);
    when(batches.countByStatus(BookingBatchStatus.ON_HOLD)).thenReturn(1L);
    when(tickets.countByStatus(SupportTicketStatus.IN_PROGRESS)).thenReturn(5L);

    var result = service.get(UserRole.ADMIN);

    assertEquals(11L, result.actionCount());
    assertEquals(3, result.items().size());
  }

  @Test
  void restrictedRolesOnlySeeTheirOwnWorkQueue() {
    when(helpers.countByKycStatus(HelperKycStatus.PENDING)).thenReturn(2L);
    when(tickets.countByStatus(SupportTicketStatus.IN_PROGRESS)).thenReturn(4L);

    assertEquals(2L, service.get(UserRole.KYC).actionCount());
    assertEquals(1, service.get(UserRole.KYC).items().size());
    assertEquals(4L, service.get(UserRole.SUPPORT).actionCount());
    assertEquals(1, service.get(UserRole.SUPPORT).items().size());
  }
}
