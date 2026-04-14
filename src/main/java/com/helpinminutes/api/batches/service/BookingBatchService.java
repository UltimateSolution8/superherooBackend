package com.helpinminutes.api.batches.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.common.ServiceArea;
import com.helpinminutes.api.batches.dto.BatchDtos;
import com.helpinminutes.api.batches.model.BookingBatchEntity;
import com.helpinminutes.api.batches.model.BookingBatchEventEntity;
import com.helpinminutes.api.batches.model.BookingBatchItemEntity;
import com.helpinminutes.api.batches.model.BookingBatchLineStatus;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.batches.repo.BookingBatchEventRepository;
import com.helpinminutes.api.batches.repo.BookingBatchItemRepository;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.tasks.dto.CreateTaskRequest;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.tasks.service.TaskService;
import com.helpinminutes.api.notifications.service.NotificationQueueService;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingBatchService {
  private final BookingBatchRepository batches;
  private final BookingBatchItemRepository items;
  private final BookingBatchEventRepository events;
  private final TaskService tasks;
  private final NotificationQueueService notificationQueue;
  private final TaskRepository taskRepo;
  private final UserRepository users;
  private final ObjectMapper objectMapper;

  public BookingBatchService(
      BookingBatchRepository batches,
      BookingBatchItemRepository items,
      BookingBatchEventRepository events,
      TaskService tasks,
      NotificationQueueService notificationQueue,
      TaskRepository taskRepo,
      UserRepository users,
      ObjectMapper objectMapper) {
    this.batches = batches;
    this.items = items;
    this.events = events;
    this.tasks = tasks;
    this.notificationQueue = notificationQueue;
    this.taskRepo = taskRepo;
    this.users = users;
    this.objectMapper = objectMapper;
  }

  public BatchDtos.PreviewResponse preview(BatchDtos.PreviewRequest req) {
    List<BatchDtos.PreviewItemResult> out = new ArrayList<>();
    int valid = 0;
    for (int i = 0; i < req.items().size(); i++) {
      BatchDtos.PreviewItem line = req.items().get(i);
      List<String> errors = validateLine(line);
      long recommended = recommendBudget(line.title(), line.description(), line.timeMinutes(), line.urgency());
      String confidence = line.timeMinutes() <= 45 ? "HIGH" : line.timeMinutes() <= 120 ? "MEDIUM" : "LOW";
      if (errors.isEmpty()) valid++;
      out.add(new BatchDtos.PreviewItemResult(i + 1, recommended, confidence, errors));
    }
    return new BatchDtos.PreviewResponse(req.items().size(), valid, req.items().size() - valid, out);
  }

  @Transactional
  public BatchDtos.CreateResponse create(UUID actorUserId, UserRole actorRole, BatchDtos.CreateRequest req) {
    UUID buyerId = resolveBuyer(actorUserId, actorRole, req.buyerId());
    if (req.items().isEmpty()) throw new BadRequestException("At least one item is required");
    if (req.scheduledWindowStart() != null && req.scheduledWindowEnd() != null
        && req.scheduledWindowEnd().isBefore(req.scheduledWindowStart())) {
      throw new BadRequestException("scheduledWindowEnd must be after scheduledWindowStart");
    }

    if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
      var existing = batches.findByCreatedByUserIdAndIdempotencyKey(buyerId, req.idempotencyKey().trim());
      if (existing.isPresent()) {
        BatchDtos.BatchSummaryResponse summary = getSummary(actorUserId, actorRole, existing.get().getId());
        long totalCreated = summary.byTaskStatus().values().stream().mapToLong(Long::longValue).sum();
        long failed = Math.max(0, req.items().size() - totalCreated);
        return new BatchDtos.CreateResponse(existing.get().getId(), req.items().size(), (int) totalCreated, (int) failed, existing.get().getStatus().name());
      }
    }

    UserEntity buyer = users.findById(buyerId).orElseThrow(() -> new NotFoundException("Buyer not found"));
    long totalBudget = req.items().stream().mapToLong(i -> Math.max(0L, i.budgetPaise())).sum();
    long balance = buyer.getDemoBalancePaise() == null ? 1_000_000L : buyer.getDemoBalancePaise();
    if (totalBudget > balance) {
      throw new BadRequestException("Insufficient buyer balance for batch escrow");
    }

    BookingBatchEntity batch = new BookingBatchEntity();
    batch.setCreatedByUserId(buyerId);
    batch.setTitle(req.title().trim());
    batch.setNotes(req.notes() == null ? null : req.notes().trim());
    batch.setScheduledWindowStart(req.scheduledWindowStart());
    batch.setScheduledWindowEnd(req.scheduledWindowEnd());
    batch.setStatus(BookingBatchStatus.CREATED);
    batch.setIdempotencyKey(req.idempotencyKey() == null ? null : req.idempotencyKey().trim());
    batch = batches.save(batch);

    int created = 0;
    int failed = 0;
    UUID firstCreatedTaskId = null;
    java.util.Set<UUID> notifiedHelperIds = new java.util.LinkedHashSet<>();
    for (int i = 0; i < req.items().size(); i++) {
      BatchDtos.CreateItem line = req.items().get(i);
      BookingBatchItemEntity item = new BookingBatchItemEntity();
      item.setBatchId(batch.getId());
      item.setLineNo(i + 1);
      item.setExternalRef(blankToNull(line.externalRef()));
      item.setPriority(line.priority() == null ? 3 : line.priority());
      item.setPayloadJson(toJson(line));

      List<String> errors = validateLine(new BatchDtos.PreviewItem(
          line.title(), line.description(), line.urgency(), line.timeMinutes(), line.budgetPaise(),
          line.lat(), line.lng(), line.addressText(), line.scheduledAt()));
      if (!errors.isEmpty()) {
        item.setLineStatus(BookingBatchLineStatus.FAILED);
        item.setErrorMessage(String.join("; ", errors));
        items.save(item);
        failed++;
        continue;
      }

      try {
        var createResult = tasks.createTask(buyerId, new CreateTaskRequest(
            line.title().trim(),
            line.description().trim(),
            line.urgency(),
            line.timeMinutes(),
            line.budgetPaise(),
            line.lat(),
            line.lng(),
            blankToNull(line.addressText()),
            line.scheduledAt()), TaskService.TaskCreateOptions.silentPush());
        item.setTaskId(createResult.taskId());
        item.setLineStatus(BookingBatchLineStatus.CREATED);
        if (firstCreatedTaskId == null) {
          firstCreatedTaskId = createResult.taskId();
        }
        if (createResult.offeredTo() != null && !createResult.offeredTo().isEmpty()) {
          notifiedHelperIds.addAll(createResult.offeredTo());
        }
        created++;
      } catch (Exception ex) {
        item.setLineStatus(BookingBatchLineStatus.FAILED);
        item.setErrorMessage("Task creation failed");
        failed++;
      }
      items.save(item);
    }

    if (firstCreatedTaskId != null && !notifiedHelperIds.isEmpty()) {
      taskRepo.findById(firstCreatedTaskId).ifPresent(seedTask ->
          notificationQueue.enqueueTaskCreated(new java.util.ArrayList<>(notifiedHelperIds), seedTask));
    }

    batch.setStatus(failed > 0 ? BookingBatchStatus.PARTIAL : BookingBatchStatus.COMPLETED);
    batches.save(batch);
    writeEvent(batch.getId(), "BATCH_CREATED", "{\"created\":" + created + ",\"failed\":" + failed + "}");
    return new BatchDtos.CreateResponse(batch.getId(), req.items().size(), created, failed, batch.getStatus().name());
  }

  @Transactional
  public BatchDtos.BatchItemResponse retryItem(UUID actorUserId, UserRole actorRole, UUID batchId, UUID itemId) {
    BookingBatchEntity batch = batches.findById(batchId).orElseThrow(() -> new NotFoundException("Batch not found"));
    ensureAccess(actorUserId, actorRole, batch);
    BookingBatchItemEntity item = items.findByIdAndBatchId(itemId, batchId).orElseThrow(() -> new NotFoundException("Batch item not found"));
    TaskEntity existingTask = item.getTaskId() == null ? null : taskRepo.findById(item.getTaskId()).orElse(null);
    if (!canRetry(item, existingTask)) throw new BadRequestException("Item is not eligible for retry");
    if (item.getPayloadJson() == null || item.getPayloadJson().isBlank()) throw new BadRequestException("Original line payload is missing");

    try {
      BatchDtos.CreateItem line = objectMapper.readValue(item.getPayloadJson(), BatchDtos.CreateItem.class);
      var createResult = tasks.createTask(batch.getCreatedByUserId(), new CreateTaskRequest(
          line.title().trim(),
          line.description().trim(),
          line.urgency(),
          line.timeMinutes(),
          line.budgetPaise(),
          line.lat(),
          line.lng(),
          blankToNull(line.addressText()),
          line.scheduledAt()));
      item.setTaskId(createResult.taskId());
      item.setLineStatus(BookingBatchLineStatus.CREATED);
      item.setErrorMessage(null);
      items.save(item);
      writeEvent(batchId, "ITEM_RETRIED", "{\"itemId\":\"" + item.getId() + "\"}");
    } catch (BadRequestException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BadRequestException("Could not retry this item");
    }

    TaskEntity task = item.getTaskId() == null ? null : taskRepo.findById(item.getTaskId()).orElse(null);
    return toItemResponse(item, task, task == null ? Map.of() : resolveHelperUsers(java.util.List.of(task)));
  }

  @Transactional
  public BatchDtos.BatchItemResponse cancelItem(UUID actorUserId, UserRole actorRole, UUID batchId, UUID itemId, String reason) {
    BookingBatchEntity batch = batches.findById(batchId).orElseThrow(() -> new NotFoundException("Batch not found"));
    ensureAccess(actorUserId, actorRole, batch);
    BookingBatchItemEntity item = items.findByIdAndBatchId(itemId, batchId).orElseThrow(() -> new NotFoundException("Batch item not found"));
    if (item.getTaskId() == null) throw new BadRequestException("This line does not have a task to cancel");

    TaskEntity task = taskRepo.findById(item.getTaskId()).orElseThrow(() -> new NotFoundException("Task not found"));
    if (!canCancel(task)) throw new BadRequestException("Only active tasks can be cancelled");
    tasks.cancelTask(actorUserId, actorRole, task.getId(), blankToNull(reason) == null ? "Cancelled from bulk console" : reason.trim());
    TaskEntity refreshed = taskRepo.findById(task.getId()).orElse(task);
    writeEvent(batchId, "ITEM_CANCELLED", "{\"itemId\":\"" + item.getId() + "\"}");
    return toItemResponse(item, refreshed, resolveHelperUsers(java.util.List.of(refreshed)));
  }

  @Transactional(readOnly = true)
  public BatchDtos.BatchSummaryResponse getSummary(UUID actorUserId, UserRole actorRole, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId).orElseThrow(() -> new NotFoundException("Batch not found"));
    ensureAccess(actorUserId, actorRole, batch);
    List<BookingBatchItemEntity> batchItems = items.findByBatchIdOrderByLineNoAsc(batchId);
    List<UUID> taskIds = batchItems.stream().map(BookingBatchItemEntity::getTaskId).filter(java.util.Objects::nonNull).toList();
    Map<UUID, TaskEntity> taskById = taskRepo.findAllById(taskIds).stream().collect(Collectors.toMap(TaskEntity::getId, t -> t));
    Map<String, Long> byStatus = new LinkedHashMap<>();
    byStatus.put("FAILED", batchItems.stream().filter(i -> i.getLineStatus() == BookingBatchLineStatus.FAILED).count());
    for (BookingBatchItemEntity item : batchItems) {
      TaskEntity task = taskById.get(item.getTaskId());
      if (task == null) continue;
      String key = task.getStatus().name();
      byStatus.put(key, byStatus.getOrDefault(key, 0L) + 1L);
    }

    return new BatchDtos.BatchSummaryResponse(
        batch.getId(),
        batch.getTitle(),
        batch.getNotes(),
        batch.getStatus().name(),
        batch.getCreatedAt(),
        batch.getScheduledWindowStart(),
        batch.getScheduledWindowEnd(),
        batchItems.size(),
        byStatus);
  }

  @Transactional(readOnly = true)
  public List<BatchDtos.BatchItemResponse> getItems(UUID actorUserId, UserRole actorRole, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId).orElseThrow(() -> new NotFoundException("Batch not found"));
    ensureAccess(actorUserId, actorRole, batch);
    List<BookingBatchItemEntity> batchItems = items.findByBatchIdOrderByLineNoAsc(batchId);
    List<UUID> taskIds = batchItems.stream().map(BookingBatchItemEntity::getTaskId).filter(java.util.Objects::nonNull).toList();
    Map<UUID, TaskEntity> taskById = taskRepo.findAllById(taskIds).stream().collect(Collectors.toMap(TaskEntity::getId, t -> t));
    Map<UUID, UserEntity> helperUsers = resolveHelperUsers(taskById.values());
    return batchItems.stream()
        .sorted(Comparator.comparingInt(BookingBatchItemEntity::getLineNo))
        .map(item -> toItemResponse(item, taskById.get(item.getTaskId()), helperUsers))
        .toList();
  }

  @Transactional(readOnly = true)
  public BatchDtos.BatchLiveResponse getLive(UUID actorUserId, UserRole actorRole, UUID batchId) {
    Map<String, Long> counters = new HashMap<>(getSummary(actorUserId, actorRole, batchId).byTaskStatus());
    return new BatchDtos.BatchLiveResponse(batchId, "batch:" + batchId, counters, getItems(actorUserId, actorRole, batchId));
  }

  private BatchDtos.BatchItemResponse toItemResponse(BookingBatchItemEntity item, TaskEntity task) {
    return toItemResponse(item, task, Map.of());
  }

  private BatchDtos.BatchItemResponse toItemResponse(
      BookingBatchItemEntity item,
      TaskEntity task,
      Map<UUID, UserEntity> helperUsers) {
    UserEntity helper = (task == null || task.getAssignedHelperId() == null)
        ? null
        : helperUsers.get(task.getAssignedHelperId());
    String helperName = helper == null
        ? null
        : (helper.getDisplayName() != null && !helper.getDisplayName().isBlank() ? helper.getDisplayName() : helper.getPhone());
    return new BatchDtos.BatchItemResponse(
        item.getId(),
        item.getLineNo(),
        item.getExternalRef(),
        item.getPriority(),
        item.getLineStatus().name(),
        item.getErrorMessage(),
        task == null ? null : task.getId(),
        task == null ? null : task.getStatus(),
        task == null ? null : task.getTitle(),
        task == null || task.getAssignedHelperId() == null ? null : task.getAssignedHelperId().toString(),
        helperName,
        null,
        canRetry(item, task),
        canCancel(task));
  }

  private Map<UUID, UserEntity> resolveHelperUsers(java.util.Collection<TaskEntity> taskValues) {
    List<UUID> helperIds = taskValues.stream()
        .map(TaskEntity::getAssignedHelperId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (helperIds.isEmpty()) return Map.of();
    return users.findAllById(helperIds).stream().collect(Collectors.toMap(UserEntity::getId, u -> u));
  }

  private List<String> validateLine(BatchDtos.PreviewItem line) {
    List<String> errors = new ArrayList<>();
    if (line.title() == null || line.title().trim().length() < 3) errors.add("title too short");
    if (line.description() == null || line.description().trim().length() < 10) errors.add("description too short");
    if (line.timeMinutes() == null || line.timeMinutes() < 1 || line.timeMinutes() > 480) errors.add("timeMinutes out of range");
    if (line.budgetPaise() == null || line.budgetPaise() < 0) errors.add("budgetPaise invalid");
    if (line.lat() == null || line.lat() < -90 || line.lat() > 90) errors.add("lat invalid");
    if (line.lng() == null || line.lng() < -180 || line.lng() > 180) errors.add("lng invalid");
    if (line.lat() != null && line.lng() != null && !ServiceArea.isWithinHyderabad(line.lat(), line.lng())) {
      errors.add("location outside service area (Hyderabad only)");
    }
    if (line.scheduledAt() != null && line.scheduledAt().isBefore(Instant.now().minusSeconds(60))) errors.add("scheduledAt is in the past");
    return errors;
  }

  private long recommendBudget(String title, String description, Integer timeMinutes, TaskUrgency urgency) {
    String t = ((title == null ? "" : title) + " " + (description == null ? "" : description)).toLowerCase();
    int score = 0;
    if (t.matches(".*(repair|plumb|electric|wiring|ac|fridge|washing|leak|fix).*")) score += 3;
    if (t.matches(".*(lift|heavy|move|shift|furniture|loading|unloading).*")) score += 2;
    if (t.matches(".*(clean|deep clean|sanitize|bathroom|kitchen).*")) score += 2;
    int minutes = Math.max(1, Math.min(480, timeMinutes == null ? 30 : timeMinutes));
    double urgencyFactor = urgency == TaskUrgency.CRITICAL ? 1.35 : urgency == TaskUrgency.HIGH ? 1.2 : urgency == TaskUrgency.LOW ? 0.9 : 1.0;
    long rupees = Math.max(80, Math.round((minutes * 6 + score * 35) * urgencyFactor));
    return rupees * 100;
  }

  private void writeEvent(UUID batchId, String type, String payloadJson) {
    BookingBatchEventEntity event = new BookingBatchEventEntity();
    event.setBatchId(batchId);
    event.setEventType(type);
    event.setPayloadJson(payloadJson);
    events.save(event);
  }

  private void ensureAccess(UUID actorUserId, UserRole actorRole, BookingBatchEntity batch) {
    if (actorRole == UserRole.ADMIN) return;
    if (actorRole != UserRole.BUYER || !actorUserId.equals(batch.getCreatedByUserId())) {
      throw new ForbiddenException("Not allowed");
    }
  }

  private UUID resolveBuyer(UUID actorUserId, UserRole actorRole, UUID buyerIdFromReq) {
    if (actorRole == UserRole.BUYER) return actorUserId;
    if (actorRole == UserRole.ADMIN) {
      if (buyerIdFromReq == null) throw new BadRequestException("buyerId is required for admin batch creation");
      return buyerIdFromReq;
    }
    throw new ForbiddenException("Only buyers/admin can create batches");
  }

  private String blankToNull(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean canRetry(BookingBatchItemEntity item, TaskEntity task) {
    if (item.getLineStatus() == BookingBatchLineStatus.FAILED) return true;
    if (task == null) return true;
    return task.getStatus() == TaskStatus.CANCELLED;
  }

  private boolean canCancel(TaskEntity task) {
    if (task == null) return false;
    return task.getStatus() == TaskStatus.SEARCHING
        || task.getStatus() == TaskStatus.ASSIGNED
        || task.getStatus() == TaskStatus.ARRIVED
        || task.getStatus() == TaskStatus.STARTED;
  }

  private String toJson(BatchDtos.CreateItem line) {
    try {
      return objectMapper.writeValueAsString(line);
    } catch (Exception ignored) {
      return null;
    }
  }
}
