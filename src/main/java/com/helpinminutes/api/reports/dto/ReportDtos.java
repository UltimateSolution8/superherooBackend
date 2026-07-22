package com.helpinminutes.api.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReportDtos {

  public record DateTrendPoint(
      String dateLabel,
      long totalBookings,
      long completedBookings,
      long cancelledBookings,
      long gmvPaise,
      long revenuePaise
  ) {}

  public record MasterConsolidatedResponse(
      long totalGmvPaise,
      long totalRevenuePaise,
      long totalCommissionPaise,
      double takeRatePercentage,
      long mrrPaise,
      long totalBookings,
      long completedBookings,
      long cancelledBookings,
      double cancellationRatePercentage,
      long activeBuyersCount,
      long activeHelpersCount,
      long activeMediatorsCount,
      double avgCustomerRating,
      double npsScore,
      double avgBookingLeadTimeMinutes,
      double avgHaversineDistanceKm,
      List<DateTrendPoint> trends,
      Map<String, Long> revenueByServiceCategory,
      Map<String, Long> bookingsByLocation
  ) {}

  public record BookingReportItem(
      UUID id,
      String title,
      UUID buyerId,
      String buyerName,
      String buyerPhone,
      UUID assignedHelperId,
      String helperName,
      String helperPhone,
      String status,
      long budgetPaise,
      double lat,
      double lng,
      String addressText,
      double arrivalLat,
      double arrivalLng,
      Double haversineDistanceKm,
      Long leadTimeMinutes,
      Integer searchesCount,
      String arrivalSelfieUrl,
      String completionSelfieUrl,
      Instant createdAt,
      Instant workStartedAt
  ) {}

  public record BookingReportResponse(
      long totalCount,
      double avgLeadTimeMinutes,
      double avgHaversineDistanceKm,
      double avgSearchesPerBooking,
      List<BookingReportItem> items,
      List<DateTrendPoint> trend
  ) {}

  public record RevenueCommissionResponse(
      long totalGmvPaise,
      long totalRevenuePaise,
      long totalCommissionPaise,
      double takeRatePercentage,
      long avgBookingValuePaise,
      long monthlyRecurringRevenuePaise,
      List<DateTrendPoint> dailyTrends,
      Map<String, Long> revenueByPaymentMethod
  ) {}

  public record SubscriptionReportItem(
      UUID id,
      UUID buyerId,
      String buyerName,
      String title,
      String cadence,
      String status,
      long budgetPaise,
      Instant createdAt,
      Instant nextOccurrence
  ) {}

  public record SubscriptionReportResponse(
      long activeSubscriptionsCount,
      long newSignupsCount,
      long cancelledCount,
      long recurringRevenuePaise,
      double churnRatePercentage,
      List<SubscriptionReportItem> items
  ) {}

  public record CustomerReportItem(
      UUID id,
      String displayName,
      String phone,
      String email,
      long totalBookingsCount,
      long completedBookingsCount,
      double avgRatingGiven,
      boolean isActive,
      Instant createdAt
  ) {}

  public record CustomerReportResponse(
      long activeCustomersCount,
      long inactiveCustomersCount,
      long newCustomersCount,
      long returningCustomersCount,
      double retentionRatePercentage,
      double avgRatingGiven,
      double npsScore,
      List<CustomerReportItem> topCustomers
  ) {}

  public record HelperPerformanceItem(
      UUID id,
      String displayName,
      String phone,
      String kycStatus,
      long tasksCompletedCount,
      long tasksCancelledCount,
      double acceptanceRatePercentage,
      double avgRatingReceived,
      double utilizationPercentage,
      long totalEarnedPaise,
      boolean isActive
  ) {}

  public record HelperPerformanceResponse(
      long activeHelpersCount,
      double avgAcceptanceRatePercentage,
      double avgRating,
      double retentionRatePercentage,
      double churnRatePercentage,
      List<HelperPerformanceItem> helpers
  ) {}

  public record SettlementReportItem(
      UUID paymentId,
      UUID taskId,
      UUID batchId,
      String recipientName,
      String recipientRole,
      long amountPaise,
      String method,
      String status,
      String fulfillmentStatus,
      Instant paidAt,
      Instant settledAt
  ) {}

  public record SettlementReportResponse(
      long totalPaidPaise,
      long pendingPayoutsPaise,
      double avgSettlementLatencyMinutes,
      Map<String, Long> paymentMethodBreakdown,
      List<SettlementReportItem> items
  ) {}

  public record CancellationRefundItem(
      UUID taskId,
      String title,
      String cancelledByRole,
      String cancelReason,
      long budgetPaise,
      long refundedAmountPaise,
      Instant cancelledAt,
      Long resolutionTimeMinutes
  ) {}

  public record CancellationRefundResponse(
      long totalCancellationsCount,
      double cancellationRatePercentage,
      long totalRefundedPaise,
      double avgResolutionTimeMinutes,
      Map<String, Long> reasonBreakdown,
      List<CancellationRefundItem> items
  ) {}

  public record LocationPerformanceItem(
      String locationName,
      long totalBookings,
      long completedBookings,
      long totalGmvPaise,
      long avgBookingValuePaise
  ) {}

  public record LocationPerformanceResponse(
      long totalLocations,
      String topPerformingRegion,
      List<LocationPerformanceItem> locations
  ) {}

  public record ServicePerformanceItem(
      String serviceTitle,
      long totalBookings,
      long completedBookings,
      long totalGmvPaise,
      double avgDurationMinutes,
      double avgRating
  ) {}

  public record ServicePerformanceResponse(
      long totalServicesCount,
      String topGrossingService,
      List<ServicePerformanceItem> services
  ) {}

  public record UserActivityItem(
      String dateLabel,
      long newBuyers,
      long newHelpers,
      long activeBuyers,
      long activeHelpers
  ) {}

  public record UserActivityResponse(
      long totalBuyersCount,
      long totalHelpersCount,
      long totalMediatorsCount,
      long dailyActiveUsersCount,
      long monthlyActiveUsersCount,
      List<UserActivityItem> activityTrend
  ) {}

  public record AuditLogItem(
      UUID id,
      UUID actorId,
      String actorEmail,
      String actorRole,
      String actionType,
      String targetResource,
      String targetId,
      String details,
      String ipAddress,
      Instant createdAt
  ) {}

  public record AuditLogResponse(
      long totalLogsCount,
      List<AuditLogItem> items
  ) {}

  public record AiModerationReportItem(
      UUID taskId,
      String title,
      String aiStatus,
      int confidence,
      int riskScore,
      int qualityScore,
      String modelUsed,
      long latencyMs,
      List<String> flags,
      List<String> reasons,
      Instant evaluatedAt
  ) {}

  public record AiModerationReportResponse(
      long totalTasksEvaluated,
      long autoApprovedCount,
      double autoApprovalRatePercentage,
      long adminReviewCount,
      double adminReviewRatePercentage,
      long rejectedCount,
      double avgLatencyMs,
      Map<String, Long> riskCategoryBreakdown,
      Map<String, Long> modelUsageBreakdown,
      List<AiModerationReportItem> items
  ) {}
}
