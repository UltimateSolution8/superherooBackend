package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ConflictException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.storage.SupabaseStorageService;
import com.helpinminutes.api.tasks.dto.CreateTaskRequest;
import com.helpinminutes.api.tasks.model.TaskEscrowStatus;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import com.helpinminutes.api.tasks.model.TaskSelfieStage;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TaskService {
  private final TaskRepository tasks;
  private final TaskOfferRepository offers;
  private final MatchingService matching;
  private final RealtimePublisher realtime;
  private final SupabaseStorageService storage;
  private final HelperPresenceService presence;
  private final UserRepository users;

  public TaskService(
      TaskRepository tasks,
      TaskOfferRepository offers,
      MatchingService matching,
      RealtimePublisher realtime,
      SupabaseStorageService storage,
      HelperPresenceService presence,
      UserRepository users) {
    this.tasks = tasks;
    this.offers = offers;
    this.matching = matching;
    this.realtime = realtime;
    this.storage = storage;
    this.presence = presence;
    this.users = users;
  }

  @Transactional
  public CreateResult createTask(UUID buyerId, CreateTaskRequest req) {
    UserEntity buyer = users.findById(buyerId)
        .orElseThrow(() -> new ForbiddenException("Buyer not found"));

    long cost = req.budgetPaise() == null ? 0L : Math.max(0L, req.budgetPaise());
    Long balance = buyer.getDemoBalancePaise();
    long current = balance == null ? 0L : balance;
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
    task.setStatus(TaskStatus.SEARCHING);
    task.setEscrowStatus(TaskEscrowStatus.HELD);
    task.setEscrowAmountPaise(cost);
    task.setEscrowHeldAt(Instant.now());

    tasks.save(task);

    List<UUID> offeredTo = List.of();
    try {
      java.util.concurrent.CompletableFuture.runAsync(() -> {
        try {
          matching.dispatchOffers(task);
        } catch (Exception ignored) {
        }
      });
    } catch (Exception ignored) {
    }

    try {
      java.util.concurrent.CompletableFuture.runAsync(() -> {
        try {
          realtime.publish(
              "TASK_CREATED",
              java.util.Map.of(
                  "taskId", task.getId().toString(),
                  "buyerId", buyerId.toString(),
                  "title", task.getTitle(),
                  "urgency", task.getUrgency().name(),
                  "status", task.getStatus().name()));
        } catch (Exception ignored) {
        }
      });
    } catch (Exception ignored) {
    }

    return new CreateResult(task.getId(), offeredTo);
  }

  @Transactional
  public TaskEntity acceptTask(UUID helperId, UUID taskId) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

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
    } else {
      var state = presence.getHelperState(helperId);
      if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) {
        throw new ForbiddenException("Helper location is not available");
      }

      long ageSeconds = (Instant.now().toEpochMilli() - state.lastSeenEpochMs()) / 1000;
      if (ageSeconds > 60) {
        throw new ForbiddenException("Helper location is stale");
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
    }

    realtime.publish(
        "TASK_ASSIGNED",
        java.util.Map.of(
            "taskId", taskId.toString(),
            "buyerId", task.getBuyerId().toString(),
            "helperId", helperId.toString(),
            "status", TaskStatus.ASSIGNED.name()));

    return tasks.findById(taskId).orElseThrow();
  }

  @Transactional
  public TaskEntity updateStatusAsHelper(UUID helperId, UUID taskId, TaskStatus newStatus) {
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
    if (newStatus == TaskStatus.COMPLETED && task.getCompletionSelfieUrl() == null) {
      throw new BadRequestException("Completion selfie is required before marking COMPLETED");
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

    realtime.publish(
        "TASK_STATUS_CHANGED",
        java.util.Map.of(
            "taskId", taskId.toString(),
            "buyerId", task.getBuyerId().toString(),
            "helperId", helperId.toString(),
            "status", newStatus.name()));

    return task;
  }

  @Transactional
  public TaskEntity uploadTaskSelfie(
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

    String selfieUrl = storage.uploadTaskSelfie(taskId, helperId, stage.name().toLowerCase(), selfie);

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

    tasks.save(task);

    realtime.publish(
        "TASK_SELFIE_UPLOADED",
        java.util.Map.of(
            "taskId", taskId.toString(),
            "buyerId", task.getBuyerId().toString(),
            "helperId", helperId.toString(),
            "stage", stage.name(),
            "selfieUrl", selfieUrl,
            "lat", lat,
            "lng", lng,
            "addressText", addressText == null ? "" : addressText,
            "capturedAt", capturedAt.toString()));

    return task;
  }

  public TaskEntity getTask(UUID taskId) {
    return tasks.findById(taskId).orElseThrow(() -> new NotFoundException("Task not found"));
  }

  public List<TaskEntity> listTasksForAdmin(TaskStatus status) {
    return status == null
        ? tasks.findTop100ByOrderByCreatedAtDesc()
        : tasks.findTop100ByStatusOrderByCreatedAtDesc(status);
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
    realtime.publish("TASK_STATUS_CHANGED", payload);
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

  public record CreateResult(UUID taskId, List<UUID> offeredTo) {}
}
