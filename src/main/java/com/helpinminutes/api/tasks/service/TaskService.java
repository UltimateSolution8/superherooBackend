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
      TaskMapper taskMapper) {
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
  }

  @Transactional
  public CreateResult createTask(UUID buyerId, CreateTaskRequest req) {
    return createTask(buyerId, req, TaskCreateOptions.defaultOptions());
  }

  @Transactional
  public CreateResult createTask(UUID buyerId, CreateTaskRequest req, TaskCreateOptions options) {
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
    if (cost > current) {
      throw new BadRequestException("Insufficient demo balance for escrow");
    }
    buyer.setDemoBalancePaise(current - cost);
    users.save(buyer);

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
    if (req.scheduledAt() != null) {
      task.setScheduledAt(req.scheduledAt());
    }
    task.setStatus(TaskStatus.SEARCHING);
    task.setEscrowStatus(TaskEscrowStatus.HELD);
    task.setEscrowAmountPaise(cost);
    task.setEscrowHeldAt(Instant.now());
    task.setArrivalOtp(generateOtp());
    task.setCompletionOtp(generateOtp());

    tasks.save(task);

    List<UUID> offeredTo = new ArrayList<>();
    Instant now = Instant.now();
    Instant scheduledAt = task.getScheduledAt();
    if (scheduledAt == null || !scheduledAt.isAfter(now)) {
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
  public TaskResponse acceptTask(UUID helperId, UUID taskId) {
    users.findByIdForUpdate(helperId)
        .orElseThrow(() -> new ForbiddenException("Helper not found"));

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
        if (otp == null || otp.isBlank() || !expected.equals(otp.trim())) {
          throw new BadRequestException("Arrival OTP is required to start work");
        }
      }
    }
    if (newStatus == TaskStatus.COMPLETED && task.getCompletionSelfieUrl() == null) {
      throw new BadRequestException("Completion selfie is required before marking COMPLETED");
    }
    if (newStatus == TaskStatus.COMPLETED) {
      String expected = task.getCompletionOtp();
      if (expected != null && !expected.isBlank()) {
        if (otp == null || otp.isBlank() || !expected.equals(otp.trim())) {
          throw new BadRequestException("Completion OTP is required to finish work");
        }
      }
    }

    task.setStatus(newStatus);

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

    if (newStatus == TaskStatus.COMPLETED) {
      notificationQueue.enqueueTaskCompleted(task.getBuyerId(), task);
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
    if (status != TaskStatus.SEARCHING && status != TaskStatus.ASSIGNED) {
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
}
