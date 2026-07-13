package com.helpinminutes.api.mediator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.batches.model.BookingBatchEntity;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.common.InputValidators;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ConflictException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import java.util.Optional;
import com.helpinminutes.api.mediator.dto.MediatorDtos.*;
import com.helpinminutes.api.mediator.model.MediatorAttendanceStatus;
import com.helpinminutes.api.mediator.model.MediatorJobWorkerEntity;
import com.helpinminutes.api.mediator.repo.MediatorJobWorkerRepository;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.dto.CreateTaskRequest;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.service.TaskService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediatorService {
  private final BookingBatchRepository batches;
  private final MediatorJobWorkerRepository workers;
  private final UserRepository users;
  private final HelperProfileRepository helperProfiles;
  private final TaskService taskService;
  private final TaskRepository taskRepo;
  private final ObjectMapper objectMapper;
  private final RealtimePublisher realtime;

  public MediatorService(
      BookingBatchRepository batches,
      MediatorJobWorkerRepository workers,
      UserRepository users,
      HelperProfileRepository helperProfiles,
      TaskService taskService,
      TaskRepository taskRepo,
      ObjectMapper objectMapper,
      RealtimePublisher realtime) {
    this.batches = batches;
    this.workers = workers;
    this.users = users;
    this.helperProfiles = helperProfiles;
    this.taskService = taskService;
    this.taskRepo = taskRepo;
    this.objectMapper = objectMapper;
    this.realtime = realtime;
  }

  @Transactional(readOnly = true)
  public List<MediatorJobResponse> listAvailableJobs() {
    List<BookingBatchEntity> list = batches.findByStatus(BookingBatchStatus.PENDING_MEDIATOR);
    List<MediatorJobResponse> res = new ArrayList<>();
    for (BookingBatchEntity batch : list) {
      res.add(toJobResponse(batch));
    }
    return res;
  }

  @Transactional(readOnly = true)
  public List<MediatorJobResponse> listMyJobs(UUID mediatorId, String statusFilter) {
    List<BookingBatchEntity> list;
    if (statusFilter != null && !statusFilter.isBlank()) {
      try {
        BookingBatchStatus status = BookingBatchStatus.valueOf(statusFilter.trim().toUpperCase());
        list = batches.findByMediatorIdAndStatus(mediatorId, status);
      } catch (IllegalArgumentException e) {
        list = batches.findByMediatorId(mediatorId);
      }
    } else {
      list = batches.findByMediatorId(mediatorId);
    }
    List<MediatorJobResponse> res = new ArrayList<>();
    for (BookingBatchEntity batch : list) {
      res.add(toJobResponse(batch));
    }
    return res;
  }

  @Transactional(readOnly = true)
  public MediatorJobResponse getJob(UUID userId, UserRole role, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    validateOwnership(batch, userId, role);
    return toJobResponse(batch);
  }

  @Transactional
  public MediatorJobResponse acceptJob(UUID mediatorId, UUID batchId, AcceptJobRequest req) {
    BookingBatchEntity batch = batches.findAndLockById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    if (batch.getStatus() != BookingBatchStatus.PENDING_MEDIATOR) {
      throw new ConflictException("Job is not available for acceptance (current status: " + batch.getStatus() + ")");
    }
    UserEntity mediator = users.findById(mediatorId)
        .orElseThrow(() -> new NotFoundException("Mediator user not found"));
    if (mediator.getRole() != UserRole.MEDIATOR) {
      throw new BadRequestException("Only users with role MEDIATOR can accept bulk jobs");
    }

    batch.setMediatorId(mediatorId);
    batch.setStatus(BookingBatchStatus.MEDIATOR_ACCEPTED);
    batch.setMediatorAcceptedAt(Instant.now());
    if (req != null) {
      batch.setScheduledDispatchAt(req.scheduledDispatchAt());
      batch.setMediatorNotes(req.notes());
    }
    batches.save(batch);

    try {
      realtime.publish("mediator.job_accepted", Map.of(
          "batchId", batchId.toString(),
          "mediatorId", mediatorId.toString(),
          "buyerId", batch.getCreatedByUserId().toString()
      ));
    } catch (Exception ignored) {}

    return toJobResponse(batch);
  }

  @Transactional
  public AddWorkersResponse addWorkers(UUID userId, UserRole role, UUID batchId, AddWorkersRequest req) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    validateOwnership(batch, userId, role);
    if (batch.getStatus() != BookingBatchStatus.MEDIATOR_ACCEPTED) {
      throw new ConflictException("Cannot add workers in status: " + batch.getStatus());
    }

    List<String> requestedPhones = req == null || req.phones() == null ? List.of() : req.phones();
    if (requestedPhones.isEmpty()) {
      throw new BadRequestException("At least one helper phone is required");
    }

    List<WorkerResult> results = new ArrayList<>();
    int successCount = 0;
    int failureCount = 0;
    int existingWorkerCount = workers.findByBatchId(batchId).size();
    int requestedCount = Math.max(1, batch.getRequestedHelperCount() == null ? 1 : batch.getRequestedHelperCount());
    java.util.Set<String> seenPhones = new java.util.HashSet<>();

    for (String phone : requestedPhones) {
      try {
        String normalized = InputValidators.normalizeIndianPhoneOrNull(phone);
        if (!seenPhones.add(normalized)) {
          results.add(new WorkerResult(phone, false, "Duplicate helper phone in this request"));
          failureCount++;
          continue;
        }
        if (existingWorkerCount >= requestedCount) {
          results.add(new WorkerResult(phone, false, "Requested helper count is already full"));
          failureCount++;
          continue;
        }

        Optional<UserEntity> helperOpt = users.findByPhoneAndRole(normalized, UserRole.HELPER);
        if (helperOpt.isEmpty()) {
          results.add(new WorkerResult(phone, false, "Helper not found"));
          failureCount++;
          continue;
        }
        UserEntity helper = helperOpt.get();

        Optional<HelperProfileEntity> profileOpt = helperProfiles.findById(helper.getId());
        if (profileOpt.isEmpty()) {
          results.add(new WorkerResult(phone, false, "Helper profile not found"));
          failureCount++;
          continue;
        }
        HelperProfileEntity profile = profileOpt.get();

        if (profile.getKycStatus() != HelperKycStatus.APPROVED) {
          results.add(new WorkerResult(phone, false, "Helper KYC is not approved"));
          failureCount++;
          continue;
        }

        var existing = workers.findByBatchIdAndHelperId(batchId, helper.getId());
        if (existing.isPresent()) {
          results.add(new WorkerResult(phone, true, "Helper already added to this job"));
          successCount++;
          continue;
        }

        MediatorJobWorkerEntity worker = new MediatorJobWorkerEntity();
        worker.setBatchId(batchId);
        worker.setHelperId(helper.getId());
        workers.save(worker);
        existingWorkerCount++;

        results.add(new WorkerResult(phone, true, null));
        successCount++;
      } catch (BadRequestException e) {
        results.add(new WorkerResult(phone, false, e.getMessage()));
        failureCount++;
      } catch (Exception e) {
        results.add(new WorkerResult(phone, false, "Could not add helper"));
        failureCount++;
      }
    }

    return new AddWorkersResponse(requestedPhones.size(), successCount, failureCount, results);
  }

  @Transactional
  public void removeWorker(UUID userId, UserRole role, UUID batchId, UUID helperId) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    validateOwnership(batch, userId, role);
    if (batch.getStatus() != BookingBatchStatus.MEDIATOR_ACCEPTED) {
      throw new ConflictException("Cannot remove workers in status: " + batch.getStatus());
    }
    workers.deleteByBatchIdAndHelperId(batchId, helperId);
  }

  @Transactional(readOnly = true)
  public List<MediatorWorkerDetail> getWorkers(UUID userId, UserRole role, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    validateOwnership(batch, userId, role);
    List<MediatorJobWorkerEntity> list = workers.findByBatchId(batchId);
    List<MediatorWorkerDetail> res = new ArrayList<>();
    for (MediatorJobWorkerEntity item : list) {
      UserEntity helper = users.findById(item.getHelperId()).orElse(null);
      String name = helper != null ? helper.getDisplayName() : "Unknown";
      String phone = helper != null ? helper.getPhone() : "";
      String taskStatus = null;
      if (item.getTaskId() != null) {
        taskStatus = taskRepo.findById(item.getTaskId()).map(t -> t.getStatus().name()).orElse(null);
      }
      res.add(new MediatorWorkerDetail(
          item.getHelperId(),
          name,
          phone,
          item.getAttendanceStatus().name(),
          item.getTaskId(),
          taskStatus
      ));
    }
    return res;
  }

  @Transactional
  public void dispatchJob(UUID userId, UserRole role, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    validateOwnership(batch, userId, role);
    if (batch.getStatus() != BookingBatchStatus.MEDIATOR_ACCEPTED) {
      throw new ConflictException("Cannot dispatch job in status: " + batch.getStatus());
    }

    List<MediatorJobWorkerEntity> jobWorkers = workers.findByBatchId(batchId);
    if (jobWorkers.isEmpty()) {
      throw new BadRequestException("At least one helper must be added before dispatching");
    }
    int requestedCount = Math.max(1, batch.getRequestedHelperCount() == null ? 1 : batch.getRequestedHelperCount());
    if (jobWorkers.size() < requestedCount) {
      throw new BadRequestException("Add all requested helpers before dispatching");
    }
    Instant earliestDispatchAt = batch.getScheduledDispatchAt();
    if (earliestDispatchAt == null && batch.getScheduledWindowStart() != null) {
      earliestDispatchAt = batch.getScheduledWindowStart().minus(Duration.ofMinutes(15));
    }
    if (earliestDispatchAt != null && earliestDispatchAt.isAfter(Instant.now())) {
      throw new BadRequestException("This scheduled job can be dispatched closer to the service time");
    }

    CreateTaskRequest template;
    try {
      template = objectMapper.readValue(batch.getTaskTemplateJson(), CreateTaskRequest.class);
    } catch (Exception e) {
      throw new BadRequestException("Failed to deserialize task template metadata");
    }

    batch.setStatus(BookingBatchStatus.MEDIATOR_DISPATCHING);
    batches.save(batch);

    int successCount = 0;
    List<String> dispatchFailures = new ArrayList<>();
    for (MediatorJobWorkerEntity w : jobWorkers) {
      try {
        TaskEntity task = taskService.createTaskForHelper(batch.getCreatedByUserId(), w.getHelperId(), template);
        w.setTaskId(task.getId());
        workers.save(w);
        successCount++;
      } catch (Exception e) {
        dispatchFailures.add(w.getHelperId() + ": " + e.getMessage());
      }
    }

    if (!dispatchFailures.isEmpty() || successCount != jobWorkers.size()) {
      batch.setStatus(BookingBatchStatus.MEDIATOR_ACCEPTED);
      batches.save(batch);
      throw new BadRequestException("Could not dispatch every helper. Please remove busy helpers and try again.");
    }

    batch.setStatus(BookingBatchStatus.MEDIATOR_IN_PROGRESS);
    batches.save(batch);

    try {
      realtime.publish("mediator.job_dispatched", Map.of(
          "batchId", batchId.toString(),
          "mediatorId", batch.getMediatorId().toString(),
          "buyerId", batch.getCreatedByUserId().toString()
      ));
    } catch (Exception ignored) {}
  }

  @Transactional
  public void submitAttendance(UUID userId, UserRole role, UUID batchId, AttendanceRequest req) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    validateOwnership(batch, userId, role);
    if (batch.getStatus() != BookingBatchStatus.MEDIATOR_IN_PROGRESS) {
      throw new ConflictException("Cannot submit attendance in status: " + batch.getStatus());
    }

    for (Map.Entry<UUID, Boolean> entry : req.attendance().entrySet()) {
      workers.findByBatchIdAndHelperId(batchId, entry.getKey()).ifPresent(w -> {
        w.setAttendanceStatus(entry.getValue() ? MediatorAttendanceStatus.PRESENT : MediatorAttendanceStatus.ABSENT);
        w.setAttendanceMarkedAt(Instant.now());
        workers.save(w);
      });
    }

    try {
      realtime.publish("mediator.attendance_update", Map.of(
          "batchId", batchId.toString(),
          "mediatorId", batch.getMediatorId().toString()
      ));
    } catch (Exception ignored) {}
  }

  @Transactional
  public void completeJob(UUID userId, UserRole role, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    validateOwnership(batch, userId, role);
    if (batch.getStatus() != BookingBatchStatus.MEDIATOR_IN_PROGRESS) {
      throw new ConflictException("Cannot complete job in status: " + batch.getStatus());
    }

    List<MediatorJobWorkerEntity> jobWorkers = workers.findByBatchId(batchId);
    CreateTaskRequest template;
    try {
      template = objectMapper.readValue(batch.getTaskTemplateJson(), CreateTaskRequest.class);
    } catch (Exception e) {
      throw new BadRequestException("Failed to deserialize task template metadata");
    }

    long helperWage = template.budgetPaise();
    long totalHelperPayout = 0L;

    for (MediatorJobWorkerEntity w : jobWorkers) {
      if (w.getAttendanceStatus() == MediatorAttendanceStatus.PRESENT) {
        w.setPaymentStatus("PAID");
        w.setPaymentAmountPaise(helperWage);
        totalHelperPayout += helperWage;

        // Transition underlying task to COMPLETED if active
        if (w.getTaskId() != null) {
          taskRepo.findById(w.getTaskId()).ifPresent(t -> {
            if (t.getStatus() != TaskStatus.COMPLETED && t.getStatus() != TaskStatus.CANCELLED) {
              t.setStatus(TaskStatus.COMPLETED);
              taskRepo.save(t);
            }
          });
        }
      } else {
        w.setPaymentStatus("SKIPPED");
        w.setPaymentAmountPaise(0L);

        // Cancel task if helper was absent
        if (w.getTaskId() != null) {
          taskRepo.findById(w.getTaskId()).ifPresent(t -> {
            if (t.getStatus() != TaskStatus.COMPLETED && t.getStatus() != TaskStatus.CANCELLED) {
              t.setStatus(TaskStatus.CANCELLED);
              t.setCancelReason("Absent from mediator bulk booking");
              taskRepo.save(t);
            }
          });
        }
      }
      workers.save(w);
    }

    // Default calculations if not explicitly set
    long mediatorCommission = batch.getMediatorCommissionPaise() != null
        ? batch.getMediatorCommissionPaise()
        : Math.round(totalHelperPayout * 0.10); // 10%

    batch.setMediatorCommissionPaise(mediatorCommission);
    batch.setStatus(BookingBatchStatus.MEDIATOR_COMPLETED);
    batches.save(batch);

    try {
      realtime.publish("mediator.job_completed", Map.of(
          "batchId", batchId.toString(),
          "mediatorId", batch.getMediatorId().toString(),
          "buyerId", batch.getCreatedByUserId().toString()
      ));
    } catch (Exception ignored) {}
  }

  @Transactional(readOnly = true)
  public PaymentBreakdownResponse getPaymentBreakdown(UUID userId, UserRole role, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    validateOwnership(batch, userId, role);
    if (batch.getStatus() != BookingBatchStatus.MEDIATOR_COMPLETED) {
      throw new BadRequestException("Payment breakdown is only available for completed jobs");
    }

    List<MediatorJobWorkerEntity> jobWorkers = workers.findByBatchId(batchId);
    List<WorkerPaymentDetail> list = new ArrayList<>();
    long totalHelperPayout = 0;

    for (MediatorJobWorkerEntity w : jobWorkers) {
      UserEntity helper = users.findById(w.getHelperId()).orElse(null);
      String name = helper != null ? helper.getDisplayName() : "Unknown";
      String phone = helper != null ? helper.getPhone() : "";

      long amt = w.getPaymentAmountPaise() != null ? w.getPaymentAmountPaise() : 0L;
      totalHelperPayout += amt;

      list.add(new WorkerPaymentDetail(
          w.getHelperId(),
          name,
          phone,
          w.getAttendanceStatus().name(),
          w.getPaymentStatus(),
          amt
      ));
    }

    long mediatorCommission = batch.getMediatorCommissionPaise() != null ? batch.getMediatorCommissionPaise() : 0L;
    long companyShare = Math.round(totalHelperPayout * 0.05);
    long totalJobValue = totalHelperPayout + mediatorCommission + companyShare;

    return new PaymentBreakdownResponse(
        totalJobValue,
        totalHelperPayout,
        mediatorCommission,
        companyShare,
        list
    );
  }

  @Transactional(readOnly = true)
  public MediatorDashboardResponse getDashboard(UUID mediatorId) {
    long pending = batches.countByStatus(BookingBatchStatus.PENDING_MEDIATOR);
    long accepted = batches.countByMediatorIdAndStatus(mediatorId, BookingBatchStatus.MEDIATOR_ACCEPTED);
    long inProgress = batches.countByMediatorIdAndStatus(mediatorId, BookingBatchStatus.MEDIATOR_IN_PROGRESS);
    long completed = batches.countByMediatorIdAndStatus(mediatorId, BookingBatchStatus.MEDIATOR_COMPLETED);

    List<BookingBatchEntity> completedBatches = batches.findByMediatorIdAndStatus(mediatorId, BookingBatchStatus.MEDIATOR_COMPLETED);
    long totalEarnings = 0;
    for (BookingBatchEntity batch : completedBatches) {
      if (batch.getMediatorCommissionPaise() != null) {
        totalEarnings += batch.getMediatorCommissionPaise();
      }
    }

    return new MediatorDashboardResponse(pending, accepted, inProgress, completed, totalEarnings);
  }

  private MediatorJobResponse toJobResponse(BookingBatchEntity batch) {
    UserEntity buyer = users.findById(batch.getCreatedByUserId()).orElse(null);
    String buyerName = buyer != null ? buyer.getDisplayName() : "Unknown";
    String buyerPhone = buyer != null ? buyer.getPhone() : "";
    int workerCount = workers.findByBatchId(batch.getId()).size();

    return new MediatorJobResponse(
        batch.getId(),
        batch.getCreatedByUserId(),
        buyerName,
        buyerPhone,
        batch.getTitle(),
        batch.getNotes(),
        batch.getStatus().name(),
        batch.getRequestedHelperCount() != null ? batch.getRequestedHelperCount() : 0,
        workerCount,
        batch.getCreatedAt(),
        batch.getScheduledDispatchAt(),
        batch.getMediatorAcceptedAt(),
        batch.getMediatorNotes(),
        batch.getMediatorCommissionPaise()
    );
  }

  private void validateOwnership(BookingBatchEntity batch, UUID userId, UserRole role) {
    if (role == UserRole.ADMIN) {
      return; // Admins have full access to all batches
    }
    if (batch.getMediatorId() == null) {
      if (batch.getStatus() != BookingBatchStatus.PENDING_MEDIATOR) {
        throw new ForbiddenException("This job is not assigned to you");
      }
    } else if (!batch.getMediatorId().equals(userId)) {
      throw new ForbiddenException("This job is assigned to another mediator");
    }
  }
}
