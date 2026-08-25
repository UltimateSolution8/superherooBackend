package com.helpinminutes.api.admin.service;

import com.helpinminutes.api.admin.dto.AdminActionCenterResponse;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.support.model.SupportTicketStatus;
import com.helpinminutes.api.support.repo.SupportTicketRepository;
import com.helpinminutes.api.users.model.UserRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminActionCenterService {
  private final HelperProfileRepository helperProfiles;
  private final BookingBatchRepository bookingBatches;
  private final SupportTicketRepository supportTickets;

  public AdminActionCenterService(
      HelperProfileRepository helperProfiles,
      BookingBatchRepository bookingBatches,
      SupportTicketRepository supportTickets) {
    this.helperProfiles = helperProfiles;
    this.bookingBatches = bookingBatches;
    this.supportTickets = supportTickets;
  }

  @Transactional(readOnly = true)
  public AdminActionCenterResponse get(UserRole role) {
    List<AdminActionCenterResponse.ActionItem> items = new ArrayList<>();

    if (role == UserRole.ADMIN || role == UserRole.ADMIN_READONLY || role == UserRole.KYC) {
      long count = helperProfiles.countByKycStatus(HelperKycStatus.PENDING);
      add(items, count, "KYC_REVIEW", "KYC reviews pending",
          count == 1 ? "1 partner is waiting for KYC review." : count + " partners are waiting for KYC review.",
          "/helpers/pending", count > 0 ? "HIGH" : "INFO");
    }

    if (role == UserRole.ADMIN || role == UserRole.ADMIN_READONLY) {
      long count = bookingBatches.countByStatus(BookingBatchStatus.PENDING_AUDIT)
          + bookingBatches.countByStatus(BookingBatchStatus.ON_HOLD);
      add(items, count, "MEDIATOR_APPROVAL", "Bulk requests need approval",
          count == 1 ? "1 mediator-routed request needs a decision." : count + " mediator-routed requests need a decision.",
          "/bulk-requests", count > 0 ? "HIGH" : "INFO");
    }

    if (role == UserRole.ADMIN || role == UserRole.ADMIN_READONLY || role == UserRole.SUPPORT) {
      long count = supportTickets.countByStatus(SupportTicketStatus.IN_PROGRESS);
      add(items, count, "SUPPORT_HANDOFF", "Support conversations need attention",
          count == 1 ? "1 conversation is waiting for human support." : count + " conversations are waiting for human support.",
          "/support/tickets", count > 0 ? "HIGH" : "INFO");
    }

    long total = items.stream().mapToLong(AdminActionCenterResponse.ActionItem::count).sum();
    return new AdminActionCenterResponse(total, List.copyOf(items), Instant.now());
  }

  private static void add(
      List<AdminActionCenterResponse.ActionItem> items,
      long count,
      String type,
      String title,
      String description,
      String href,
      String severity) {
    if (count <= 0) return;
    items.add(new AdminActionCenterResponse.ActionItem(type, title, description, count, href, severity));
  }
}
