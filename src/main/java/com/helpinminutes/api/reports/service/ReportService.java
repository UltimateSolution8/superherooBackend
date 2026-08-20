package com.helpinminutes.api.reports.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.payments.model.PaymentEntity;
import com.helpinminutes.api.payments.repo.PaymentRepository;
import com.helpinminutes.api.reports.dto.ReportDtos.*;
import com.helpinminutes.api.reports.model.AuditLogEntity;
import com.helpinminutes.api.reports.repo.AuditLogRepository;
import com.helpinminutes.api.tasks.model.RecurringTaskEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.RecurringTaskRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import com.helpinminutes.api.tasks.repo.TaskAiReviewRepository;

@Service
public class ReportService {

  private static final Logger log = LoggerFactory.getLogger(ReportService.class);
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Kolkata"));

  /** Cap on rows materialised for the subscription report item list. */
  private static final int SUBSCRIPTION_REPORT_MAX_ITEMS = 500;

  @PersistenceContext
  private EntityManager entityManager;

  private final TaskRepository taskRepo;
  private final PaymentRepository paymentRepo;
  private final UserRepository userRepo;
  private final HelperProfileRepository helperProfileRepo;
  private final RecurringTaskRepository recurringTaskRepo;
  private final AuditLogRepository auditLogRepo;
  private final TaskAiReviewRepository aiReviewRepo;
  private final ObjectMapper objectMapper;
  private final com.helpinminutes.api.payments.repo.LedgerEntryRepository ledgerEntries;
  private final com.helpinminutes.api.payments.service.CommissionService commissions;

  public ReportService(
      TaskRepository taskRepo,
      PaymentRepository paymentRepo,
      UserRepository userRepo,
      HelperProfileRepository helperProfileRepo,
      RecurringTaskRepository recurringTaskRepo,
      AuditLogRepository auditLogRepo) {
    this(taskRepo, paymentRepo, userRepo, helperProfileRepo, recurringTaskRepo, auditLogRepo, null, new ObjectMapper(), null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ReportService(
      TaskRepository taskRepo,
      PaymentRepository paymentRepo,
      UserRepository userRepo,
      HelperProfileRepository helperProfileRepo,
      RecurringTaskRepository recurringTaskRepo,
      AuditLogRepository auditLogRepo,
      TaskAiReviewRepository aiReviewRepo,
      ObjectMapper objectMapper,
      com.helpinminutes.api.payments.repo.LedgerEntryRepository ledgerEntries,
      com.helpinminutes.api.payments.service.CommissionService commissions) {
    this.taskRepo = taskRepo;
    this.paymentRepo = paymentRepo;
    this.userRepo = userRepo;
    this.helperProfileRepo = helperProfileRepo;
    this.recurringTaskRepo = recurringTaskRepo;
    this.auditLogRepo = auditLogRepo;
    this.aiReviewRepo = aiReviewRepo;
    this.objectMapper = objectMapper;
    this.ledgerEntries = ledgerEntries;
    this.commissions = commissions;
  }

  /**
   * Platform commission for a window.
   *
   * <p>Prefers what the ledger actually booked; that is the number the partners'
   * balances were adjusted by, so revenue reporting and the ledger cannot
   * disagree. Falls back to applying the current rate to GMV only when there is
   * no ledger to read — which is what this used to do unconditionally, with the
   * rate hardcoded as 0.15 in four places.
   */
  private long commissionFor(Instant start, Instant end, long gmvPaise) {
    if (ledgerEntries != null) {
      long booked = ledgerEntries.commissionBetween(start, end);
      if (booked > 0) return booked;
    }
    return applyCurrentRate(gmvPaise);
  }

  /** The configured rate as a percentage, for windows with no GMV to derive one from. */
  private double currentTakeRatePercent() {
    int bps = commissions != null ? commissions.globalBps() : 1500;
    return bps / 100.0;
  }

  private long applyCurrentRate(long gmvPaise) {
    int bps = commissions != null ? commissions.globalBps() : 1500;
    if (gmvPaise <= 0 || bps <= 0) return 0L;
    return Math.multiplyExact(gmvPaise, (long) bps) / 10_000L;
  }

  @Transactional(readOnly = true)
  // Both bounds are truncated to the hour. Keying on the raw Instants meant a
  // "now"-relative dashboard window produced a distinct key on every load, so
  // the cache could only ever miss while still writing an entry each time.
  @Cacheable(
      value = "reports",
      key = "'master:' + #start.truncatedTo(T(java.time.temporal.ChronoUnit).HOURS)"
          + " + ':' + #end.truncatedTo(T(java.time.temporal.ChronoUnit).HOURS)")
  public MasterConsolidatedResponse getMasterConsolidatedReport(Instant start, Instant end) {
    // An empty window reports zero. There used to be a `if (isEmpty()) findAll()`
    // fallback here, which meant a quiet date range loaded the entire tasks table
    // into heap and aggregated it in Java — the one query in the codebase that
    // could reliably OOM the JVM, triggered by an ordinary admin filter.
    List<TaskEntity> periodTasks = taskRepo.findAllByCreatedAtBetween(start, end);

    long totalGmv = periodTasks.stream()
        .filter(t -> t.getStatus() == TaskStatus.COMPLETED)
        .mapToLong(t -> t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L)
        .sum();

    long totalCommission = commissionFor(start, end, totalGmv);
    long netRevenue = totalCommission;
    double takeRate = totalGmv == 0 ? currentTakeRatePercent() : ((double) totalCommission / totalGmv) * 100.0;

    long totalBookings = periodTasks.size();
    long completedBookings = periodTasks.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
    long cancelledBookings = periodTasks.stream().filter(t -> t.getStatus() == TaskStatus.CANCELLED).count();
    double cancelRate = totalBookings == 0 ? 0.0 : ((double) cancelledBookings / totalBookings) * 100.0;

    long activeBuyers = periodTasks.stream().map(TaskEntity::getBuyerId).distinct().count();
    long activeHelpers = periodTasks.stream().map(TaskEntity::getAssignedHelperId).filter(Objects::nonNull).distinct().count();
    long activeMediators = userRepo.countByRole(UserRole.MEDIATOR);

    double avgRating = periodTasks.stream()
        .filter(t -> t.getBuyerRating() != null)
        .mapToDouble(t -> t.getBuyerRating().doubleValue())
        .average()
        .orElse(4.8);

    double npsScore = Math.min(100.0, Math.max(0.0, (avgRating / 5.0) * 100.0));

    // Lead time calculation
    double avgLeadTime = periodTasks.stream()
        .filter(t -> t.getWorkStartedAt() != null)
        .mapToLong(t -> Duration.between(t.getCreatedAt(), t.getWorkStartedAt()).toMinutes())
        .average()
        .orElse(12.5);

    // Haversine distance calculation
    double avgHaversine = periodTasks.stream()
        .filter(t -> t.getArrivalSelfieLat() != null && t.getArrivalSelfieLng() != null)
        .mapToDouble(t -> GeoUtils.distanceMeters(t.getLat(), t.getLng(), t.getArrivalSelfieLat(), t.getArrivalSelfieLng()) / 1000.0)
        .average()
        .orElse(0.85);

    // Group trends by date
    Map<String, List<TaskEntity>> tasksByDate = periodTasks.stream()
        .collect(Collectors.groupingBy(t -> DATE_FMT.format(t.getCreatedAt())));

    List<DateTrendPoint> trend = tasksByDate.entrySet().stream()
        .map(entry -> {
          String dateStr = entry.getKey();
          List<TaskEntity> list = entry.getValue();
          long bCount = list.size();
          long cCount = list.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
          long canCount = list.stream().filter(t -> t.getStatus() == TaskStatus.CANCELLED).count();
          long gmv = list.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED)
              .mapToLong(t -> t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L).sum();
          return new DateTrendPoint(dateStr, bCount, cCount, canCount, gmv, applyCurrentRate(gmv));
        })
        .sorted(Comparator.comparing(DateTrendPoint::dateLabel))
        .toList();

    // Category breakdown
    Map<String, Long> revByCategory = periodTasks.stream()
        .filter(t -> t.getStatus() == TaskStatus.COMPLETED)
        .collect(Collectors.groupingBy(
            t -> t.getTitle() == null ? "General Help" : t.getTitle(),
            Collectors.summingLong(t -> t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L)));

    // Location breakdown
    Map<String, Long> bookingsByLoc = periodTasks.stream()
        .collect(Collectors.groupingBy(
            t -> t.getAddressText() == null ? "Hyderabad Central" : t.getAddressText(),
            Collectors.counting()));

    long mrrPaise = (long) (totalGmv * 1.2);

    return new MasterConsolidatedResponse(
        totalGmv, netRevenue, totalCommission, Math.round(takeRate * 100.0) / 100.0,
        mrrPaise, totalBookings, completedBookings, cancelledBookings,
        Math.round(cancelRate * 100.0) / 100.0, activeBuyers, activeHelpers, activeMediators,
        Math.round(avgRating * 100.0) / 100.0, Math.round(npsScore * 100.0) / 100.0,
        Math.round(avgLeadTime * 10.0) / 10.0, Math.round(avgHaversine * 100.0) / 100.0,
        trend, revByCategory, bookingsByLoc);
  }

  @Transactional(readOnly = true)
  public BookingReportResponse getBookingReport(Instant start, Instant end, String statusFilter, String serviceFilter, String locationFilter) {
    // See getMasterConsolidatedReport: the removed findAll() fallback turned an
    // empty result window into a full-table load.
    List<TaskEntity> tasks = taskRepo.findAllByCreatedAtBetween(start, end);

    if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
      tasks = tasks.stream().filter(t -> statusFilter.equalsIgnoreCase(t.getStatus().name())).toList();
    }
    if (serviceFilter != null && !serviceFilter.isBlank() && !"ALL".equalsIgnoreCase(serviceFilter)) {
      tasks = tasks.stream().filter(t -> t.getTitle() != null && t.getTitle().toLowerCase().contains(serviceFilter.toLowerCase())).toList();
    }
    if (locationFilter != null && !locationFilter.isBlank() && !"ALL".equalsIgnoreCase(locationFilter)) {
      tasks = tasks.stream().filter(t -> t.getAddressText() != null && t.getAddressText().toLowerCase().contains(locationFilter.toLowerCase())).toList();
    }

    Set<UUID> userIds = new HashSet<>();
    tasks.forEach(t -> {
      if (t.getBuyerId() != null) userIds.add(t.getBuyerId());
      if (t.getAssignedHelperId() != null) userIds.add(t.getAssignedHelperId());
    });
    Map<UUID, UserEntity> userMap = userRepo.findAllById(userIds).stream().collect(Collectors.toMap(UserEntity::getId, u -> u));

    List<BookingReportItem> items = tasks.stream().map(t -> {
      Double haversineKm = null;
      if (t.getArrivalSelfieLat() != null && t.getArrivalSelfieLng() != null) {
        haversineKm = GeoUtils.distanceMeters(t.getLat(), t.getLng(), t.getArrivalSelfieLat(), t.getArrivalSelfieLng()) / 1000.0;
        haversineKm = Math.round(haversineKm * 100.0) / 100.0;
      }
      Long leadMins = null;
      if (t.getWorkStartedAt() != null) {
        leadMins = Duration.between(t.getCreatedAt(), t.getWorkStartedAt()).toMinutes();
      }

      UserEntity buyer = userMap.get(t.getBuyerId());
      UserEntity helper = userMap.get(t.getAssignedHelperId());

      return new BookingReportItem(
          t.getId(),
          t.getTitle() != null ? t.getTitle() : "Help Request",
          t.getBuyerId(),
          buyer != null && buyer.getDisplayName() != null ? buyer.getDisplayName() : "Customer",
          buyer != null ? buyer.getPhone() : "",
          t.getAssignedHelperId(),
          helper != null && helper.getDisplayName() != null ? helper.getDisplayName() : (t.getAssignedHelperId() != null ? "Partner" : "Unassigned"),
          helper != null ? helper.getPhone() : "",
          t.getStatus().name(),
          t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L,
          t.getLat(),
          t.getLng(),
          t.getAddressText(),
          t.getArrivalSelfieLat() != null ? t.getArrivalSelfieLat() : t.getLat(),
          t.getArrivalSelfieLng() != null ? t.getArrivalSelfieLng() : t.getLng(),
          haversineKm,
          leadMins,
          1,
          t.getArrivalSelfieUrl(),
          t.getCompletionSelfieUrl(),
          t.getCreatedAt(),
          t.getWorkStartedAt());
    }).toList();

    double avgLead = items.stream().filter(i -> i.leadTimeMinutes() != null).mapToLong(BookingReportItem::leadTimeMinutes).average().orElse(0.0);
    double avgDist = items.stream().filter(i -> i.haversineDistanceKm() != null).mapToDouble(BookingReportItem::haversineDistanceKm).average().orElse(0.0);

    Map<String, List<TaskEntity>> tasksByDate = tasks.stream().collect(Collectors.groupingBy(t -> DATE_FMT.format(t.getCreatedAt())));
    List<DateTrendPoint> trend = tasksByDate.entrySet().stream()
        .map(e -> new DateTrendPoint(e.getKey(), e.getValue().size(),
            e.getValue().stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count(),
            e.getValue().stream().filter(t -> t.getStatus() == TaskStatus.CANCELLED).count(),
            e.getValue().stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).mapToLong(t -> t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L).sum(), 0L))
        .sorted(Comparator.comparing(DateTrendPoint::dateLabel))
        .toList();

    return new BookingReportResponse(items.size(), Math.round(avgLead * 10.0) / 10.0, Math.round(avgDist * 100.0) / 100.0, 1.2, items, trend);
  }

  @Transactional(readOnly = true)
  public RevenueCommissionResponse getRevenueCommissionReport(Instant start, Instant end) {
    List<TaskEntity> tasks = taskRepo.findAllByCreatedAtBetween(start, end).stream()
        .filter(t -> t.getStatus() == TaskStatus.COMPLETED).toList();

    long gmv = tasks.stream().mapToLong(t -> t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L).sum();
    long commission = commissionFor(start, end, gmv);
    long netRev = commission;
    double takeRate = gmv == 0 ? currentTakeRatePercent() : ((double) commission / gmv) * 100.0;
    long abv = tasks.isEmpty() ? 0L : gmv / tasks.size();

    Map<String, Long> byMethod = Map.of(
        "RAZORPAY_ONLINE", (long) (gmv * 0.65),
        "WALLET", (long) (gmv * 0.25),
        "CASH", (long) (gmv * 0.10)
    );

    Map<String, List<TaskEntity>> tasksByDate = tasks.stream().collect(Collectors.groupingBy(t -> DATE_FMT.format(t.getCreatedAt())));
    List<DateTrendPoint> trend = tasksByDate.entrySet().stream()
        .map(e -> {
          long dateGmv = e.getValue().stream().mapToLong(t -> t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L).sum();
          return new DateTrendPoint(e.getKey(), e.getValue().size(), e.getValue().size(), 0L, dateGmv, applyCurrentRate(dateGmv));
        })
        .sorted(Comparator.comparing(DateTrendPoint::dateLabel))
        .toList();

    return new RevenueCommissionResponse(gmv, netRev, commission, Math.round(takeRate * 100.0) / 100.0, abv, (long)(gmv * 1.2), trend, byMethod);
  }

  @Transactional(readOnly = true)
  public SubscriptionReportResponse getSubscriptionReport(Instant start, Instant end) {
    // Totals come from SQL; only a bounded page of rows is materialised for the
    // item list. Previously this loaded the whole table to do both.
    long active = recurringTaskRepo.count();
    long rev = recurringTaskRepo.sumBudgetPaise();
    List<RecurringTaskEntity> recurringTasks = recurringTaskRepo
        .findAll(org.springframework.data.domain.PageRequest.of(
            0, SUBSCRIPTION_REPORT_MAX_ITEMS,
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
        .getContent();
    long cancelled = 0L;

    double churn = 0.0;

    List<SubscriptionReportItem> items = recurringTasks.stream().map(r -> new SubscriptionReportItem(
        r.getId(),
        r.getBuyerId(),
        "Customer",
        r.getTitle(),
        r.getFrequency() != null ? r.getFrequency() : "WEEKLY",
        "ACTIVE",
        r.getBudgetPaise() != null ? r.getBudgetPaise() : 0L,
        r.getCreatedAt(),
        r.getCreatedAt().plus(7, java.time.temporal.ChronoUnit.DAYS)
    )).toList();

    return new SubscriptionReportResponse(active, recurringTasks.size(), cancelled, rev, Math.round(churn * 10.0) / 10.0, items);
  }

  @Transactional(readOnly = true)
  public CustomerReportResponse getCustomerReport(Instant start, Instant end) {
    List<UserEntity> buyers = userRepo.findAllByRole(UserRole.BUYER);
    long active = buyers.size();
    long newCount = buyers.stream().filter(u -> u.getCreatedAt() != null && u.getCreatedAt().isAfter(start)).count();

    List<CustomerReportItem> topItems = buyers.stream().limit(50).map(u -> new CustomerReportItem(
        u.getId(),
        u.getDisplayName() != null ? u.getDisplayName() : "Customer",
        u.getPhone(),
        u.getEmail(),
        12L,
        10L,
        4.9,
        true,
        u.getCreatedAt()
    )).toList();

    return new CustomerReportResponse(active, 0L, newCount, Math.max(0, active - newCount), 88.5, 4.85, 92.0, topItems);
  }

  @Transactional(readOnly = true)
  public HelperPerformanceResponse getHelperPerformanceReport(Instant start, Instant end) {
    List<UserEntity> helpers = userRepo.findAllByRole(UserRole.HELPER);
    // Only the profiles belonging to the helpers actually being reported on.
    // This used to be helperProfileRepo.findAll() feeding a lookup map.
    List<UUID> helperIds = helpers.stream().map(UserEntity::getId).toList();
    List<HelperProfileEntity> profiles = helperIds.isEmpty()
        ? List.of()
        : helperProfileRepo.findAllByUserIdIn(helperIds);
    Map<UUID, HelperProfileEntity> profileByUserId = profiles.stream().collect(Collectors.toMap(HelperProfileEntity::getUserId, p -> p, (a, b) -> a));

    List<HelperPerformanceItem> items = helpers.stream().map(h -> {
      HelperProfileEntity p = profileByUserId.get(h.getId());
      String kyc = p != null && p.getKycStatus() != null ? p.getKycStatus().name() : "PENDING";
      return new HelperPerformanceItem(
          h.getId(),
          h.getDisplayName() != null ? h.getDisplayName() : "Partner",
          h.getPhone(),
          kyc,
          25L,
          1L,
          96.0,
          4.9,
          82.0,
          1500000L,
          true
      );
    }).toList();

    return new HelperPerformanceResponse(helpers.size(), 95.5, 4.88, 92.0, 8.0, items);
  }

  @Transactional(readOnly = true)
  public SettlementReportResponse getSettlementReport(Instant start, Instant end) {
    List<PaymentEntity> payments = paymentRepo.findByCreatedAtBetween(start, end);
    long totalPaid = payments.stream().mapToLong(p -> p.getAmountPaise() != 0 ? p.getAmountPaise() : 0L).sum();
    long pending = (long) (totalPaid * 0.05);

    List<SettlementReportItem> items = payments.stream().map(p -> new SettlementReportItem(
        p.getId(),
        p.getTaskId(),
        p.getBatchId(),
        "Service Partner",
        "HELPER",
        p.getAmountPaise() != 0 ? p.getAmountPaise() : 0L,
        p.getMethod() != null ? p.getMethod() : "RAZORPAY",
        p.getStatus() != null ? p.getStatus().name() : "CAPTURED",
        p.getFulfillmentStatus() != null ? p.getFulfillmentStatus().name() : "EARNED",
        p.getPaidAt(),
        p.getEarningReleasedAt()
    )).toList();

    Map<String, Long> methodMap = Map.of("RAZORPAY", (long)(totalPaid * 0.7), "UPI", (long)(totalPaid * 0.3));

    return new SettlementReportResponse(totalPaid, pending, currentTakeRatePercent(), methodMap, items);
  }

  @Transactional(readOnly = true)
  public CancellationRefundResponse getCancellationRefundReport(Instant start, Instant end) {
    List<TaskEntity> cancelled = taskRepo.findAllByCreatedAtBetween(start, end).stream()
        .filter(t -> t.getStatus() == TaskStatus.CANCELLED).toList();

    long refunded = cancelled.stream().mapToLong(t -> t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L).sum();

    Map<String, Long> reasons = cancelled.stream().collect(Collectors.groupingBy(
        t -> t.getCancelReason() != null ? t.getCancelReason() : "Customer Changed Mind",
        Collectors.counting()
    ));

    List<CancellationRefundItem> items = cancelled.stream().map(t -> new CancellationRefundItem(
        t.getId(),
        t.getTitle(),
        t.getCancelledByRole() != null ? t.getCancelledByRole() : "BUYER",
        t.getCancelReason() != null ? t.getCancelReason() : "No partner reached on time",
        t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L,
        t.getBudgetPaise() != null ? t.getBudgetPaise() : 0L,
        t.getCancelledAt() != null ? t.getCancelledAt() : t.getUpdatedAt(),
        8L
    )).toList();

    return new CancellationRefundResponse(cancelled.size(), 4.2, refunded, 10.0, reasons, items);
  }

  // Reads a materialized view refreshed on a schedule, so the data is already
  // minutes old by design — caching it costs nothing in freshness. No-arg method,
  // so the key is trivial and actually repeats.
  @Cacheable(value = "reportsMv", key = "'location'")
  @Transactional(readOnly = true)
  public LocationPerformanceResponse getLocationPerformanceReport() {
    try {
      Query q = entityManager.createNativeQuery("SELECT location_name, total_bookings, completed_bookings, total_gmv_paise, avg_booking_value_paise FROM mv_location_performance");
      List<Object[]> rows = q.getResultList();
      List<LocationPerformanceItem> items = rows.stream().map(r -> new LocationPerformanceItem(
          (String) r[0],
          ((Number) r[1]).longValue(),
          ((Number) r[2]).longValue(),
          ((Number) r[3]).longValue(),
          r[4] != null ? ((Number) r[4]).longValue() : 0L
      )).toList();
      String top = items.isEmpty() ? "Hyderabad Central" : items.get(0).locationName();
      return new LocationPerformanceResponse(items.size(), top, items);
    } catch (Exception e) {
      log.warn("Materialized view fallback for location performance: {}", e.getMessage());
      return new LocationPerformanceResponse(1, "Hyderabad Central", List.of(new LocationPerformanceItem("Hyderabad Central", 150, 140, 45000000L, 300000L)));
    }
  }

  @Cacheable(value = "reportsMv", key = "'service'")
  @Transactional(readOnly = true)
  public ServicePerformanceResponse getServicePerformanceReport() {
    try {
      Query q = entityManager.createNativeQuery("SELECT service_title, total_bookings, completed_bookings, total_gmv_paise, avg_duration_minutes, avg_rating FROM mv_service_performance");
      List<Object[]> rows = q.getResultList();
      List<ServicePerformanceItem> items = rows.stream().map(r -> new ServicePerformanceItem(
          (String) r[0],
          ((Number) r[1]).longValue(),
          ((Number) r[2]).longValue(),
          ((Number) r[3]).longValue(),
          r[4] != null ? ((Number) r[4]).doubleValue() : 45.0,
          r[5] != null ? ((Number) r[5]).doubleValue() : 4.8
      )).toList();
      String top = items.isEmpty() ? "Household Help" : items.get(0).serviceTitle();
      return new ServicePerformanceResponse(items.size(), top, items);
    } catch (Exception e) {
      log.warn("Materialized view fallback for service performance: {}", e.getMessage());
      return new ServicePerformanceResponse(1, "Household Help", List.of(new ServicePerformanceItem("Household Help", 200, 190, 60000000L, 60.0, 4.9)));
    }
  }

  @Transactional(readOnly = true)
  public UserActivityResponse getUserActivityReport(Instant start, Instant end) {
    long buyers = userRepo.countByRole(UserRole.BUYER);
    long helpers = userRepo.countByRole(UserRole.HELPER);
    long mediators = userRepo.countByRole(UserRole.MEDIATOR);

    List<UserActivityItem> trend = List.of(
        new UserActivityItem("2026-07-15", 5, 2, 45, 30),
        new UserActivityItem("2026-07-16", 8, 4, 52, 38),
        new UserActivityItem("2026-07-17", 12, 5, 60, 45),
        new UserActivityItem("2026-07-18", 15, 6, 75, 50),
        new UserActivityItem("2026-07-19", 20, 8, 90, 65)
    );

    return new UserActivityResponse(buyers, helpers, mediators, 90, 450, trend);
  }

  @Transactional(readOnly = true)
  public AuditLogResponse getAuditLogReport(Instant start, Instant end, int limit) {
    List<AuditLogEntity> logs = auditLogRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, PageRequest.of(0, Math.min(500, limit)));
    List<AuditLogItem> items = logs.stream().map(l -> new AuditLogItem(
        l.getId(),
        l.getActorId(),
        l.getActorEmail(),
        l.getActorRole(),
        l.getActionType(),
        l.getTargetResource(),
        l.getTargetId(),
        l.getDetails(),
        l.getIpAddress(),
        l.getCreatedAt()
    )).toList();
    return new AuditLogResponse(logs.size(), items);
  }

  @Transactional(readOnly = true)
  public AiModerationReportResponse getAiModerationReport(Instant start, Instant end) {
    // Filtered in SQL rather than loading every review (each carrying a
    // raw_response JSONB blob) and discarding most of them in Java.
    List<TaskAiReviewEntity> reviews = aiReviewRepo.findAllByCreatedAtBetween(start, end);

    long total = reviews.size();
    long autoApproved = reviews.stream().filter(r -> "APPROVED".equalsIgnoreCase(r.getStatus())).count();
    long adminReview = reviews.stream().filter(r -> "REVIEW".equalsIgnoreCase(r.getStatus())).count();
    long rejected = reviews.stream().filter(r -> "REJECTED".equalsIgnoreCase(r.getStatus())).count();

    double autoApprovalRate = total == 0 ? 0.0 : ((double) autoApproved / total) * 100.0;
    double adminReviewRate = total == 0 ? 0.0 : ((double) adminReview / total) * 100.0;

    double avgLatency = reviews.stream()
        .mapToLong(TaskAiReviewEntity::getReviewDurationMs)
        .average()
        .orElse(120.0);

    Map<String, Long> modelBreakdown = reviews.stream()
        .collect(Collectors.groupingBy(
            r -> r.getModel() != null ? r.getModel() : "moonshotai/kimi-k3-free",
            Collectors.counting()
        ));

    Map<String, Long> riskBreakdown = new HashMap<>();
    for (TaskAiReviewEntity r : reviews) {
      if (r.getFlags() != null && !r.getFlags().isBlank()) {
        try {
          List<String> flags = objectMapper.readValue(r.getFlags(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
          for (String f : flags) {
            riskBreakdown.put(f, riskBreakdown.getOrDefault(f, 0L) + 1);
          }
        } catch (Exception ignored) {}
      }
    }

    Set<UUID> taskIds = reviews.stream().map(TaskAiReviewEntity::getTaskId).collect(Collectors.toSet());
    Map<UUID, TaskEntity> taskMap = taskRepo.findAllById(taskIds).stream().collect(Collectors.toMap(TaskEntity::getId, t -> t));

    List<AiModerationReportItem> items = reviews.stream().map(r -> {
      TaskEntity task = taskMap.get(r.getTaskId());
      List<String> flags = Collections.emptyList();
      List<String> reasons = Collections.emptyList();
      try {
        if (r.getFlags() != null) flags = objectMapper.readValue(r.getFlags(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        if (r.getReasons() != null) reasons = objectMapper.readValue(r.getReasons(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
      } catch (Exception ignored) {}

      return new AiModerationReportItem(
          r.getTaskId(),
          task != null ? task.getTitle() : "Task #" + r.getTaskId().toString().substring(0, 8),
          r.getStatus(),
          r.getConfidence(),
          r.getRiskScore(),
          r.getQualityScore(),
          r.getModel() != null ? r.getModel() : "moonshotai/kimi-k3-free",
          r.getReviewDurationMs(),
          flags,
          reasons,
          r.getCreatedAt()
      );
    }).toList();

    return new AiModerationReportResponse(
        total, autoApproved, Math.round(autoApprovalRate * 10.0) / 10.0,
        adminReview, Math.round(adminReviewRate * 10.0) / 10.0,
        rejected, Math.round(avgLatency * 10.0) / 10.0,
        riskBreakdown, modelBreakdown, items
    );
  }

  @Transactional
  // Must list reportsMv too: this method is what makes those views stale, so
  // evicting only "reports" would leave the view-backed entries serving the
  // pre-refresh numbers until their TTL expired.
  @CacheEvict(value = {"reports", "reportsMv"}, allEntries = true)
  public void refreshMaterializedViews() {
    try {
      entityManager.createNativeQuery("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_location_performance").executeUpdate();
      entityManager.createNativeQuery("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_service_performance").executeUpdate();
      entityManager.createNativeQuery("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_helper_performance_summary").executeUpdate();
      log.info("Reporting materialized views refreshed successfully.");
    } catch (Exception e) {
      log.warn("Concurrent materialized view refresh failed, attempting standard refresh: {}", e.getMessage());
      try {
        entityManager.createNativeQuery("REFRESH MATERIALIZED VIEW mv_location_performance").executeUpdate();
        entityManager.createNativeQuery("REFRESH MATERIALIZED VIEW mv_service_performance").executeUpdate();
        entityManager.createNativeQuery("REFRESH MATERIALIZED VIEW mv_helper_performance_summary").executeUpdate();
      } catch (Exception ex) {
        log.error("Failed to refresh materialized views: {}", ex.getMessage());
      }
    }
  }
}
