package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.common.ServiceArea;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ConflictException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.tasks.dto.CreateTaskRequest;
import com.helpinminutes.api.tasks.dto.TaskRatingRequest;
import com.helpinminutes.api.tasks.dto.TaskResponse;
import com.helpinminutes.api.tasks.model.TaskEscrowStatus;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import com.helpinminutes.api.tasks.model.TaskSelfieStage;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.helpinminutes.api.tasks.model.RecurringTaskEntity;
import com.helpinminutes.api.tasks.model.RecurringTaskStatus;
import com.helpinminutes.api.tasks.repo.RecurringTaskRepository;
import com.helpinminutes.api.tasks.dto.CreateRecurringTaskRequest;
import com.helpinminutes.api.tasks.dto.CreateRecurringTaskResponse;
import com.helpinminutes.api.tasks.dto.RecurringTaskResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.batches.repo.BookingBatchItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TaskService {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskService.class);
  private static final java.util.List<TaskStatus> HELPER_ACTIVE_TASK_STATUSES = java.util.List.of(
      TaskStatus.ASSIGNED,
      TaskStatus.ARRIVED,
      TaskStatus.STARTED);
  private final TaskRepository tasks;
  private final TaskOfferRepository offers;
  private final MatchingService matching;
  private final RealtimePublisher realtime;
  private final SupabaseStorageService storage;
  private final HelperPresenceService presence;
  private final AppProperties props;
  private final UserRepository users;
  private final HelperProfileRepository helperProfiles;
  private final NotificationQueueService notificationQueue;
  private final PushNotificationService pushNotifications;
  private final TaskMapper taskMapper;
  private final RecurringTaskRepository recurringTasks;
  private final TaskModerationService taskModerationService;
  private final BookingBatchRepository bookingBatches;
  private final BookingBatchItemRepository bookingBatchItems;
  private final ObjectMapper objectMapper;
  private final InvoiceEmailService invoiceEmail;

  public TaskService(
      TaskRepository tasks,
      TaskOfferRepository offers,
      MatchingService matching,
      RealtimePublisher realtime,
      SupabaseStorageService storage,
      HelperPresenceService presence,
      AppProperties props,
      UserRepository users,
      HelperProfileRepository helperProfiles,
      NotificationQueueService notificationQueue,
      PushNotificationService pushNotifications,
      TaskMapper taskMapper,
      RecurringTaskRepository recurringTasks,
      TaskModerationService taskModerationService,
      BookingBatchRepository bookingBatches,
      BookingBatchItemRepository bookingBatchItems,
      ObjectMapper objectMapper,
      InvoiceEmailService invoiceEmail) {
    this.tasks = tasks;
    this.offers = offers;
    this.matching = matching;
    this.realtime = realtime;
    this.storage = storage;
    this.presence = presence;
    this.props = props;
    this.users = users;
    this.helperProfiles = helperProfiles;
    this.notificationQueue = notificationQueue;
    this.pushNotifications = pushNotifications;
    this.taskMapper = taskMapper;
    this.recurringTasks = recurringTasks;
    this.taskModerationService = taskModerationService;
    this.bookingBatches = bookingBatches;
    this.bookingBatchItems = bookingBatchItems;
    this.objectMapper = objectMapper;
    this.invoiceEmail = invoiceEmail;
  }

  @Transactional
  public CreateRecurringTaskResponse createRecurringTask(UUID buyerId, CreateRecurringTaskRequest req) {
    taskModerationService.validateTask(req.title(), req.description());
    if (!ServiceArea.isWithinHyderabad(req.lat(), req.lng())) {
      throw new BadRequestException("Service is currently live only in Hyderabad");
    }

    LocalTime time;
    try {
      String ts = req.timeSlot().trim();
      time = LocalTime.parse(ts);
    } catch (Exception e) {
      throw new BadRequestException("Invalid time slot format. Must be HH:mm or HH:mm:ss");
    }

    if (req.startDate().isAfter(req.endDate())) {
      throw new BadRequestException("Start date cannot be after end date");
    }

    String tz = req.timezone() != null ? req.timezone().trim() : "Asia/Kolkata";
    try {
      ZoneId.of(tz);
    } catch (Exception e) {
      throw new BadRequestException("Invalid timezone string: " + tz);
    }

    validateRecurringRequest(req);

    RecurringTaskEntity rec = new RecurringTaskEntity();
    rec.setId(UUID.randomUUID());
    rec.setBuyerId(buyerId);
    rec.setTitle(req.title().trim());
    rec.setDescription(req.description().trim());
    rec.setUrgency(req.urgency());
    rec.setTimeMinutes(req.timeMinutes());
    rec.setBudgetPaise(req.budgetPaise());
    rec.setLat(req.lat());
    rec.setLng(req.lng());
    rec.setAddressText(req.addressText() != null ? req.addressText().trim() : null);
    rec.setFrequency(req.frequency().trim().toUpperCase());
    rec.setStartDate(req.startDate());
    rec.setEndDate(req.endDate());
    rec.setTimeSlot(req.timeSlot().trim());
    rec.setRecurrenceInterval(req.recurrenceInterval() != null ? req.recurrenceInterval() : 1);
    rec.setByDay(req.byDay());
    rec.setByMonthDay(req.byMonthDay());
    rec.setTimezone(tz);
    rec.setHelperCount(req.helperCount() != null ? req.helperCount() : 1);
    rec.setCreatedAt(Instant.now());

    var firstOccurrence = RecurrenceCalculator.nextOccurrence(rec, Instant.now());
    if (firstOccurrence.isEmpty()) {
      throw new BadRequestException("Recurring schedule does not create any future occurrence");
    }
    if (firstOccurrence.get().toInstant().isBefore(Instant.now().plus(java.time.Duration.ofMinutes(5)))) {
      throw new BadRequestException("First recurring occurrence must be at least 5 minutes in the future");
    }

    recurringTasks.save(rec);

    Instant now = Instant.now();
    Instant lookaheadHorizon = now.plus(7, java.time.temporal.ChronoUnit.DAYS);
    List<UUID> createdTaskIds = new ArrayList<>();

    List<ZonedDateTime> occurrences = RecurrenceCalculator.nextNOccurrences(rec, now, 20);
    for (ZonedDateTime zdt : occurrences) {
      Instant scheduledAt = zdt.toInstant();
      if (scheduledAt.isAfter(lookaheadHorizon)) {
        break;
      }
      spawnOccurrence(rec, scheduledAt, createdTaskIds);
    }

    return new CreateRecurringTaskResponse(rec.getId(), createdTaskIds);
  }

  @Transactional
  public List<UUID> spawnOccurrence(UUID recurringTaskId, Instant scheduledAt) {
    RecurringTaskEntity rec = recurringTasks.findById(recurringTaskId)
        .orElseThrow(() -> new NotFoundException("Recurring task config not found"));
    List<UUID> created = new ArrayList<>();
    spawnOccurrence(rec, scheduledAt, created);
    return created;
  }

  private void spawnOccurrence(RecurringTaskEntity rec, Instant scheduledAt, List<UUID> createdTaskIds) {
    if (rec.getHelperCount() == null || rec.getHelperCount() <= 1) {
      var single = createTask(rec.getBuyerId(), new CreateTaskRequest(
          rec.getTitle(),
          rec.getDescription(),
          rec.getUrgency(),
          rec.getTimeMinutes(),
          rec.getBudgetPaise(),
          rec.getLat(),
          rec.getLng(),
          rec.getAddressText(),
          scheduledAt,
          null
      ), TaskCreateOptions.defaultOptions());
      createdTaskIds.add(single.taskId());

      tasks.findById(single.taskId()).ifPresent(t -> {
        t.setRecurringTaskId(rec.getId());
        tasks.save(t);
      });
    } else {
      // Bulk crew occurrences are represented as one mediator-managed batch.
      // This avoids showing duplicate copies of the same request to partners.
      if (bookingBatches.existsBySourceRecurringTaskIdAndScheduledWindowStartAndStatusNot(
          rec.getId(), scheduledAt, BookingBatchStatus.CANCELLED)) {
        return;
      }
      com.helpinminutes.api.batches.model.BookingBatchEntity batch = new com.helpinminutes.api.batches.model.BookingBatchEntity();
      batch.setId(UUID.randomUUID());
      batch.setCreatedByUserId(rec.getBuyerId());
      batch.setTitle(rec.getTitle() + " (Bulk x" + rec.getHelperCount() + ")");
      batch.setNotes("Created from recurring bulk task config (Mediator Routed): " + rec.getId());
      batch.setSourceRecurringTaskId(rec.getId());
      batch.setScheduledWindowStart(scheduledAt);
      batch.setScheduledWindowEnd(scheduledAt.plus(java.time.Duration.ofMinutes(rec.getTimeMinutes())));
      batch.setStatus(com.helpinminutes.api.batches.model.BookingBatchStatus.PENDING_AUDIT);
      batch.setRequestedHelperCount(rec.getHelperCount());
      batch.setBatchStartOtp(generateOtp());
      batch.setBatchCompletionOtp(generateOtp());

      try {
        com.helpinminutes.api.tasks.dto.CreateTaskRequest template = new com.helpinminutes.api.tasks.dto.CreateTaskRequest(
            rec.getTitle(),
            rec.getDescription(),
            rec.getUrgency(),
            rec.getTimeMinutes(),
            rec.getBudgetPaise(),
            rec.getLat(),
            rec.getLng(),
            rec.getAddressText(),
            scheduledAt,
            null
        );
        batch.setTaskTemplateJson(objectMapper.writeValueAsString(template));
      } catch (Exception e) {
        throw new RuntimeException("Failed to serialize task template metadata", e);
      }

      bookingBatches.save(batch);

      try {
        realtime.publish("mediator.job_pending_audit", java.util.Map.of(
            "batchId", batch.getId().toString(),
            "buyerId", rec.getBuyerId().toString(),
            "helperCount", rec.getHelperCount()
        ));
      } catch (Exception ignored) {}
    }
  }

  @Transactional
  public CreateResult createTask(UUID buyerId, CreateTaskRequest req) {
    return createTask(buyerId, req, TaskCreateOptions.defaultOptions());
  }

  @Transactional
  public CreateResult createTask(UUID buyerId, CreateTaskRequest req, TaskCreateOptions options) {
    taskModerationService.validateTask(req.title(), req.description());
    TaskCreateOptions resolvedOptions = options == null ? TaskCreateOptions.defaultOptions() : options;
    UserEntity buyer = users.findById(buyerId)
        .orElseThrow(() -> new ForbiddenException("Buyer not found"));

    if (!ServiceArea.isWithinHyderabad(req.lat(), req.lng())) {
      throw new BadRequestException("Service is currently live only in Hyderabad");
    }

    long cost = req.budgetPaise() == null ? 0L : Math.max(0L, req.budgetPaise());
    Long balance = buyer.getDemoBalancePaise();
    long current = balance == null ? 1_000_000L : balance;
    if (balance == null) {
      buyer.setDemoBalancePaise(current);
      users.save(buyer);
    }
    // Demo balance bypass: since we are on Cash / UPI, wallet balance should not block task booking
    /*
    if (cost > current) {
      throw new BadRequestException("Insufficient demo balance for escrow");
    }
    buyer.setDemoBalancePaise(current - cost);
    users.save(buyer);
    */

    TaskEntity task = new TaskEntity();
    task.setBuyerId(buyerId);
    task.setTitle(req.title().trim());
    task.setDescription(req.description().trim());
    task.setUrgency(req.urgency());
    task.setTimeMinutes(req.timeMinutes());
    task.setBudgetPaise(req.budgetPaise());
    task.setLat(req.lat());
    task.setLng(req.lng());
    task.setAddressText(req.addressText());
    task.setLandmark(req.landmark());
    Instant now = Instant.now();
    Instant scheduledAt = req.scheduledAt();
    if (scheduledAt != null) {
      task.setScheduledAt(scheduledAt);
    }
    boolean isFutureScheduled = scheduledAt != null && scheduledAt.isAfter(now.plus(java.time.Duration.ofMinutes(1)));
    if (isFutureScheduled) {
      task.setStatus(TaskStatus.SCHEDULED_PENDING);
    } else {
      task.setStatus(TaskStatus.SEARCHING);
    }
    task.setEscrowStatus(TaskEscrowStatus.HELD);
    task.setEscrowAmountPaise(cost);
    task.setEscrowHeldAt(now);
    task.setArrivalOtp(generateOtp());
    task.setCompletionOtp(generateOtp());

    tasks.save(task);

    List<UUID> offeredTo = new ArrayList<>();
    if (!isFutureScheduled) {
      try {
        offeredTo = matching.dispatchOffers(task, resolvedOptions.sendOfferNotifications());
      } catch (Exception e) {
        log.error("Failed to dispatch offers for task {}", task.getId(), e);
      }
    } else {
      log.info("Task {} scheduled for {}. Skipping immediate dispatch.", task.getId(), scheduledAt);
    }

    try {
      java.util.concurrent.CompletableFuture.runAsync(() -> {
        try {
          realtime.publish(
              "task_created",
              java.util.Map.of(
                  "taskId", task.getId().toString(),
                  "buyerId", buyerId.toString(),
                  "title", task.getTitle(),
                  "urgency", task.getUrgency().name(),
                  "status", task.getStatus().name()));
        } catch (Exception ignored) {
        }
        try {
          pushNotifications.notifyTaskCreatedMonitor(task);
        } catch (Exception ignored) {
        }
      });
    } catch (Exception ignored) {
    }

    return new CreateResult(task.getId(), offeredTo);
  }

  @Transactional
  public TaskEntity createTaskForHelper(UUID buyerId, UUID helperId, CreateTaskRequest req) {
    taskModerationService.validateTask(req.title(), req.description());

    UserEntity helper = users.findById(helperId)
        .orElseThrow(() -> new BadRequestException("Helper not found"));
    if (helper.getRole() != UserRole.HELPER) {
      throw new BadRequestException("User is not a helper");
    }
    var profile = helperProfiles.findById(helperId)
        .orElseThrow(() -> new BadRequestException("Helper profile not found"));
    if (profile.getKycStatus() != HelperKycStatus.APPROVED) {
      throw new BadRequestException("Helper KYC is not approved");
    }

    if (tasks.existsByAssignedHelperIdAndStatusIn(helperId, HELPER_ACTIVE_TASK_STATUSES)) {
      throw new ConflictException("Helper already has an active task");
    }

    UserEntity buyer = users.findById(buyerId)
        .orElseThrow(() -> new ForbiddenException("Buyer not found"));

    long cost = req.budgetPaise() == null ? 0L : Math.max(0L, req.budgetPaise());

    TaskEntity task = new TaskEntity();
    task.setBuyerId(buyerId);
    task.setAssignedHelperId(helperId);
    task.setTitle(req.title().trim());
    task.setDescription(req.description().trim());
    task.setUrgency(req.urgency());
    task.setTimeMinutes(req.timeMinutes());
    task.setBudgetPaise(req.budgetPaise());
    task.setLat(req.lat());
    task.setLng(req.lng());
    task.setAddressText(req.addressText());
    task.setLandmark(req.landmark());
    Instant now = Instant.now();
    task.setScheduledAt(req.scheduledAt() != null ? req.scheduledAt() : now);
    task.setStatus(TaskStatus.ASSIGNED);
    task.setEscrowStatus(TaskEscrowStatus.HELD);
    task.setEscrowAmountPaise(cost);
    task.setEscrowHeldAt(now);
    task.setArrivalOtp(generateOtp());
    task.setCompletionOtp(generateOtp());

    tasks.save(task);

    // Publish realtime event
    try {
      java.util.concurrent.CompletableFuture.runAsync(() -> {
        try {
          realtime.publish(
              "task_assigned",
              java.util.Map.of(
                  "taskId", task.getId().toString(),
                  "buyerId", buyerId.toString(),
                  "helperId", helperId.toString(),
                  "title", task.getTitle(),
                  "status", task.getStatus().name()));
        } catch (Exception ignored) {
        }
        try {
          pushNotifications.notifyTaskOffered(java.util.List.of(helperId), task);
        } catch (Exception ignored) {
        }
      });
    } catch (Exception ignored) {
    }

    return task;
  }

  @Transactional
  public TaskResponse acceptTask(UUID helperId, UUID taskId) {
    users.findByIdForUpdate(helperId)
        .orElseThrow(() -> new ForbiddenException("Helper not found"));

    var profile = helperProfiles.findById(helperId)
        .orElseThrow(() -> new ForbiddenException("Helper profile not found"));
    if (profile.getKycStatus() != HelperKycStatus.APPROVED) {
      throw new ForbiddenException("KYC verification is required to accept tasks");
    }

    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    if (tasks.existsByAssignedHelperIdAndStatusIn(helperId, HELPER_ACTIVE_TASK_STATUSES)) {
      throw new ConflictException("Finish your current task before accepting another one");
    }

    Instant now = Instant.now();
    var offerOpt = offers.findByTaskIdAndHelperId(taskId, helperId);
    if (offerOpt.isPresent()) {
      var offer = offerOpt.get();
      if (offer.getExpiresAt().isBefore(now)) {
        throw new ConflictException("Offer expired");
      }

      int responded = offers.respond(taskId, helperId, TaskOfferStatus.OFFERED, TaskOfferStatus.ACCEPTED, now);
      if (responded == 0) {
        throw new ConflictException("Offer already responded");
      }

      int updated = tasks.assignIfUnassigned(taskId, helperId, TaskStatus.SEARCHING, TaskStatus.ASSIGNED);
      if (updated == 0) {
        throw new ConflictException("Task already assigned");
      }

      offers.expireOthers(taskId, TaskOfferStatus.OFFERED, TaskOfferStatus.EXPIRED, helperId);
      task.setAssignedHelperId(helperId);
      task.setStatus(TaskStatus.ASSIGNED);
    } else {
      var state = presence.getHelperState(helperId);
      if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) {
        throw new ForbiddenException("Helper location is not available");
      }

      double distMeters = GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng());
      if (distMeters > 3000d) {
        throw new ForbiddenException("Helper is too far from this task");
      }

      int updated = tasks.assignIfUnassigned(taskId, helperId, TaskStatus.SEARCHING, TaskStatus.ASSIGNED);
      if (updated == 0) {
        throw new ConflictException("Task already assigned");
      }

      TaskOfferEntity offer = new TaskOfferEntity();
      offer.setTaskId(taskId);
      offer.setHelperId(helperId);
      offer.setStatus(TaskOfferStatus.ACCEPTED);
      offer.setOfferedAt(now);
      offer.setExpiresAt(now);
      offer.setRespondedAt(now);
      offers.save(offer);

      task.setAssignedHelperId(helperId);
      task.setStatus(TaskStatus.ASSIGNED);
    }

    realtime.publish(
        "task_assigned",
        java.util.Map.of(
            "taskId", taskId.toString(),
            "buyerId", task.getBuyerId().toString(),
            "helperId", helperId.toString(),
            "status", TaskStatus.ASSIGNED.name()));

    notificationQueue.enqueueTaskAccepted(task.getBuyerId(), task);

    return taskMapper.toResponse(task, false);
  }

  @Transactional
  public TaskResponse updateStatusAsHelper(UUID helperId, UUID taskId, TaskStatus newStatus, String otp) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    if (task.getAssignedHelperId() == null || !task.getAssignedHelperId().equals(helperId)) {
      throw new ForbiddenException("Not assigned to this task");
    }

    TaskStatus current = task.getStatus();
    if (!isValidHelperTransition(current, newStatus)) {
      throw new BadRequestException("Invalid status transition: " + current + " -> " + newStatus);
    }

    if (newStatus == TaskStatus.ARRIVED && task.getArrivalSelfieUrl() == null) {
      throw new BadRequestException("Arrival selfie is required before marking ARRIVED");
    }
    if (newStatus == TaskStatus.STARTED) {
      String expected = task.getArrivalOtp();
      if (expected != null && !expected.isBlank()) {
        if (otp == null || otp.isBlank()) {
          throw new BadRequestException("Arrival OTP is required to start work");
        }
        if (!expected.equals(otp.trim())) {
          throw new BadRequestException("Incorrect OTP");
        }
      }
    }
    if (newStatus == TaskStatus.COMPLETED && task.getCompletionSelfieUrl() == null) {
      throw new BadRequestException("Completion selfie is required before marking COMPLETED");
    }
    if (newStatus == TaskStatus.COMPLETED) {
      String expected = task.getCompletionOtp();
      if (expected != null && !expected.isBlank()) {
        if (otp == null || otp.isBlank()) {
          throw new BadRequestException("Completion OTP is required to finish work");
        }
        if (!expected.equals(otp.trim())) {
          throw new BadRequestException("Incorrect OTP");
        }
      }
    }

    task.setStatus(newStatus);
    if (newStatus == TaskStatus.STARTED && task.getWorkStartedAt() == null) {
      task.setWorkStartedAt(Instant.now());
    }

    if (newStatus == TaskStatus.COMPLETED && task.getEscrowAmountPaise() != null && task.getEscrowAmountPaise() > 0) {
      if (task.getEscrowStatus() == TaskEscrowStatus.HELD) {
        task.setEscrowStatus(TaskEscrowStatus.RELEASE_SCHEDULED);
        task.setEscrowReleaseAt(Instant.now().plusSeconds(300));
        task.setEscrowReleasedToHelperId(helperId);
      }
    }
    tasks.save(task);

    if (newStatus == TaskStatus.COMPLETED && task.getEscrowStatus() == TaskEscrowStatus.RELEASE_SCHEDULED) {
      scheduleEscrowRelease(task.getId(), helperId);
    }

    realtime.publish(
        "task_status_changed",
        java.util.Map.of(
            "taskId", taskId.toString(),
            "buyerId", task.getBuyerId().toString(),
            "helperId", helperId.toString(),
            "status", newStatus.name()));

    if (newStatus == TaskStatus.ARRIVED) {
      try {
        pushNotifications.notifyBuyerHelperArrived(task.getBuyerId(), task);
      } catch (Exception e) {
        log.warn("Failed to send helper arrived notification for task {}", task.getId(), e);
      }
    }

    if (newStatus == TaskStatus.COMPLETED) {
      notificationQueue.enqueueTaskCompleted(task.getBuyerId(), task);
      invoiceEmail.sendInvoiceEmailAsync(task);
    }

    return taskMapper.toResponse(task, false);
  }

  @Transactional
  public TaskResponse rateTask(UUID userId, UserRole role, UUID taskId, TaskRatingRequest req) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    BigDecimal rating = req.rating();
    if (rating == null) {
      throw new BadRequestException("Rating is required");
    }

    if (role == UserRole.BUYER) {
      if (!userId.equals(task.getBuyerId())) {
        throw new ForbiddenException("Only the buyer can rate the helper");
      }
      task.setBuyerRating(rating);
      task.setBuyerRatingComment(req.comment());
      task.setBuyerRatedAt(Instant.now());
    } else if (role == UserRole.HELPER) {
      if (task.getAssignedHelperId() == null || !userId.equals(task.getAssignedHelperId())) {
        throw new ForbiddenException("Only the assigned helper can rate the buyer");
      }
      task.setHelperRating(rating);
      task.setHelperRatingComment(req.comment());
      task.setHelperRatedAt(Instant.now());
    } else {
      throw new ForbiddenException("Only buyers or helpers can submit ratings");
    }

    return taskMapper.toResponse(task, role == UserRole.BUYER);
  }

  @Transactional
  public TaskResponse cancelTask(UUID userId, UserRole role, UUID taskId, String reason) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    TaskStatus status = task.getStatus();
    if (status != TaskStatus.SCHEDULED_PENDING && status != TaskStatus.SEARCHING && status != TaskStatus.ASSIGNED) {
      throw new BadRequestException("Task can only be cancelled before arrival");
    }

    if (role == UserRole.BUYER) {
      if (!userId.equals(task.getBuyerId())) {
        throw new ForbiddenException("Only the buyer can cancel this task");
      }
    } else if (role == UserRole.HELPER) {
      if (task.getAssignedHelperId() == null || !userId.equals(task.getAssignedHelperId())) {
        throw new ForbiddenException("Only the assigned helper can cancel this task");
      }
    } else {
      throw new ForbiddenException("Only buyers or helpers can cancel tasks");
    }

    String trimmed = reason == null ? "" : reason.trim();
    if (trimmed.isBlank()) {
      throw new BadRequestException("Cancellation reason is required");
    }

    task.setStatus(TaskStatus.CANCELLED);
    task.setCancelReason(trimmed);
    task.setCancelledByRole(role.name());
    task.setCancelledByUserId(userId);
    task.setCancelledAt(Instant.now());

    if (task.getEscrowAmountPaise() != null && task.getEscrowAmountPaise() > 0) {
      if (task.getEscrowStatus() == TaskEscrowStatus.HELD
          || task.getEscrowStatus() == TaskEscrowStatus.RELEASE_SCHEDULED) {
        UserEntity buyer = users.findById(task.getBuyerId()).orElse(null);
        if (buyer != null) {
          long current = buyer.getDemoBalancePaise() == null ? 0L : buyer.getDemoBalancePaise();
          buyer.setDemoBalancePaise(current + task.getEscrowAmountPaise());
          users.save(buyer);
        }
        task.setEscrowStatus(TaskEscrowStatus.REFUNDED);
        task.setEscrowReleaseAt(null);
        task.setEscrowReleasedAt(Instant.now());
        task.setEscrowReleasedToHelperId(null);
      }
    }

    return taskMapper.toResponse(task, role == UserRole.BUYER);
  }

  @Transactional
  public TaskResponse rescheduleTask(UUID buyerId, UUID taskId, Instant newScheduledAt) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    if (!buyerId.equals(task.getBuyerId())) {
      throw new ForbiddenException("Only the buyer can reschedule this task");
    }

    TaskStatus status = task.getStatus();
    if (status != TaskStatus.SCHEDULED_PENDING && status != TaskStatus.SEARCHING) {
      throw new BadRequestException("Task can only be rescheduled if pending or searching");
    }

    Instant now = Instant.now();
    if (newScheduledAt.isBefore(now.plus(java.time.Duration.ofMinutes(5)))) {
      throw new BadRequestException("Scheduled time must be at least 5 minutes in the future");
    }

    task.setScheduledAt(newScheduledAt);
    task.setStatus(TaskStatus.SCHEDULED_PENDING);
    tasks.save(task);

    try {
      realtime.publish(
          "task_status_changed",
          java.util.Map.of(
              "taskId", task.getId().toString(),
              "buyerId", task.getBuyerId().toString(),
              "status", TaskStatus.SCHEDULED_PENDING.name()));
    } catch (Exception re) {
      log.warn("Failed to publish real-time status change for task {}", task.getId(), re);
    }

    return taskMapper.toResponse(task, true);
  }

  @Transactional
  public TaskResponse uploadTaskSelfie(
      UUID helperId,
      UUID taskId,
      TaskSelfieStage stage,
      MultipartFile selfie,
      double lat,
      double lng,
      String addressText,
      String capturedAtIso) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    if (task.getAssignedHelperId() == null || !task.getAssignedHelperId().equals(helperId)) {
      throw new ForbiddenException("Not assigned to this task");
    }

    Instant capturedAt = Instant.now();
    if (capturedAtIso != null && !capturedAtIso.isBlank()) {
      try {
        capturedAt = Instant.parse(capturedAtIso.trim());
      } catch (DateTimeParseException ignored) {
        throw new BadRequestException("capturedAt must be valid ISO-8601 timestamp");
      }
    }

    log.info("Uploading selfie for task {} helper {}, stage: {}", taskId, helperId, stage);
    String selfieUrl = storage.uploadTaskSelfie(taskId, helperId, stage.name().toLowerCase(), selfie);
    log.info("Selfie uploaded successfully, url: {}", selfieUrl);

    if (stage == TaskSelfieStage.ARRIVAL) {
      task.setArrivalSelfieUrl(selfieUrl);
      task.setArrivalSelfieLat(lat);
      task.setArrivalSelfieLng(lng);
      task.setArrivalSelfieAddress(addressText);
      task.setArrivalSelfieCapturedAt(capturedAt);
    } else {
      task.setCompletionSelfieUrl(selfieUrl);
      task.setCompletionSelfieLat(lat);
      task.setCompletionSelfieLng(lng);
      task.setCompletionSelfieAddress(addressText);
      task.setCompletionSelfieCapturedAt(capturedAt);
    }

    // persist changes so subsequent API reads reflect the selfie immediately
    tasks.save(task);

    return taskMapper.toResponse(task, false);
  }

  @Transactional
  public TaskResponse attachTaskSelfieFromStorageKey(
      UUID helperId,
      UUID taskId,
      TaskSelfieStage stage,
      String storageKey,
      double lat,
      double lng,
      String addressText,
      Instant capturedAt) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    if (task.getAssignedHelperId() == null || !task.getAssignedHelperId().equals(helperId)) {
      throw new ForbiddenException("Not assigned to this task");
    }

    Instant resolvedCapturedAt = capturedAt == null ? Instant.now() : capturedAt;
    String selfieUrl = storage.buildPublicUrl(storageKey);

    if (stage == TaskSelfieStage.ARRIVAL) {
      task.setArrivalSelfieUrl(selfieUrl);
      task.setArrivalSelfieLat(lat);
      task.setArrivalSelfieLng(lng);
      task.setArrivalSelfieAddress(addressText);
      task.setArrivalSelfieCapturedAt(resolvedCapturedAt);
    } else {
      task.setCompletionSelfieUrl(selfieUrl);
      task.setCompletionSelfieLat(lat);
      task.setCompletionSelfieLng(lng);
      task.setCompletionSelfieAddress(addressText);
      task.setCompletionSelfieCapturedAt(resolvedCapturedAt);
    }

    return taskMapper.toResponse(task, false);
  }

  public TaskEntity getTask(UUID taskId) {
    return tasks.findById(taskId).orElseThrow(() -> new NotFoundException("Task not found"));
  }

  public List<TaskEntity> listTasksForAdmin(TaskStatus status) {
    return status == null
        ? tasks.findTop100ByOrderByCreatedAtDesc()
        : tasks.findTop100ByStatusOrderByCreatedAtDesc(status);
  }

  public List<TaskEntity> listRecentTasks(int limit) {
    int size = Math.max(1, Math.min(50, limit));
    return tasks.findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, size));
  }

  public List<TaskEntity> listTasksForUser(UUID userId, UserRole role) {
    if (role == UserRole.BUYER) {
      return tasks.findTop50ByBuyerIdOrderByCreatedAtDesc(userId);
    }
    if (role == UserRole.HELPER) {
      return tasks.findTop50ByAssignedHelperIdOrderByCreatedAtDesc(userId);
    }
    return java.util.List.of();
  }

  public Long countByHelperCompleted(UUID helperId) {
    return tasks.countByAssignedHelperIdAndStatus(helperId, TaskStatus.COMPLETED);
  }

  public Long countByBuyerCompleted(UUID buyerId) {
    return tasks.countByBuyerIdAndStatus(buyerId, TaskStatus.COMPLETED);
  }

  public Double avgBuyerRatingForHelper(UUID helperId) {
    return tasks.avgBuyerRatingForHelper(helperId);
  }

  public Double avgHelperRatingForBuyer(UUID buyerId) {
    return tasks.avgHelperRatingForBuyer(buyerId);
  }

  public List<TaskEntity> listAvailableTasks(UUID helperId) {
    var profileOpt = helperProfiles.findById(helperId);
    if (profileOpt.isEmpty() || profileOpt.get().getKycStatus() != HelperKycStatus.APPROVED) {
      return java.util.List.of();
    }

    var state = presence.getHelperState(helperId);
    if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) {
      return java.util.List.of();
    }

    if (tasks.existsByAssignedHelperIdAndStatusIn(helperId, HELPER_ACTIVE_TASK_STATUSES)) {
      return java.util.List.of();
    }

    Instant now = Instant.now();
    return tasks.findTop100ByStatusOrderByCreatedAtDesc(TaskStatus.SEARCHING)
        .stream()
        .filter(t -> t.getScheduledAt() == null || !t.getScheduledAt().isAfter(now))
        .filter(t -> GeoUtils.distanceMeters(t.getLat(), t.getLng(), state.lat(), state.lng()) <= 3000d)
        .limit(50)
        .toList();
  }

  @Transactional
  public TaskEntity updateStatusAsAdmin(UUID taskId, TaskStatus newStatus) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));
    task.setStatus(newStatus);
    tasks.save(task);

    java.util.Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("taskId", taskId.toString());
    payload.put("buyerId", task.getBuyerId().toString());
    payload.put("status", newStatus.name());
    if (task.getAssignedHelperId() != null) {
      payload.put("helperId", task.getAssignedHelperId().toString());
    }
    realtime.publish("task_status_changed", payload);
    if (newStatus == TaskStatus.COMPLETED) {
      invoiceEmail.sendInvoiceEmailAsync(task);
    }
    return task;
  }

  private static boolean isValidHelperTransition(TaskStatus from, TaskStatus to) {
    return switch (from) {
      case ASSIGNED -> to == TaskStatus.ARRIVED;
      case ARRIVED -> to == TaskStatus.STARTED;
      case STARTED -> to == TaskStatus.COMPLETED;
      default -> false;
    };
  }

  private static String generateOtp() {
    int code = 100000 + (int) (Math.random() * 900000);
    return String.valueOf(code);
  }

  private void scheduleEscrowRelease(UUID taskId, UUID helperId) {
    java.util.concurrent.CompletableFuture.runAsync(() -> {
      try {
        Thread.sleep(300_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        TaskEntity task = tasks.findById(taskId).orElse(null);
        if (task == null)
          return;
        if (task.getEscrowStatus() != TaskEscrowStatus.RELEASE_SCHEDULED)
          return;
        Long amount = task.getEscrowAmountPaise();
        if (amount == null || amount <= 0)
          return;
        UUID payHelperId = task.getAssignedHelperId() != null ? task.getAssignedHelperId() : helperId;
        if (payHelperId == null)
          return;
        UserEntity helper = users.findById(payHelperId).orElse(null);
        if (helper == null)
          return;

        long current = helper.getDemoBalancePaise() == null ? 0L : helper.getDemoBalancePaise();
        helper.setDemoBalancePaise(current + amount);
        task.setEscrowStatus(TaskEscrowStatus.RELEASED);
        task.setEscrowReleasedAt(Instant.now());
        task.setEscrowReleasedToHelperId(payHelperId);
        tasks.save(task);
        users.save(helper);

        realtime.publish(
            "escrow_released",
            java.util.Map.of(
                "taskId", taskId.toString(),
                "helperId", payHelperId.toString(),
                "amountPaise", amount));
      } catch (Exception ignored) {
        // best-effort for demo
      }
    });
  }

  @Transactional
  public TaskResponse extendTask(UUID buyerId, UUID taskId, int additionalTimeMinutes, long additionalBudgetPaise) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    if (!task.getBuyerId().equals(buyerId)) {
      throw new ForbiddenException("Not allowed to modify this task");
    }

    if (task.getStatus() != TaskStatus.STARTED) {
      throw new BadRequestException("Task can only be extended while in progress (STARTED status)");
    }

    UserEntity buyer = users.findById(buyerId)
        .orElseThrow(() -> new ForbiddenException("Buyer not found"));

    Long balance = buyer.getDemoBalancePaise();
    long current = balance == null ? 1_000_000L : balance;
    // Demo balance bypass: since we are on Cash / UPI, wallet balance should not block extensions
    /*
    if (additionalBudgetPaise > current) {
      throw new BadRequestException("Insufficient demo balance for extension");
    }

    buyer.setDemoBalancePaise(current - additionalBudgetPaise);
    users.save(buyer);
    */

    task.setTimeMinutes(task.getTimeMinutes() + additionalTimeMinutes);
    task.setBudgetPaise(task.getBudgetPaise() + additionalBudgetPaise);
    task.setEscrowAmountPaise(task.getEscrowAmountPaise() + additionalBudgetPaise);
    tasks.save(task);

    try {
      realtime.publish(
          "task_status_changed",
          java.util.Map.of(
              "taskId", task.getId().toString(),
              "buyerId", task.getBuyerId().toString(),
              "status", task.getStatus().name()));
    } catch (Exception ignored) {
    }

    return taskMapper.toResponse(task, true);
  }

  private void validateRecurringRequest(CreateRecurringTaskRequest req) {
    String frequency = req.frequency() == null ? "" : req.frequency().trim().toUpperCase();
    java.util.Set<String> allowed = java.util.Set.of(
        "DAILY", "EVERYDAY", "WEEKLY", "MONTHLY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    if (!allowed.contains(frequency)) {
      throw new BadRequestException("Invalid recurring frequency");
    }
    if (req.byDay() != null) {
      if (req.byDay().length > 7) {
        throw new BadRequestException("Recurring weekly days cannot exceed 7 values");
      }
      java.util.Set<Integer> seen = new java.util.HashSet<>();
      for (int day : req.byDay()) {
        if (day < 1 || day > 7 || !seen.add(day)) {
          throw new BadRequestException("Recurring day values must be unique and between 1 and 7");
        }
      }
    }
    if (req.helperCount() != null && req.helperCount() > 500) {
      throw new BadRequestException("Recurring bulk bookings support up to 500 helpers");
    }
  }

  private void cancelPendingMediatorBatchesForRecurring(UUID recurringTaskId, String reason) {
    cancelPendingMediatorBatchesForRecurring(recurringTaskId, reason, false);
  }

  private void cancelPendingMediatorBatchesForRecurring(UUID recurringTaskId, String reason, boolean detachFromRecurring) {
    java.util.List<BookingBatchStatus> cancellable = java.util.List.of(
        BookingBatchStatus.PENDING_AUDIT,
        BookingBatchStatus.ON_HOLD,
        BookingBatchStatus.PENDING_MEDIATOR,
        BookingBatchStatus.MEDIATOR_ACCEPTED,
        BookingBatchStatus.MEDIATOR_DISPATCHING);
    var batchesToUpdate = detachFromRecurring
        ? bookingBatches.findBySourceRecurringTaskId(recurringTaskId)
        : bookingBatches.findBySourceRecurringTaskIdAndStatusIn(recurringTaskId, cancellable);
    for (var batch : batchesToUpdate) {
      if (cancellable.contains(batch.getStatus())) {
        batch.setStatus(BookingBatchStatus.CANCELLED);
        batch.setNotes(((batch.getNotes() == null || batch.getNotes().isBlank()) ? "" : batch.getNotes() + " | ") + reason);
      }
      if (detachFromRecurring) {
        batch.setSourceRecurringTaskId(null);
      }
      bookingBatches.save(batch);
    }
  }

  public record CreateResult(UUID taskId, List<UUID> offeredTo) {
  }

  public record TaskCreateOptions(boolean sendOfferNotifications) {
    public static TaskCreateOptions defaultOptions() {
      return new TaskCreateOptions(true);
    }

    public static TaskCreateOptions silentPush() {
      return new TaskCreateOptions(false);
    }
  }

  @Transactional(readOnly = true)
  public java.util.List<RecurringTaskResponse> getMyRecurringTasks(UUID buyerId) {
    return recurringTasks.findAllByBuyerIdOrderByCreatedAtDesc(buyerId).stream()
        .map(rec -> new RecurringTaskResponse(
            rec.getId(),
            rec.getBuyerId(),
            rec.getTitle(),
            rec.getDescription(),
            rec.getUrgency(),
            rec.getTimeMinutes(),
            rec.getBudgetPaise(),
            rec.getLat(),
            rec.getLng(),
            rec.getAddressText(),
            rec.getFrequency(),
            rec.getStartDate(),
            rec.getEndDate(),
            rec.getTimeSlot(),
            rec.getCreatedAt(),
            rec.getStatus(),
            rec.getRecurrenceInterval(),
            rec.getByDay(),
            rec.getByMonthDay(),
            rec.getTimezone(),
            rec.getHelperCount()
        ))
        .collect(java.util.stream.Collectors.toList());
  }

  @Transactional
  public void deleteRecurringTask(UUID buyerId, UUID recurringTaskId) {
    RecurringTaskEntity rec = recurringTasks.findById(recurringTaskId)
        .orElseThrow(() -> new NotFoundException("Recurring task not found"));

    if (!rec.getBuyerId().equals(buyerId)) {
      throw new ForbiddenException("Only the owner can delete this recurring task");
    }

    // Cancel all future tasks associated with this recurring task that are not started (SEARCHING, ASSIGNED, and SCHEDULED_PENDING)
    java.util.List<TaskEntity> searchingTasks = tasks.findByRecurringTaskIdAndStatus(recurringTaskId, TaskStatus.SEARCHING);
    java.util.List<TaskEntity> assignedTasks = tasks.findByRecurringTaskIdAndStatus(recurringTaskId, TaskStatus.ASSIGNED);
    java.util.List<TaskEntity> scheduledPendingTasks = tasks.findByRecurringTaskIdAndStatus(recurringTaskId, TaskStatus.SCHEDULED_PENDING);

    java.util.List<TaskEntity> associatedTasks = new java.util.ArrayList<>();
    associatedTasks.addAll(searchingTasks);
    associatedTasks.addAll(assignedTasks);
    associatedTasks.addAll(scheduledPendingTasks);

    for (TaskEntity task : associatedTasks) {
      task.setStatus(TaskStatus.CANCELLED);
      task.setCancelReason("Recurring task configuration was deleted");
      task.setCancelledByRole(UserRole.BUYER.name());
      task.setCancelledByUserId(buyerId);
      task.setCancelledAt(Instant.now());

      if (task.getEscrowAmountPaise() != null && task.getEscrowAmountPaise() > 0) {
        if (task.getEscrowStatus() == TaskEscrowStatus.HELD
            || task.getEscrowStatus() == TaskEscrowStatus.RELEASE_SCHEDULED) {
          UserEntity buyer = users.findById(task.getBuyerId()).orElse(null);
          if (buyer != null) {
            long current = buyer.getDemoBalancePaise() == null ? 0L : buyer.getDemoBalancePaise();
            buyer.setDemoBalancePaise(current + task.getEscrowAmountPaise());
            users.save(buyer);
          }
          task.setEscrowStatus(TaskEscrowStatus.REFUNDED);
          task.setEscrowReleaseAt(null);
          task.setEscrowReleasedAt(Instant.now());
          task.setEscrowReleasedToHelperId(null);
        }
      }
      tasks.save(task);
    }

    cancelPendingMediatorBatchesForRecurring(recurringTaskId, "Recurring task configuration was deleted", true);
    recurringTasks.delete(rec);
  }

  @Transactional
  public void pauseRecurringTask(UUID buyerId, UUID recurringTaskId) {
    RecurringTaskEntity rec = recurringTasks.findById(recurringTaskId)
        .orElseThrow(() -> new NotFoundException("Recurring task not found"));

    if (!rec.getBuyerId().equals(buyerId)) {
      throw new ForbiddenException("Only the owner can pause this recurring task");
    }

    if (rec.getStatus() == RecurringTaskStatus.PAUSED) {
      return;
    }

    rec.setStatus(RecurringTaskStatus.PAUSED);
    recurringTasks.save(rec);

    // Cancel all future tasks associated with this recurring task that are SEARCHING or SCHEDULED_PENDING
    java.util.List<TaskEntity> futureTasks = tasks.findByRecurringTaskId(recurringTaskId);
    for (TaskEntity task : futureTasks) {
      if (task.getStatus() == TaskStatus.SEARCHING || task.getStatus() == TaskStatus.SCHEDULED_PENDING) {
        task.setStatus(TaskStatus.CANCELLED);
        task.setCancelReason("Recurring task configuration was paused");
        task.setCancelledByRole(UserRole.BUYER.name());
        task.setCancelledByUserId(buyerId);
        task.setCancelledAt(Instant.now());

        if (task.getEscrowAmountPaise() != null && task.getEscrowAmountPaise() > 0) {
          if (task.getEscrowStatus() == TaskEscrowStatus.HELD
              || task.getEscrowStatus() == TaskEscrowStatus.RELEASE_SCHEDULED) {
            UserEntity buyer = users.findById(task.getBuyerId()).orElse(null);
            if (buyer != null) {
              long current = buyer.getDemoBalancePaise() == null ? 0L : buyer.getDemoBalancePaise();
              buyer.setDemoBalancePaise(current + task.getEscrowAmountPaise());
              users.save(buyer);
            }
            task.setEscrowStatus(TaskEscrowStatus.REFUNDED);
            task.setEscrowReleaseAt(null);
            task.setEscrowReleasedAt(Instant.now());
            task.setEscrowReleasedToHelperId(null);
          }
        }
        tasks.save(task);
      }
    }

    cancelPendingMediatorBatchesForRecurring(recurringTaskId, "Recurring task configuration was paused");
  }

  @Transactional
  public void resumeRecurringTask(UUID buyerId, UUID recurringTaskId) {
    RecurringTaskEntity rec = recurringTasks.findById(recurringTaskId)
        .orElseThrow(() -> new NotFoundException("Recurring task not found"));

    if (!rec.getBuyerId().equals(buyerId)) {
      throw new ForbiddenException("Only the owner can resume this recurring task");
    }

    if (rec.getStatus() == RecurringTaskStatus.ACTIVE) {
      return;
    }

    rec.setStatus(RecurringTaskStatus.ACTIVE);
    recurringTasks.save(rec);

    // Re-generate future tasks starting from max(now, startDate) to min(now + 7 days, endDate)
    Instant now = Instant.now();
    Instant lookaheadHorizon = now.plus(7, java.time.temporal.ChronoUnit.DAYS);
    List<ZonedDateTime> occurrences = RecurrenceCalculator.nextNOccurrences(rec, now, 20);

    for (ZonedDateTime zdt : occurrences) {
      Instant scheduledAt = zdt.toInstant();
      if (scheduledAt.isAfter(lookaheadHorizon)) {
        break; // stop at horizon
      }

      // Check if an active (non-cancelled) task already exists for this scheduledAt
      boolean exists = tasks.findByRecurringTaskId(recurringTaskId).stream()
          .anyMatch(t -> t.getScheduledAt() != null
              && Math.abs(t.getScheduledAt().toEpochMilli() - scheduledAt.toEpochMilli()) < 1000
              && t.getStatus() != TaskStatus.CANCELLED)
          || bookingBatches.existsBySourceRecurringTaskIdAndScheduledWindowStartAndStatusNot(
              recurringTaskId, scheduledAt, BookingBatchStatus.CANCELLED);

      if (!exists) {
        spawnOccurrence(rec.getId(), scheduledAt);
      }
    }
  }
}
