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
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskOfferEntity;
import com.helpinminutes.api.tasks.model.TaskOfferStatus;
import com.helpinminutes.api.tasks.model.TaskSelfieStage;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskVerificationMode;
import com.helpinminutes.api.tasks.repo.TaskOfferRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.payments.service.PaymentLifecycleService;
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

  /**
   * Rows the pull feed reads before distance filtering, and how many it returns.
   *
   * <p>The scan limit has to exceed the return limit because the bounding-box query
   * is a superset of the circle — the corners get discarded.
   */
  private static final int PULL_FEED_SCAN_LIMIT = 300;
  private static final int PULL_FEED_RETURN_LIMIT = 60;
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
  private final PaymentLifecycleService paymentLifecycle;
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;
  private final com.helpinminutes.api.moderation.service.AiTaskModerationService aiTaskModeration;

  /**
   * Injected by field rather than constructor only because this class already has
   * a 22-argument constructor plus a legacy overload used by tests. Optional: when
   * absent (direct construction in a unit test) {@link #dispatchAsync} falls back
   * to running inline, which keeps test behaviour deterministic.
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  @org.springframework.beans.factory.annotation.Qualifier("realtimeDispatchExecutor")
  private java.util.concurrent.Executor dispatchExecutor;

  /**
   * Routing provider, for the ETAs on the pull feed. Field-injected for the same
   * reason as {@link #dispatchExecutor}: the constructor is already at 22 arguments
   * with a legacy overload the tests use. Absent in unit tests, where the feed
   * simply omits ETA rather than failing.
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.helpinminutes.api.geo.GeoProviderChain geo;

  /**
   * Books the partner's earning and the platform's commission on completion.
   *
   * Field-injected for the same reason as the two above. Absent in the unit tests
   * that construct this class directly, where the ledger is not what is under test —
   * the null check below is what makes that safe rather than a silent NPE inside a
   * status transition.
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.helpinminutes.api.payments.service.LedgerService ledger;

  /**
   * Fire-and-forget side effects (realtime publish, push notification) that must
   * not extend the request. Explicitly not {@code CompletableFuture.runAsync}:
   * that lands on the common ForkJoinPool, sized {@code availableProcessors() - 1}
   * — one thread on 2 vCPU — and these bodies do blocking HTTP and JDBC, so they
   * serialise behind each other.
   */
  private void dispatchAsync(Runnable body) {
    java.util.concurrent.Executor executor = this.dispatchExecutor;
    if (executor == null) {
      body.run();
      return;
    }
    executor.execute(body);
  }

  /** Runs a side effect only after the surrounding database commit is visible. */
  private void afterCommitAsync(Runnable body) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatchAsync(body);
            }
          });
      return;
    }
    dispatchAsync(body);
  }

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
      InvoiceEmailService invoiceEmail,
      PaymentLifecycleService paymentLifecycle) {
    this(tasks, offers, matching, realtime, storage, presence, props, users, helperProfiles, notificationQueue, pushNotifications, taskMapper, recurringTasks, taskModerationService, bookingBatches, bookingBatchItems, objectMapper, invoiceEmail, paymentLifecycle, null, event -> {});
  }

  @org.springframework.beans.factory.annotation.Autowired
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
      InvoiceEmailService invoiceEmail,
      PaymentLifecycleService paymentLifecycle,
      com.helpinminutes.api.moderation.service.AiTaskModerationService aiTaskModeration,
      org.springframework.context.ApplicationEventPublisher eventPublisher) {
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
    this.eventPublisher = eventPublisher;
    this.taskMapper = taskMapper;
    this.recurringTasks = recurringTasks;
    this.taskModerationService = taskModerationService;
    this.bookingBatches = bookingBatches;
    this.bookingBatchItems = bookingBatchItems;
    this.objectMapper = objectMapper;
    this.invoiceEmail = invoiceEmail;
    this.paymentLifecycle = paymentLifecycle;
    this.aiTaskModeration = aiTaskModeration;
  }

  @Transactional
  public CreateRecurringTaskResponse createRecurringTask(UUID buyerId, CreateRecurringTaskRequest req) {
    UserEntity buyer = users.findById(buyerId)
        .orElseThrow(() -> new ForbiddenException("Buyer not found"));
    requireVerifiedEmailForLaunchAction(buyer);
    if (!ServiceArea.isWithinHyderabad(req.lat(), req.lng())) {
      throw new BadRequestException(
          "Superherooo is currently available in Hyderabad only. Pick a location within the city to book.");
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
    CrewSchedulingPolicy.validate(req.helperCount(), firstOccurrence.get().toInstant(), Instant.now());

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

      realtime.publish("mediator.job_pending_audit", java.util.Map.of(
            "batchId", batch.getId().toString(),
            "buyerId", rec.getBuyerId().toString(),
            "helperCount", rec.getHelperCount()
        ));
    }
  }

  /**
   * Minimum useful title and description, mirrored from the app.
   *
   * <p>The app has enforced 3 and 10 characters since it shipped; the server did
   * not, so anything that was not the app could book "a" / "b" and a partner would
   * arrive at a job with no idea what it was. Hiding a rule in the client is
   * presentation, not enforcement.
   *
   * <p>Deliberately generous. This is a floor against a task nobody can act on, not
   * a quality bar — the AI moderation pass is what judges content.
   */
  static final int MIN_TITLE_CHARS = 3;
  static final int MIN_DESCRIPTION_CHARS = 10;

  /** Package-private so the rule can be tested without standing up the whole service. */
  static void requireUsableDetails(String title, String description) {
    if (title == null || title.trim().length() < MIN_TITLE_CHARS) {
      throw new BadRequestException(
          "Give the task a name of at least " + MIN_TITLE_CHARS + " characters.");
    }
    if (description == null || description.trim().length() < MIN_DESCRIPTION_CHARS) {
      throw new BadRequestException(
          "Describe the task in at least " + MIN_DESCRIPTION_CHARS + " characters so a partner "
              + "knows what they are accepting.");
    }
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
      throw new BadRequestException(
          "Superherooo is currently available in Hyderabad only. Pick a location within the city to book.");
    }
    requireUsableDetails(req.title(), req.description());
    // Safety check will run via AI moderation rather than throwing BadRequestException immediately

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
    PaymentCollectionMode paymentMode = requireSupportedPaymentMode(req.resolvedPaymentCollectionMode());
    task.setPaymentCollectionMode(paymentMode);
    task.setVerificationMode(req.resolvedVerificationMode());
    Instant now = Instant.now();
    Instant scheduledAt = req.scheduledAt();
    if (scheduledAt != null) {
      task.setScheduledAt(scheduledAt);
    }

    boolean awaitingPrepayment = paymentMode == PaymentCollectionMode.ONLINE_PREPAID;
    TaskStatus finalStatus = TaskStatus.AI_PENDING;

    task.setArrivalOtp(generateOtp());
    task.setCompletionOtp(generateOtp());

    if (awaitingPrepayment) {
      task.setStatus(TaskStatus.PAYMENT_PENDING);
      tasks.save(task);
    } else {
      tasks.save(task);
      // Run AI safety check synchronously!
      finalStatus = aiTaskModeration.moderateTaskSynchronously(task);
    }

    if (!awaitingPrepayment) {
      if (finalStatus == TaskStatus.SEARCHING) {
        // Commit a matching job atomically with the task. Rabbit delivery starts
        // immediately after commit; the database outbox retries across process or
        // broker restarts, closing the commit-to-callback loss window.
        notificationQueue.enqueueMatchingDispatch(task, resolvedOptions.sendOfferNotifications());
      }
    }

    if (!awaitingPrepayment) {
      // Transactional outbox write belongs inside the task transaction.
      realtime.publish(
          "task_created",
          java.util.Map.of(
              "taskId", task.getId().toString(),
              "buyerId", buyerId.toString(),
              "title", task.getTitle(),
              "urgency", task.getUrgency().name(),
              "status", task.getStatus().name()));
      afterCommitAsync(() -> {
        try {
          pushNotifications.notifyTaskCreatedMonitor(task);
        } catch (Exception ignored) {
        }
      });
    }

    // Dispatch runs from the durable job just after commit, so the creation
    // response does not wait on Redis/routing. Realtime and the pull feed remain
    // independent recovery paths.
    return new CreateResult(task.getId(), List.of());
  }

  @Transactional
  public TaskEntity createTaskForHelper(UUID buyerId, UUID helperId, CreateTaskRequest req) {
    // Row lock on the helper, matching acceptTask. The "already busy" check
    // below is a plain read followed by a write, so without serialising per
    // helper this path could assign a second concurrent task to someone who is
    // simultaneously accepting one through the normal offer flow.
    UserEntity helper = users.findByIdForUpdate(helperId)
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
    requireVerifiedEmailForLaunchAction(buyer);
    requireUsableDetails(req.title(), req.description());

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
    task.setPaymentCollectionMode(req.paymentCollectionMode() == null
        ? PaymentCollectionMode.PAY_AFTER_SERVICE
        : req.paymentCollectionMode());
    task.setVerificationMode(req.resolvedVerificationMode());
    Instant now = Instant.now();
    task.setScheduledAt(req.scheduledAt() != null ? req.scheduledAt() : now);
    task.setStatus(TaskStatus.ASSIGNED);
    task.setArrivalOtp(generateOtp());
    task.setCompletionOtp(generateOtp());

    tasks.save(task);

    // Both events are durable outbox writes in the assignment transaction.
    realtime.publish(
          "task_assigned",
          java.util.Map.of(
              "taskId", task.getId().toString(),
              "buyerId", buyerId.toString(),
              "helperId", helperId.toString(),
              "title", task.getTitle(),
              "status", task.getStatus().name()));
    notificationQueue.enqueueTaskOffered(java.util.List.of(helperId), task);

    return task;
  }

  @Transactional
  public TaskResponse acceptTask(UUID helperId, UUID taskId) {
    UserEntity helperUser = users.findByIdForUpdate(helperId)
        .orElseThrow(() -> new ForbiddenException("Helper not found"));
    requireVerifiedEmailForLaunchAction(helperUser);

    var profile = helperProfiles.findById(helperId)
        .orElseThrow(() -> new ForbiddenException("Helper profile not found"));
    if (profile.getKycStatus() != HelperKycStatus.APPROVED) {
      throw new ForbiddenException("KYC verification is required to accept tasks");
    }

    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    // If task is already assigned to this helper (e.g. direct admin assignment or idempotent retry):
    if (helperId.equals(task.getAssignedHelperId()) && task.getStatus() == TaskStatus.ASSIGNED) {
      paymentLifecycle.bindHelper(taskId, helperId);
      return taskMapper.toResponse(task, false);
    }

    if (tasks.existsByAssignedHelperIdAndStatusInAndIdNot(helperId, HELPER_ACTIVE_TASK_STATUSES, taskId)) {
      throw new ConflictException("Finish your current task before accepting another one");
    }

    // If task was assigned to this helper but status was still SEARCHING:
    if (helperId.equals(task.getAssignedHelperId()) && task.getStatus() == TaskStatus.SEARCHING) {
      task.setStatus(TaskStatus.ASSIGNED);
      tasks.save(task);
      paymentLifecycle.bindHelper(taskId, helperId);
      return taskMapper.toResponse(task, false);
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
      // Feeds the acceptance-rate term in candidate ranking. Only counted on the
      // offer path: a walk-up claim from the pull feed was never offered, so
      // crediting it would inflate the ratio above 1.
      profile.setOffersAccepted(profile.getOffersAccepted() + 1);
      helperProfiles.save(profile);
    } else {
      var state = presence.getHelperState(helperId);
      if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) {
        throw new ForbiddenException("Helper location is not available");
      }

      // Walk-up claim from the pull feed: no offer row exists, so the distance
      // check is the only gate. Bounded by the pull-feed radius, not by a push
      // wave — a partner who can see a job in their list must be able to take it,
      // otherwise the feed advertises work they cannot accept.
      double distMeters = GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng());
      if (distMeters > props.matching().pullFeedRadiusMeters()) {
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
    paymentLifecycle.bindHelper(taskId, helperId);

    return taskMapper.toResponse(task, false);
  }

  /**
   * Declines an offer.
   *
   * TaskOfferStatus.DECLINED existed but nothing ever wrote it — a partner could
   * only ignore an offer and wait out its TTL, which held a dispatch slot for
   * the full window. Declining frees the slot immediately and re-offers the job
   * to the next-nearest partner.
   */
  @Transactional
  public void declineOffer(UUID helperId, UUID taskId) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    int responded = offers.respond(
        taskId, helperId, TaskOfferStatus.OFFERED, TaskOfferStatus.DECLINED, Instant.now());
    if (responded == 0) {
      // Already accepted, expired or declined. Idempotent by design: a partner
      // double-tapping decline should not see an error.
      return;
    }

    // Only worth re-offering while the job is still looking for someone.
    if (task.getStatus() == TaskStatus.SEARCHING && task.getAssignedHelperId() == null) {
      // Queue in this transaction; do not hold the declining request open over
      // Redis discovery and routing. The expected-wave field makes duplicates safe.
      notificationQueue.enqueueMatchingDispatch(task);
    }
  }

  @Transactional
  public TaskResponse updateStatusAsHelper(UUID helperId, UUID taskId, TaskStatus newStatus, String otp) {
    TaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    if (task.getAssignedHelperId() == null || !task.getAssignedHelperId().equals(helperId)) {
      // If helper received an active offer for this task, auto-assign upon marking arrived/started
      var offerOpt = offers.findByTaskIdAndHelperId(taskId, helperId);
      if (offerOpt.isPresent()
          && (offerOpt.get().getStatus() == TaskOfferStatus.OFFERED || offerOpt.get().getStatus() == TaskOfferStatus.ACCEPTED)
          && !offerOpt.get().getExpiresAt().isBefore(Instant.now())) {
        int updated = tasks.assignIfUnassigned(taskId, helperId, TaskStatus.SEARCHING, TaskStatus.ASSIGNED);
        if (updated > 0 || helperId.equals(task.getAssignedHelperId())) {
          offers.respond(taskId, helperId, TaskOfferStatus.OFFERED, TaskOfferStatus.ACCEPTED, Instant.now());
          offers.expireOthers(taskId, TaskOfferStatus.OFFERED, TaskOfferStatus.EXPIRED, helperId);
          task.setAssignedHelperId(helperId);
          task.setStatus(TaskStatus.ASSIGNED);
          paymentLifecycle.bindHelper(taskId, helperId);
        } else {
          throw new ForbiddenException("Task already assigned to another helper");
        }
      } else {
        throw new ForbiddenException("Not assigned to this task");
      }
    }

    TaskStatus current = task.getStatus();
    if (!isValidHelperTransition(current, newStatus)) {
      throw new BadRequestException("Invalid status transition: " + current + " -> " + newStatus);
    }

    boolean requiresPhoto = task.getVerificationMode() != TaskVerificationMode.OTP_ONLY;
    if (requiresPhoto && newStatus == TaskStatus.ARRIVED && task.getArrivalSelfieUrl() == null) {
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
    if (requiresPhoto && newStatus == TaskStatus.COMPLETED && task.getCompletionSelfieUrl() == null) {
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

    tasks.save(task);

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
      paymentLifecycle.releaseTaskEarning(task);
      // Books the partner's earning and the platform's commission. Runs in its own
      // transaction and swallows a duplicate, so it can neither roll back the
      // completion nor double-count a retry.
      if (ledger != null) ledger.recordTaskCompletion(task);
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
    // AI_PENDING / ADMIN_REVIEW are included deliberately. A booking routed to
    // moderation review previously could not be cancelled by the citizen at
    // all — they were stuck waiting on a human with no way out. Load testing
    // surfaced this: every task the moderator queued became uncancellable.
    if (status != TaskStatus.PAYMENT_PENDING && status != TaskStatus.SCHEDULED_PENDING
        && status != TaskStatus.SEARCHING && status != TaskStatus.ASSIGNED
        && status != TaskStatus.AI_PENDING && status != TaskStatus.ADMIN_REVIEW
        && status != TaskStatus.AI_APPROVED && status != TaskStatus.ADMIN_APPROVED) {
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
    paymentLifecycle.requestTaskRefund(task);

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
    if (status != TaskStatus.PAYMENT_PENDING && status != TaskStatus.SCHEDULED_PENDING && status != TaskStatus.SEARCHING) {
      throw new BadRequestException("Task can only be rescheduled if pending or searching");
    }

    Instant now = Instant.now();
    CrewSchedulingPolicy.validate(1, newScheduledAt, now);

    task.setScheduledAt(newScheduledAt);
    task.setStatus(task.getPaymentCollectionMode() == PaymentCollectionMode.ONLINE_PREPAID
        && status == TaskStatus.PAYMENT_PENDING ? TaskStatus.PAYMENT_PENDING : TaskStatus.SCHEDULED_PENDING);
    tasks.save(task);

    realtime.publish(
          "task_status_changed",
          java.util.Map.of(
              "taskId", task.getId().toString(),
              "buyerId", task.getBuyerId().toString(),
              "status", TaskStatus.SCHEDULED_PENDING.name()));

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

  public boolean hasActiveOffer(UUID taskId, UUID helperId) {
    return offers.existsByTaskIdAndHelperIdAndStatusAndExpiresAtAfter(
        taskId, helperId, TaskOfferStatus.OFFERED, Instant.now());
  }

  public List<TaskEntity> listTasksForAdmin(TaskStatus status) {
    return status == null
        ? tasks.findTop100ByOrderByCreatedAtDesc()
        : tasks.findTop100ByStatusOrderByCreatedAtDesc(status);
  }

  public List<TaskEntity> listRecentTasks(int limit) {
    int size = Math.max(1, Math.min(50, limit));
    return tasks.findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, size)).getContent();
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

  /**
   * The partner's pull feed of open jobs, nearest first, with distance attached.
   *
   * <p>Deliberately far wider than a push wave ({@code pullFeedRadiusMeters}, 15km
   * by default, versus 3km for wave 0). Offers were push-only, so a job's whole
   * lifetime reached at most a handful of partners and the sixth-nearest never
   * learned it existed. Listing a job costs nothing per partner — only pushes do —
   * so the feed covers the service area and lets partners opt in.
   */
  public AvailableTasks listAvailableTasks(UUID helperId) {
    var profileOpt = helperProfiles.findById(helperId);
    if (profileOpt.isEmpty() || profileOpt.get().getKycStatus() != HelperKycStatus.APPROVED) {
      return AvailableTasks.empty();
    }

    var state = presence.getHelperState(helperId);
    if (state == null || !"1".equals(state.online()) || state.lastSeenEpochMs() == null) {
      return AvailableTasks.empty();
    }

    if (tasks.existsByAssignedHelperIdAndStatusIn(helperId, HELPER_ACTIVE_TASK_STATUSES)) {
      return AvailableTasks.empty();
    }

    Instant now = Instant.now();
    double radiusMeters = props.matching().pullFeedRadiusMeters();
    GeoUtils.BoundingBox box = GeoUtils.boundingBox(state.lat(), state.lng(), radiusMeters);

    // Bounding box narrows it in the index, then exact distance filters the corners.
    record Candidate(TaskEntity task, double distanceMeters) {}
    List<Candidate> nearby = tasks.findAvailableInBounds(
            TaskStatus.SEARCHING,
            helperId,
            now,
            box.minLat(),
            box.maxLat(),
            box.minLng(),
            box.maxLng(),
            org.springframework.data.domain.PageRequest.of(0, PULL_FEED_SCAN_LIMIT))
        .stream()
        .map(t -> new Candidate(
            t, GeoUtils.distanceMeters(t.getLat(), t.getLng(), state.lat(), state.lng())))
        .filter(candidate -> candidate.distanceMeters() <= radiusMeters)
        .sorted(java.util.Comparator.comparingDouble(Candidate::distanceMeters))
        .limit(PULL_FEED_RETURN_LIMIT)
        .toList();

    if (nearby.isEmpty()) {
      return AvailableTasks.empty();
    }

    java.util.Map<UUID, Double> distanceByTask = new java.util.LinkedHashMap<>();
    nearby.forEach(candidate -> distanceByTask.put(candidate.task().getId(), candidate.distanceMeters()));

    // One matrix call for the whole page: N route calls on a 20s poll would be the
    // most expensive thing in the app. Falls back to straight-line inside the chain.
    java.util.Map<UUID, Integer> etaByTask = new java.util.LinkedHashMap<>();
    if (geo != null) {
      List<Integer> etas = geo.etaSecondsToDestination(
          nearby.stream().map(c -> new double[] {c.task().getLat(), c.task().getLng()}).toList(),
          state.lat(),
          state.lng());
      for (int i = 0; i < nearby.size() && i < etas.size(); i++) {
        Integer etaSeconds = etas.get(i);
        if (etaSeconds != null && etaSeconds > 0) {
          etaByTask.put(nearby.get(i).task().getId(), Math.max(1, Math.round(etaSeconds / 60f)));
        }
      }
    }

    return new AvailableTasks(
        nearby.stream().map(Candidate::task).toList(), distanceByTask, etaByTask);
  }

  /**
   * The pull feed plus the per-task distance and ETA the partner app renders.
   *
   * <p>Carried alongside the entities rather than stuffed into them: distance is a
   * property of the viewer, not of the task.
   */
  public record AvailableTasks(
      List<TaskEntity> tasks,
      java.util.Map<UUID, Double> distanceMetersByTask,
      java.util.Map<UUID, Integer> etaMinutesByTask) {
    public static AvailableTasks empty() {
      return new AvailableTasks(List.of(), java.util.Map.of(), java.util.Map.of());
    }
  }

  @Transactional
  public TaskEntity updateStatusAsAdmin(UUID taskId, TaskStatus newStatus) {
    // Lock the row: this can release money, so it must not interleave with a
    // concurrent completion coming through the normal partner flow.
    TaskEntity task = tasks.findByIdForUpdate(taskId)
        .orElseThrow(() -> new NotFoundException("Task not found"));

    TaskStatus current = task.getStatus();
    if (current == newStatus) {
      // Idempotent. Critically, this stops a repeated "mark completed" from
      // calling releaseTaskEarning twice and paying a partner twice.
      return task;
    }
    if (!isValidAdminTransition(current, newStatus)) {
      throw new BadRequestException(
          "Cannot move a task from " + current + " to " + newStatus);
    }

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
      paymentLifecycle.releaseTaskEarning(task);
      invoiceEmail.sendInvoiceEmailAsync(task);
    }
    return task;
  }

  /**
   * Transitions an operator may force.
   *
   * Admins legitimately need to unstick tasks, so this is deliberately broader
   * than the partner state machine — but not unconstrained. Previously ANY
   * status could be applied from ANY other, including reviving a cancelled task
   * or re-completing a completed one (which released the earning again).
   */
  private static boolean isValidAdminTransition(TaskStatus from, TaskStatus to) {
    // Terminal states are terminal. Reopening them corrupts payment state.
    if (from == TaskStatus.CANCELLED || from == TaskStatus.COMPLETED) {
      return false;
    }
    // Cancelling is always allowed — it is the primary operator escape hatch.
    if (to == TaskStatus.CANCELLED) {
      return true;
    }
    return switch (from) {
      case AI_PENDING, ADMIN_REVIEW ->
          to == TaskStatus.AI_APPROVED || to == TaskStatus.ADMIN_APPROVED
              || to == TaskStatus.ADMIN_REJECTED || to == TaskStatus.SEARCHING;
      case AI_APPROVED, ADMIN_APPROVED, PAYMENT_PENDING, SCHEDULED_PENDING ->
          to == TaskStatus.SEARCHING;
      // An unassigned task cannot jump straight to work-in-progress states.
      case SEARCHING -> to == TaskStatus.SCHEDULED_PENDING;
      case ASSIGNED -> to == TaskStatus.ARRIVED || to == TaskStatus.STARTED;
      case ARRIVED -> to == TaskStatus.STARTED || to == TaskStatus.COMPLETED;
      case STARTED -> to == TaskStatus.COMPLETED;
      default -> false;
    };
  }

  private static boolean isValidHelperTransition(TaskStatus from, TaskStatus to) {
    return switch (from) {
      case SEARCHING -> to == TaskStatus.ASSIGNED || to == TaskStatus.ARRIVED;
      case ASSIGNED -> to == TaskStatus.ARRIVED || to == TaskStatus.STARTED;
      case ARRIVED -> to == TaskStatus.STARTED || to == TaskStatus.COMPLETED;
      case STARTED -> to == TaskStatus.COMPLETED;
      default -> false;
    };
  }

  private void requireVerifiedEmailForLaunchAction(UserEntity user) {
    if (user == null) return;
    if (user.getEmail() != null && !user.getEmail().isBlank() && !user.isEmailVerified()) {
      throw new ForbiddenException("Please verify your email before using launch bookings");
    }
  }

  private static final java.security.SecureRandom OTP_RNG = new java.security.SecureRandom();

  private static String generateOtp() {
    return String.valueOf(100000 + OTP_RNG.nextInt(900000));
  }

  /**
   * Rejects prepaid bookings while the online gateway is switched off.
   *
   * Enforced server-side rather than only hidden in the app: the client must not
   * be the thing that decides whether money can be taken. Fails with a clean 400
   * instead of the 500 the gateway would raise on missing credentials.
   */
  private PaymentCollectionMode requireSupportedPaymentMode(PaymentCollectionMode mode) {
    if (mode == PaymentCollectionMode.ONLINE_PREPAID && !props.payments().onlineEnabled()) {
      throw new BadRequestException(
          "Online payment is currently unavailable. Please choose pay after service "
              + "and settle in cash or UPI with your partner.");
    }
    return mode;
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

    task.setTimeMinutes(task.getTimeMinutes() + additionalTimeMinutes);
    task.setBudgetPaise(task.getBudgetPaise() + additionalBudgetPaise);
    tasks.save(task);

    realtime.publish(
          "task_status_changed",
          java.util.Map.of(
              "taskId", task.getId().toString(),
              "buyerId", task.getBuyerId().toString(),
              "status", task.getStatus().name()));

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
