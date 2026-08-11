package com.helpinminutes.api.reports;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.payments.repo.PaymentRepository;
import com.helpinminutes.api.reports.dto.ReportDtos.AiModerationReportResponse;
import com.helpinminutes.api.reports.repo.AuditLogRepository;
import com.helpinminutes.api.reports.service.ReportService;
import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.repo.RecurringTaskRepository;
import com.helpinminutes.api.tasks.repo.TaskAiReviewRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiModerationReportTest {

  private TaskRepository taskRepo;
  private PaymentRepository paymentRepo;
  private UserRepository userRepo;
  private HelperProfileRepository helperProfileRepo;
  private RecurringTaskRepository recurringTaskRepo;
  private AuditLogRepository auditLogRepo;
  private TaskAiReviewRepository aiReviewRepo;
  private ObjectMapper objectMapper;

  private ReportService reportService;

  @BeforeEach
  void setUp() {
    taskRepo = mock(TaskRepository.class);
    paymentRepo = mock(PaymentRepository.class);
    userRepo = mock(UserRepository.class);
    helperProfileRepo = mock(HelperProfileRepository.class);
    recurringTaskRepo = mock(RecurringTaskRepository.class);
    auditLogRepo = mock(AuditLogRepository.class);
    aiReviewRepo = mock(TaskAiReviewRepository.class);
    objectMapper = new ObjectMapper();

    reportService = new ReportService(
        taskRepo,
        paymentRepo,
        userRepo,
        helperProfileRepo,
        recurringTaskRepo,
        auditLogRepo,
        aiReviewRepo,
        objectMapper
    );
  }

  @Test
  void getAiModerationReportComputesCorrectAggregations() {
    UUID task1 = UUID.randomUUID();
    UUID task2 = UUID.randomUUID();

    TaskAiReviewEntity r1 = new TaskAiReviewEntity();
    r1.setTaskId(task1);
    r1.setStatus("APPROVED");
    r1.setConfidence(98);
    r1.setRiskScore(5);
    r1.setQualityScore(95);
    r1.setModel("moonshotai/kimi-k3-free");
    r1.setReviewDurationMs(110L);
    r1.setCreatedAt(Instant.now().minusSeconds(3600));

    TaskAiReviewEntity r2 = new TaskAiReviewEntity();
    r2.setTaskId(task2);
    r2.setStatus("REVIEW");
    r2.setConfidence(60);
    r2.setRiskScore(80);
    r2.setQualityScore(40);
    r2.setFlags("[\"CONTACT_LEAK\"]");
    r2.setModel("moonshotai/kimi-k3-free");
    r2.setReviewDurationMs(130L);
    r2.setCreatedAt(Instant.now().minusSeconds(1800));

    // The report now bounds the scan in SQL instead of loading every review
    // (raw_response JSONB included) and filtering by date in Java.
    when(aiReviewRepo.findAllByCreatedAtBetween(any(), any())).thenReturn(List.of(r1, r2));

    TaskEntity t1 = new TaskEntity();
    t1.setId(task1);
    t1.setTitle("Safe task");

    TaskEntity t2 = new TaskEntity();
    t2.setId(task2);
    t2.setTitle("Flagged task");

    when(taskRepo.findAllById(any())).thenReturn(List.of(t1, t2));

    Instant start = Instant.now().minusSeconds(86400);
    Instant end = Instant.now();

    AiModerationReportResponse response = reportService.getAiModerationReport(start, end);

    assertNotNull(response);
    assertEquals(2, response.totalTasksEvaluated());
    assertEquals(1, response.autoApprovedCount());
    assertEquals(50.0, response.autoApprovalRatePercentage());
    assertEquals(1, response.adminReviewCount());
    assertEquals(50.0, response.adminReviewRatePercentage());
    assertEquals(120.0, response.avgLatencyMs());
    assertTrue(response.riskCategoryBreakdown().containsKey("CONTACT_LEAK"));
    assertEquals(1, response.riskCategoryBreakdown().get("CONTACT_LEAK"));
  }
}
