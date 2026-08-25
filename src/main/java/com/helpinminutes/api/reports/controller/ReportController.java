package com.helpinminutes.api.reports.controller;

import com.helpinminutes.api.reports.dto.ReportDtos.*;
import com.helpinminutes.api.reports.service.AuditLogService;
import com.helpinminutes.api.reports.service.ReportExportService;
import com.helpinminutes.api.reports.service.ReportService;
import com.helpinminutes.api.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/admin/reports")
@PreAuthorize("hasAnyRole('ADMIN', 'ADMIN_READONLY')")
public class ReportController {

  private final ReportService reportService;
  private final ReportExportService exportService;
  private final AuditLogService auditLogService;

  public ReportController(ReportService reportService, ReportExportService exportService, AuditLogService auditLogService) {
    this.reportService = reportService;
    this.exportService = exportService;
    this.auditLogService = auditLogService;
  }

  @GetMapping("/master-summary")
  public ResponseEntity<MasterConsolidatedResponse> getMasterSummary(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "master-summary");
    return ResponseEntity.ok(reportService.getMasterConsolidatedReport(start, end));
  }

  @GetMapping("/bookings")
  public ResponseEntity<BookingReportResponse> getBookingReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String serviceType,
      @RequestParam(required = false) String location,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "bookings");
    return ResponseEntity.ok(reportService.getBookingReport(start, end, status, serviceType, location));
  }

  @GetMapping("/revenue-commission")
  public ResponseEntity<RevenueCommissionResponse> getRevenueCommissionReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "revenue-commission");
    return ResponseEntity.ok(reportService.getRevenueCommissionReport(start, end));
  }

  @GetMapping("/subscriptions")
  public ResponseEntity<SubscriptionReportResponse> getSubscriptionReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "subscriptions");
    return ResponseEntity.ok(reportService.getSubscriptionReport(start, end));
  }

  @GetMapping("/customers")
  public ResponseEntity<CustomerReportResponse> getCustomerReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "customers");
    return ResponseEntity.ok(reportService.getCustomerReport(start, end));
  }

  @GetMapping("/helpers")
  public ResponseEntity<HelperPerformanceResponse> getHelperPerformanceReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "helpers");
    return ResponseEntity.ok(reportService.getHelperPerformanceReport(start, end));
  }

  @GetMapping("/payments-settlements")
  public ResponseEntity<SettlementReportResponse> getSettlementReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "payments-settlements");
    return ResponseEntity.ok(reportService.getSettlementReport(start, end));
  }

  @GetMapping("/cancellations-refunds")
  public ResponseEntity<CancellationRefundResponse> getCancellationRefundReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "cancellations-refunds");
    return ResponseEntity.ok(reportService.getCancellationRefundReport(start, end));
  }

  @GetMapping("/locations")
  public ResponseEntity<LocationPerformanceResponse> getLocationReport(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    logAudit(principal, request, "VIEW_REPORT", "locations");
    return ResponseEntity.ok(reportService.getLocationPerformanceReport());
  }

  @GetMapping("/services")
  public ResponseEntity<ServicePerformanceResponse> getServiceReport(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    logAudit(principal, request, "VIEW_REPORT", "services");
    return ResponseEntity.ok(reportService.getServicePerformanceReport());
  }

  @GetMapping("/user-activity")
  public ResponseEntity<UserActivityResponse> getUserActivityReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "user-activity");
    return ResponseEntity.ok(reportService.getUserActivityReport(start, end));
  }

  @GetMapping("/audit-logs")
  public ResponseEntity<AuditLogResponse> getAuditLogs(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @RequestParam(defaultValue = "100") int limit,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "audit-logs");
    return ResponseEntity.ok(reportService.getAuditLogReport(start, end, limit));
  }

  @GetMapping("/ai-moderation")
  public ResponseEntity<AiModerationReportResponse> getAiModerationReport(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "VIEW_REPORT", "ai-moderation");
    return ResponseEntity.ok(reportService.getAiModerationReport(start, end));
  }

  @GetMapping("/export/bookings.csv")
  public ResponseEntity<StreamingResponseBody> exportBookingReportCsv(
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String serviceType,
      @RequestParam(required = false) String location,
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    Instant start = parseStart(startDate);
    Instant end = parseEnd(endDate);
    logAudit(principal, request, "EXPORT_REPORT", "bookings.csv");

    StreamingResponseBody stream = exportService.streamBookingReportCsv(reportService, start, end, status, serviceType, location);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"superherooo_booking_report.csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(stream);
  }

  @PostMapping("/refresh-materialized-views")
  public ResponseEntity<Map<String, String>> refreshMaterializedViews(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request) {
    logAudit(principal, request, "REFRESH_MATERIALIZED_VIEWS", "all");
    reportService.refreshMaterializedViews();
    return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Materialized views refreshed successfully"));
  }

  private Instant parseStart(String str) {
    if (str == null || str.isBlank()) {
      return Instant.now().minus(30, ChronoUnit.DAYS);
    }
    try {
      return Instant.parse(str);
    } catch (Exception e) {
      return Instant.now().minus(30, ChronoUnit.DAYS);
    }
  }

  private Instant parseEnd(String str) {
    if (str == null || str.isBlank()) {
      return Instant.now();
    }
    try {
      return Instant.parse(str);
    } catch (Exception e) {
      return Instant.now();
    }
  }

  private void logAudit(UserPrincipal principal, HttpServletRequest request, String action, String resource) {
    try {
      UUID actorId = principal != null ? principal.userId() : null;
      String actorEmail = principal != null && principal.role() != null ? principal.role().name() : "ADMIN";
      auditLogService.logAction(actorId, actorEmail, "ADMIN", action, resource, null, "Requested report endpoint: " + resource, request.getRemoteAddr());
    } catch (Exception ignored) {}
  }
}
