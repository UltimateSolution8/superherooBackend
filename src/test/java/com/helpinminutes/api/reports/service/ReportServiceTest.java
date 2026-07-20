package com.helpinminutes.api.reports.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.payments.repo.PaymentRepository;
import com.helpinminutes.api.reports.dto.ReportDtos.*;
import com.helpinminutes.api.reports.repo.AuditLogRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.RecurringTaskRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

  @Mock private TaskRepository taskRepo;
  @Mock private PaymentRepository paymentRepo;
  @Mock private UserRepository userRepo;
  @Mock private HelperProfileRepository helperProfileRepo;
  @Mock private RecurringTaskRepository recurringTaskRepo;
  @Mock private AuditLogRepository auditLogRepo;

  private ReportService reportService;

  @BeforeEach
  void setUp() {
    reportService = new ReportService(
        taskRepo, paymentRepo, userRepo, helperProfileRepo, recurringTaskRepo, auditLogRepo);
  }

  @Test
  void testGetMasterConsolidatedReport() {
    Instant now = Instant.now();
    Instant start = now.minus(7, ChronoUnit.DAYS);

    TaskEntity task = new TaskEntity();
    task.prePersist();
    task.setStatus(TaskStatus.COMPLETED);
    task.setBudgetPaise(50000L); // ₹500
    task.setBuyerId(UUID.randomUUID());

    when(taskRepo.findAllByCreatedAtBetween(any(), any())).thenReturn(List.of(task));

    MasterConsolidatedResponse res = reportService.getMasterConsolidatedReport(start, now);

    assertNotNull(res);
    assertEquals(50000L, res.totalGmvPaise());
    assertEquals(7500L, res.totalCommissionPaise()); // 15% of 50000
    assertEquals(15.0, res.takeRatePercentage());
    assertEquals(1, res.totalBookings());
    assertEquals(1, res.completedBookings());
  }

  @Test
  void testGetBookingReportWithHaversineDistance() {
    Instant now = Instant.now();
    Instant start = now.minus(7, ChronoUnit.DAYS);

    TaskEntity task = new TaskEntity();
    task.prePersist();
    task.setTitle("House Cleaning");
    task.setStatus(TaskStatus.COMPLETED);
    task.setBudgetPaise(30000L);
    task.setLat(17.3850);
    task.setLng(78.4867);
    task.setArrivalSelfieLat(17.3855);
    task.setArrivalSelfieLng(78.4870);

    when(taskRepo.findAllByCreatedAtBetween(any(), any())).thenReturn(List.of(task));

    BookingReportResponse res = reportService.getBookingReport(start, now, null, null, null);

    assertNotNull(res);
    assertEquals(1, res.totalCount());
    assertNotNull(res.items().get(0).haversineDistanceKm());
    assertTrue(res.items().get(0).haversineDistanceKm() > 0.0);
  }
}
