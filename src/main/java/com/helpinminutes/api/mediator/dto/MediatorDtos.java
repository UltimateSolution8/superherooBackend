package com.helpinminutes.api.mediator.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MediatorDtos {
  public record MediatorJobResponse(
      UUID batchId,
      UUID buyerId,
      String buyerName,
      String buyerPhone,
      String title,
      String notes,
      String status,
      int requestedHelperCount,
      int addedWorkerCount,
      Instant createdAt,
      Instant scheduledDispatchAt,
      Instant mediatorAcceptedAt,
      String mediatorNotes,
      Long mediatorCommissionPaise
  ) {}

  public record AcceptJobRequest(
      Instant scheduledDispatchAt,
      String notes
  ) {}

  public record AddWorkersRequest(
      @NotEmpty @Size(max = 500) List<@Pattern(regexp = "^(?:\\+91|91|0)?[6-9]\\d{9}$", message = "Invalid Indian mobile number") String> phones
  ) {}

  public record WorkerResult(
      String phone,
      boolean success,
      String error
  ) {}

  public record AddWorkersResponse(
      int totalProcessed,
      int successCount,
      int failureCount,
      List<WorkerResult> results
  ) {}

  public record AttendanceRequest(
      Map<UUID, Boolean> attendance
  ) {}

  public record WorkerPaymentDetail(
      UUID helperId,
      String helperName,
      String helperPhone,
      String attendanceStatus,
      String paymentStatus,
      Long paymentAmountPaise
  ) {}

  public record PaymentBreakdownResponse(
      long totalJobValuePaise,
      long totalHelperPayoutPaise,
      long mediatorCommissionPaise,
      long companySharePaise,
      List<WorkerPaymentDetail> workerPayments
  ) {}

  public record MediatorDashboardResponse(
      long pendingJobsCount,
      long acceptedJobsCount,
      long inProgressJobsCount,
      long completedJobsCount,
      long totalEarningsPaise
  ) {}

  public record MediatorWorkerDetail(
      UUID helperId,
      String name,
      String phone,
      String attendanceStatus,
      UUID taskId,
      String taskStatus
  ) {}
}
