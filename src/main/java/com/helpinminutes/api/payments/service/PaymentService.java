package com.helpinminutes.api.payments.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.batches.model.BatchPaymentMode;
import com.helpinminutes.api.batches.model.BookingBatchEntity;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ConflictException;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.errors.PaymentProviderException;
import com.helpinminutes.api.payments.dto.PaymentDtos.CheckoutPrefill;
import com.helpinminutes.api.payments.dto.PaymentDtos.BatchPaymentLine;
import com.helpinminutes.api.payments.dto.PaymentDtos.BatchPaymentSummary;
import com.helpinminutes.api.payments.dto.PaymentDtos.CreateOrderResponse;
import com.helpinminutes.api.payments.dto.PaymentDtos.PaymentResponse;
import com.helpinminutes.api.payments.dto.PaymentDtos.VerifyPaymentRequest;
import com.helpinminutes.api.payments.gateway.RazorpayGateway;
import com.helpinminutes.api.payments.gateway.RazorpayGateway.OrderResult;
import com.helpinminutes.api.payments.gateway.RazorpayGateway.PaymentResult;
import com.helpinminutes.api.payments.gateway.RazorpayGatewayException;
import com.helpinminutes.api.payments.model.PaymentAttemptEntity;
import com.helpinminutes.api.payments.model.PaymentEntity;
import com.helpinminutes.api.payments.model.PaymentScope;
import com.helpinminutes.api.payments.model.PaymentStatus;
import com.helpinminutes.api.payments.model.PaymentWebhookEventEntity;
import com.helpinminutes.api.payments.repo.PaymentAttemptRepository;
import com.helpinminutes.api.payments.repo.PaymentRepository;
import com.helpinminutes.api.payments.repo.PaymentWebhookEventRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import com.helpinminutes.api.mediator.model.MediatorAttendanceStatus;
import com.helpinminutes.api.mediator.model.MediatorJobWorkerEntity;
import com.helpinminutes.api.mediator.repo.MediatorJobWorkerRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
  private static final String INR = "INR";
  private static final Set<PaymentStatus> PAID = EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);
  private static final Set<PaymentStatus> OPEN = EnumSet.of(PaymentStatus.CREATING, PaymentStatus.CREATED, PaymentStatus.AUTHORIZED);
  private static final Set<String> SUPPORTED_WEBHOOK_EVENTS = Set.of(
      "payment.captured", "payment.failed", "order.paid", "refund.processed", "refund.failed");
  private static final Duration CREATING_TIMEOUT = Duration.ofMinutes(2);

  private final PaymentRepository payments;
  private final PaymentAttemptRepository attempts;
  private final PaymentWebhookEventRepository webhookEvents;
  private final TaskRepository tasks;
  private final BookingBatchRepository batches;
  private final MediatorJobWorkerRepository mediatorWorkers;
  private final UserRepository users;
  private final RazorpayGateway razorpay;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate tx;

  public PaymentService(
      PaymentRepository payments,
      PaymentAttemptRepository attempts,
      PaymentWebhookEventRepository webhookEvents,
      TaskRepository tasks,
      BookingBatchRepository batches,
      MediatorJobWorkerRepository mediatorWorkers,
      UserRepository users,
      RazorpayGateway razorpay,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.payments = payments;
    this.attempts = attempts;
    this.webhookEvents = webhookEvents;
    this.tasks = tasks;
    this.batches = batches;
    this.mediatorWorkers = mediatorWorkers;
    this.users = users;
    this.razorpay = razorpay;
    this.objectMapper = objectMapper;
    this.tx = new TransactionTemplate(transactionManager);
  }

  public CreateOrderResponse createTaskOrder(UUID buyerId, UserRole role, UUID taskId, String rawIdempotencyKey) {
    requireBuyer(role);
    requireConfigured();
    String idempotencyKey = validateIdempotencyKey(rawIdempotencyKey);

    PreparedOrder prepared = tx.execute(status -> prepareTaskOrder(buyerId, taskId, idempotencyKey));
    if (prepared == null) throw new PaymentProviderException("Could not prepare payment");
    if (prepared.response() != null) return prepared.response();

    return createProviderOrder(prepared, Map.of(
        "scope", "task",
        "task_id", taskId.toString(),
        "payment_id", prepared.intent().getId().toString()));
  }

  public CreateOrderResponse createBatchOrder(UUID buyerId, UserRole role, UUID batchId, String rawIdempotencyKey) {
    requireBuyer(role);
    requireConfigured();
    String idempotencyKey = validateIdempotencyKey(rawIdempotencyKey);

    PreparedOrder prepared = tx.execute(status -> prepareBatchOrder(buyerId, batchId, idempotencyKey));
    if (prepared == null) throw new PaymentProviderException("Could not prepare payment");
    if (prepared.response() != null) return prepared.response();

    return createProviderOrder(prepared, Map.of(
        "scope", "mediator_batch",
        "batch_id", batchId.toString(),
        "payment_id", prepared.intent().getId().toString()));
  }

  private CreateOrderResponse createProviderOrder(PreparedOrder prepared, Map<String, String> notes) {

    PaymentEntity intent = prepared.intent();
    try {
      OrderResult order = razorpay.createOrder(
          intent.getAmountPaise(),
          intent.getCurrency(),
          intent.getReceipt(),
          notes);
      validateOrder(order, intent);
      return tx.execute(status -> {
        PaymentEntity current = payments.findById(intent.getId()).orElseThrow();
        current.setProviderOrderId(order.id());
        current.setStatus(PaymentStatus.CREATED);
        payments.save(current);
        return responseFor(current, prepared.user(), false);
      });
    } catch (RuntimeException ex) {
      tx.executeWithoutResult(status -> payments.findById(intent.getId()).ifPresent(payment -> {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureCode("ORDER_CREATION_FAILED");
        payment.setFailureDescription("Checkout could not be initialized");
        payment.setFailedAt(Instant.now());
        payments.save(payment);
      }));
      if (ex instanceof PaymentProviderException providerException) throw providerException;
      throw new PaymentProviderException("Razorpay order creation failed", ex);
    }
  }

  public PaymentResponse verify(UUID buyerId, UserRole role, VerifyPaymentRequest request) {
    requireBuyer(role);
    validateVerificationTarget(request);
    PaymentEntity stored = payments.findByProviderOrderId(request.razorpayOrderId())
        .orElseThrow(() -> new BadRequestException("Unknown payment order"));
    PaymentScope storedScope = stored.getPaymentScope() == null ? PaymentScope.TASK : stored.getPaymentScope();
    boolean targetMatches = storedScope == PaymentScope.TASK
        ? stored.getTaskId() != null && stored.getTaskId().equals(request.taskId()) && request.batchId() == null
        : stored.getBatchId() != null && stored.getBatchId().equals(request.batchId()) && request.taskId() == null;
    if (!stored.getBuyerId().equals(buyerId) || !targetMatches) {
      throw new ForbiddenException("Payment does not belong to this buyer and task");
    }
    if (PAID.contains(stored.getStatus())) return toResponse(stored, paymentTitle(stored));

    // Always sign the server-stored order id; never trust the order id as proof from the client.
    if (!razorpay.verifyPaymentSignature(stored.getProviderOrderId(), request.razorpayPaymentId(), request.razorpaySignature())) {
      throw new BadRequestException("Payment verification failed");
    }

    PaymentResult providerPayment;
    try {
      providerPayment = razorpay.fetchPayment(request.razorpayPaymentId());
      validateProviderPayment(stored, providerPayment);
      if ("authorized".equalsIgnoreCase(providerPayment.status())) {
        try {
          providerPayment = razorpay.capturePayment(providerPayment.id(), stored.getAmountPaise(), stored.getCurrency());
        } catch (RazorpayGatewayException captureFailure) {
          // Capture may have raced with auto-capture. Fetch once before treating it as pending.
          providerPayment = razorpay.fetchPayment(providerPayment.id());
        }
        validateProviderPayment(stored, providerPayment);
      }
    } catch (RazorpayGatewayException providerFailure) {
      throw new PaymentProviderException("Razorpay payment verification failed", providerFailure);
    }

    PaymentResult finalProviderPayment = providerPayment;
    PaymentResponse verified = tx.execute(status -> {
      PaymentEntity current = payments.findById(stored.getId()).orElseThrow();
      applyProviderState(current, finalProviderPayment);
      payments.save(current);
      return toResponse(current, paymentTitle(current));
    });
    if (verified == null || !verified.paid()) {
      throw new ConflictException("Payment is verified but capture is still pending");
    }
    return verified;
  }

  public PaymentResponse getForTask(UUID userId, UserRole role, UUID taskId) {
    TaskEntity task = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("Task not found"));
    requireTaskAccess(userId, role, task);
    return payments.findTopByTaskIdOrderByCreatedAtDesc(taskId)
        .map(payment -> toResponse(payment, task.getTitle()))
        .orElse(null);
  }

  public PaymentResponse getForBatch(UUID userId, UserRole role, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Bulk request not found"));
    requireBatchAccess(userId, role, batch);
    return payments.findTopByBatchIdOrderByCreatedAtDesc(batchId)
        .map(payment -> toResponse(payment, batch.getTitle()))
        .orElse(null);
  }

  public BatchPaymentSummary selectBatchPaymentMode(
      UUID buyerId,
      UserRole role,
      UUID batchId,
      BatchPaymentMode requestedMode) {
    requireBuyer(role);
    tx.executeWithoutResult(status -> {
      BookingBatchEntity batch = batches.findAndLockById(batchId)
          .orElseThrow(() -> new NotFoundException("Bulk request not found"));
      if (!batch.getCreatedByUserId().equals(buyerId)) {
        throw new ForbiddenException("Only the bulk request buyer can choose payment mode");
      }
      requireCompletedMediatorBatch(batch);
      List<UUID> taskIds = mediatorTaskIds(batchId);
      boolean hasPayments = payments.existsByBatchId(batchId)
          || (!taskIds.isEmpty() && payments.existsByTaskIdIn(taskIds));
      if (hasPayments && batch.getPaymentMode() != requestedMode) {
        throw new ConflictException("Payment mode cannot be changed after checkout has started");
      }
      batch.setPaymentMode(requestedMode);
      batches.save(batch);
    });
    return batchPaymentSummary(buyerId, role, batchId);
  }

  public BatchPaymentSummary batchPaymentSummary(UUID userId, UserRole role, UUID batchId) {
    BookingBatchEntity batch = batches.findById(batchId)
        .orElseThrow(() -> new NotFoundException("Bulk request not found"));
    requireBatchAccess(userId, role, batch);

    List<MediatorJobWorkerEntity> workers = mediatorWorkers.findByBatchId(batchId).stream()
        .filter(worker -> worker.getAttendanceStatus() == MediatorAttendanceStatus.PRESENT)
        .filter(worker -> worker.getTaskId() != null)
        .toList();
    List<UUID> taskIds = workers.stream().map(MediatorJobWorkerEntity::getTaskId).toList();
    List<PaymentEntity> taskPaymentRows = taskIds.isEmpty() ? List.of() : payments.findByTaskIdIn(taskIds);
    Map<UUID, PaymentEntity> latestByTask = taskPaymentRows.stream()
        .collect(Collectors.toMap(
            PaymentEntity::getTaskId,
            payment -> payment,
            (left, right) -> left.getCreatedAt().isAfter(right.getCreatedAt()) ? left : right));
    Map<UUID, TaskEntity> taskById = tasks.findAllById(taskIds).stream()
        .collect(Collectors.toMap(TaskEntity::getId, task -> task));
    Map<UUID, UserEntity> helperById = users.findAllById(
            workers.stream().map(MediatorJobWorkerEntity::getHelperId).collect(Collectors.toSet()))
        .stream().collect(Collectors.toMap(UserEntity::getId, user -> user));

    List<BatchPaymentLine> lines = workers.stream().map(worker -> {
      TaskEntity task = taskById.get(worker.getTaskId());
      PaymentEntity payment = latestByTask.get(worker.getTaskId());
      UserEntity helper = helperById.get(worker.getHelperId());
      long amount = worker.getPaymentAmountPaise() == null ? 0L : worker.getPaymentAmountPaise();
      return new BatchPaymentLine(
          worker.getTaskId(),
          worker.getHelperId(),
          helper == null ? "Partner" : displayName(helper),
          amount,
          task == null || task.getStatus() == null ? "UNKNOWN" : task.getStatus().name(),
          payment == null ? null : toResponse(payment, task == null ? batch.getTitle() : task.getTitle()));
    }).toList();

    PaymentEntity consolidated = payments.findTopByBatchIdOrderByCreatedAtDesc(batchId).orElse(null);
    boolean locked = consolidated != null || !latestByTask.isEmpty();
    long total = lines.stream().mapToLong(BatchPaymentLine::amountPaise).sum();
    return new BatchPaymentSummary(
        batchId,
        batch.getTitle(),
        batch.getPaymentMode(),
        batch.getStatus() == BookingBatchStatus.MEDIATOR_COMPLETED,
        locked,
        total,
        consolidated == null ? null : toResponse(consolidated, batch.getTitle()),
        lines);
  }

  public List<PaymentResponse> history(UUID userId, UserRole role) {
    List<PaymentEntity> rows;
    if (role == UserRole.BUYER) {
      rows = payments.findTop100ByBuyerIdOrderByCreatedAtDesc(userId);
    } else if (role == UserRole.HELPER) {
      rows = payments.findTop100ByHelperIdOrderByCreatedAtDesc(userId);
    } else if (role == UserRole.MEDIATOR) {
      rows = payments.findTop100ByMediatorIdOrderByCreatedAtDesc(userId);
    } else {
      throw new ForbiddenException("Payment history is not available for this role");
    }
    Set<UUID> taskIds = rows.stream().map(PaymentEntity::getTaskId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    Set<UUID> batchIds = rows.stream().map(PaymentEntity::getBatchId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    Map<UUID, String> titles = tasks.findAllById(taskIds)
        .stream().collect(Collectors.toMap(TaskEntity::getId, TaskEntity::getTitle));
    Map<UUID, String> batchTitles = batches.findAllById(batchIds)
        .stream().collect(Collectors.toMap(BookingBatchEntity::getId, BookingBatchEntity::getTitle));
    return rows.stream().map(row -> toResponse(
        row,
        row.getTaskId() == null ? batchTitles.get(row.getBatchId()) : titles.get(row.getTaskId()))).toList();
  }

  public void processWebhook(String rawBody, String signature, String eventId) {
    if (signature == null || signature.isBlank() || eventId == null || eventId.isBlank()) {
      throw new BadRequestException("Missing Razorpay webhook headers");
    }
    if (rawBody == null || rawBody.isBlank() || rawBody.length() > 1_000_000) {
      throw new BadRequestException("Invalid webhook payload size");
    }
    if (signature.length() > 256 || eventId.trim().length() > 128) {
      throw new BadRequestException("Invalid Razorpay webhook headers");
    }
    try {
      if (!razorpay.verifyWebhookSignature(rawBody, signature)) {
        throw new BadRequestException("Invalid webhook signature");
      }
    } catch (RazorpayGatewayException configurationFailure) {
      throw new PaymentProviderException("Razorpay webhook verification is unavailable", configurationFailure);
    }

    JsonNode payload;
    try {
      payload = objectMapper.readTree(rawBody);
    } catch (Exception e) {
      throw new BadRequestException("Invalid webhook payload");
    }
    String eventType = payload.path("event").asText("");
    if (eventType.isBlank()) throw new BadRequestException("Missing webhook event type");

    PaymentWebhookEventEntity event = beginWebhookEvent(eventId.trim(), eventType, sha256(rawBody));
    if ("PROCESSED".equals(event.getStatus())) return;

    try {
      if (SUPPORTED_WEBHOOK_EVENTS.contains(eventType)) {
        JsonNode providerPayment = payload.path("payload").path("payment").path("entity");
        if (!providerPayment.isMissingNode() && providerPayment.hasNonNull("order_id")) {
          applyWebhookPayment(eventType, providerPayment);
        } else if (eventType.startsWith("refund.")) {
          reconcileRefundWithoutPaymentSnapshot(payload);
        }
      }
      tx.executeWithoutResult(status -> {
        PaymentWebhookEventEntity current = webhookEvents.findById(event.getId()).orElseThrow();
        current.setStatus("PROCESSED");
        current.setErrorMessage(null);
        current.setProcessedAt(Instant.now());
        webhookEvents.save(current);
      });
    } catch (RuntimeException e) {
      tx.executeWithoutResult(status -> webhookEvents.findById(event.getId()).ifPresent(current -> {
        current.setStatus("FAILED");
        current.setErrorMessage(safeMessage(e.getMessage()));
        webhookEvents.save(current);
      }));
      throw e;
    }
  }

  private PreparedOrder prepareTaskOrder(UUID buyerId, UUID taskId, String idempotencyKey) {
    TaskEntity task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new NotFoundException("Task not found"));
    if (!task.getBuyerId().equals(buyerId)) throw new ForbiddenException("Only the task buyer can pay");
    if (task.getStatus() != TaskStatus.COMPLETED) {
      throw new BadRequestException("Payment is available after task completion");
    }
    mediatorWorkers.findByTaskId(taskId).ifPresent(worker -> {
      BookingBatchEntity batch = batches.findAndLockById(worker.getBatchId())
          .orElseThrow(() -> new NotFoundException("Bulk request not found"));
      requireCompletedMediatorBatch(batch);
      if (batch.getPaymentMode() == BatchPaymentMode.CONSOLIDATED_MEDIATOR) {
        throw new ConflictException("This bulk request uses one consolidated mediator payment");
      }
      if (batch.getPaymentMode() == null) {
        batch.setPaymentMode(BatchPaymentMode.PER_HELPER);
        batches.save(batch);
      }
    });
    long amount = task.getBudgetPaise() == null ? 0 : task.getBudgetPaise();
    if (amount < 100) throw new BadRequestException("Task amount must be at least ₹1.00");
    UserEntity user = users.findById(buyerId).orElseThrow(() -> new NotFoundException("Buyer not found"));

    PaymentEntity idempotent = payments.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey).orElse(null);
    if (idempotent != null) {
      if (!taskId.equals(idempotent.getTaskId())) throw new ConflictException("Idempotency key was already used");
      if (idempotent.getProviderOrderId() != null || PAID.contains(idempotent.getStatus())) {
        return new PreparedOrder(null, responseFor(idempotent, user, PAID.contains(idempotent.getStatus())), user);
      }
      if (idempotent.getStatus() == PaymentStatus.CREATING
          && idempotent.getCreatedAt().isAfter(Instant.now().minus(CREATING_TIMEOUT))) {
        throw new ConflictException("Payment checkout is being prepared. Please retry shortly");
      }
      resetOrderCreationIntent(idempotent);
      payments.save(idempotent);
      return new PreparedOrder(idempotent, null, user);
    }

    PaymentEntity paid = payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(taskId, PAID).orElse(null);
    if (paid != null) return new PreparedOrder(null, responseFor(paid, user, true), user);

    PaymentEntity open = payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(taskId, OPEN).orElse(null);
    if (open != null && open.getProviderOrderId() != null) {
      return new PreparedOrder(null, responseFor(open, user, false), user);
    }

    PaymentEntity intent = new PaymentEntity();
    intent.setId(UUID.randomUUID());
    intent.setTaskId(taskId);
    intent.setPaymentScope(PaymentScope.TASK);
    intent.setBuyerId(buyerId);
    intent.setHelperId(task.getAssignedHelperId());
    intent.setAmountPaise(amount);
    intent.setCurrency(INR);
    intent.setProvider("RAZORPAY");
    intent.setStatus(PaymentStatus.CREATING);
    intent.setIdempotencyKey(idempotencyKey);
    intent.setReceipt(receipt(taskId, intent.getId()));
    try {
      payments.saveAndFlush(intent);
    } catch (DataIntegrityViolationException race) {
      throw new ConflictException("Payment checkout is already being prepared");
    }
    return new PreparedOrder(intent, null, user);
  }

  private PreparedOrder prepareBatchOrder(UUID buyerId, UUID batchId, String idempotencyKey) {
    BookingBatchEntity batch = batches.findAndLockById(batchId)
        .orElseThrow(() -> new NotFoundException("Bulk request not found"));
    if (!batch.getCreatedByUserId().equals(buyerId)) {
      throw new ForbiddenException("Only the bulk request buyer can pay");
    }
    requireCompletedMediatorBatch(batch);
    if (batch.getPaymentMode() == BatchPaymentMode.PER_HELPER) {
      throw new ConflictException("This bulk request uses separate payments for each partner");
    }
    if (batch.getPaymentMode() == null) {
      batch.setPaymentMode(BatchPaymentMode.CONSOLIDATED_MEDIATOR);
      batches.save(batch);
    }

    List<MediatorJobWorkerEntity> payableWorkers = mediatorWorkers.findByBatchId(batchId).stream()
        .filter(worker -> worker.getAttendanceStatus() == MediatorAttendanceStatus.PRESENT)
        .filter(worker -> worker.getTaskId() != null)
        .toList();
    if (payableWorkers.isEmpty()) throw new BadRequestException("No completed partner work is payable");

    long amount = 0L;
    for (MediatorJobWorkerEntity worker : payableWorkers) {
      TaskEntity task = tasks.findById(worker.getTaskId())
          .orElseThrow(() -> new BadRequestException("A partner task is missing"));
      if (task.getStatus() != TaskStatus.COMPLETED
          || !buyerId.equals(task.getBuyerId())
          || !worker.getHelperId().equals(task.getAssignedHelperId())) {
        throw new ConflictException("Every present partner task must be completed before consolidated payment");
      }
      long lineAmount = worker.getPaymentAmountPaise() == null ? 0L : worker.getPaymentAmountPaise();
      if (lineAmount < 100 || !Long.valueOf(lineAmount).equals(task.getBudgetPaise())) {
        throw new ConflictException("Partner payment amount does not match the completed task");
      }
      amount = Math.addExact(amount, lineAmount);
    }
    if (amount < 100) throw new BadRequestException("Payment amount must be at least ₹1.00");
    UserEntity user = users.findById(buyerId).orElseThrow(() -> new NotFoundException("Buyer not found"));

    PaymentEntity idempotent = payments.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey).orElse(null);
    if (idempotent != null) {
      if (!batchId.equals(idempotent.getBatchId())) throw new ConflictException("Idempotency key was already used");
      if (idempotent.getProviderOrderId() != null || PAID.contains(idempotent.getStatus())) {
        return new PreparedOrder(null, responseFor(idempotent, user, PAID.contains(idempotent.getStatus())), user);
      }
      if (idempotent.getStatus() == PaymentStatus.CREATING
          && idempotent.getCreatedAt().isAfter(Instant.now().minus(CREATING_TIMEOUT))) {
        throw new ConflictException("Payment checkout is being prepared. Please retry shortly");
      }
      resetOrderCreationIntent(idempotent);
      payments.save(idempotent);
      return new PreparedOrder(idempotent, null, user);
    }

    PaymentEntity paid = payments.findTopByBatchIdAndStatusInOrderByCreatedAtDesc(batchId, PAID).orElse(null);
    if (paid != null) return new PreparedOrder(null, responseFor(paid, user, true), user);
    PaymentEntity open = payments.findTopByBatchIdAndStatusInOrderByCreatedAtDesc(batchId, OPEN).orElse(null);
    if (open != null && open.getProviderOrderId() != null) {
      return new PreparedOrder(null, responseFor(open, user, false), user);
    }

    PaymentEntity intent = new PaymentEntity();
    intent.setId(UUID.randomUUID());
    intent.setBatchId(batchId);
    intent.setMediatorId(batch.getMediatorId());
    intent.setPaymentScope(PaymentScope.MEDIATOR_BATCH);
    intent.setBuyerId(buyerId);
    intent.setAmountPaise(amount);
    intent.setCurrency(INR);
    intent.setProvider("RAZORPAY");
    intent.setStatus(PaymentStatus.CREATING);
    intent.setIdempotencyKey(idempotencyKey);
    intent.setReceipt(receipt("batch", batchId, intent.getId()));
    try {
      payments.saveAndFlush(intent);
    } catch (DataIntegrityViolationException race) {
      throw new ConflictException("Payment checkout is already being prepared");
    }
    return new PreparedOrder(intent, null, user);
  }

  private void applyWebhookPayment(String eventType, JsonNode node) {
    String orderId = node.path("order_id").asText("");
    if (orderId.isBlank()) return;
    tx.executeWithoutResult(status -> payments.findByProviderOrderId(orderId).ifPresent(payment -> {
      PaymentResult result = nodeToPayment(node);
      validateProviderPayment(payment, result);
      if ("payment.failed".equals(eventType)) {
        recordAttempt(payment, result);
        // A later failed retry for the same order must never regress an already
        // captured payment. Webhook delivery order is not guaranteed.
        if (!PAID.contains(payment.getStatus()) && payment.getStatus() != PaymentStatus.REFUNDED) {
          payment.setProviderPaymentId(result.id());
          payment.setMethod(safeMethod(result.method()));
          payment.setStatus(PaymentStatus.FAILED);
          payment.setFailureCode(safeCode(result.errorCode()));
          payment.setFailureDescription(safeMessage(result.errorDescription()));
          payment.setFailedAt(Instant.now());
        }
      } else {
        applyProviderState(payment, result);
      }
      payments.save(payment);
    }));
  }

  private void reconcileRefundWithoutPaymentSnapshot(JsonNode payload) {
    String providerPaymentId = payload.path("payload").path("refund").path("entity")
        .path("payment_id").asText("");
    if (providerPaymentId.isBlank()) return;
    PaymentEntity stored = payments.findByProviderPaymentId(providerPaymentId).orElse(null);
    if (stored == null) return;
    PaymentResult result;
    try {
      result = razorpay.fetchPayment(providerPaymentId);
    } catch (RazorpayGatewayException providerFailure) {
      throw new PaymentProviderException("Razorpay refund reconciliation failed", providerFailure);
    }
    validateProviderPayment(stored, result);
    tx.executeWithoutResult(status -> payments.findById(stored.getId()).ifPresent(payment -> {
      applyProviderState(payment, result);
      payments.save(payment);
    }));
  }

  private void applyProviderState(PaymentEntity payment, PaymentResult result) {
    recordAttempt(payment, result);
    long previousRefunded = Math.max(0, payment.getAmountRefundedPaise());
    long effectiveRefunded = Math.max(previousRefunded, Math.max(0, result.amountRefundedPaise()));
    Instant now = Instant.now();
    String status = result.status() == null ? "" : result.status().toLowerCase();

    // Provider callbacks are retried and may arrive out of order. Never allow an
    // older authorized/captured/failed snapshot to undo a captured refund state.
    if (payment.getStatus() == PaymentStatus.REFUNDED && effectiveRefunded < payment.getAmountPaise()) {
      return;
    }
    if (PAID.contains(payment.getStatus()) && ("authorized".equals(status) || "failed".equals(status))) {
      return;
    }

    payment.setProviderPaymentId(result.id());
    payment.setMethod(safeMethod(result.method()));
    payment.setAmountRefundedPaise(effectiveRefunded);
    if (effectiveRefunded >= payment.getAmountPaise()) {
      payment.setStatus(PaymentStatus.REFUNDED);
      payment.setRefundedAt(now);
    } else if ("captured".equals(status)) {
      payment.setStatus(effectiveRefunded > 0 ? PaymentStatus.PARTIALLY_REFUNDED : PaymentStatus.CAPTURED);
      if (payment.getPaidAt() == null) payment.setPaidAt(now);
      if (payment.getCapturedAt() == null) payment.setCapturedAt(now);
    } else if ("authorized".equals(status)) {
      payment.setStatus(PaymentStatus.AUTHORIZED);
    } else if ("refunded".equals(status)) {
      payment.setStatus(PaymentStatus.REFUNDED);
      payment.setRefundedAt(now);
    } else if ("failed".equals(status)) {
      payment.setStatus(PaymentStatus.FAILED);
      payment.setFailureCode(safeCode(result.errorCode()));
      payment.setFailureDescription(safeMessage(result.errorDescription()));
      payment.setFailedAt(now);
    }
  }

  private void validateOrder(OrderResult order, PaymentEntity payment) {
    if (order == null || order.id() == null || order.id().isBlank()) {
      throw new PaymentProviderException("Razorpay returned an invalid order");
    }
    if (order.amountPaise() != payment.getAmountPaise() || !payment.getCurrency().equalsIgnoreCase(order.currency())) {
      throw new PaymentProviderException("Razorpay order amount mismatch");
    }
  }

  private void validateProviderPayment(PaymentEntity stored, PaymentResult result) {
    if (result == null
        || !stored.getProviderOrderId().equals(result.orderId())
        || stored.getAmountPaise() != result.amountPaise()
        || !stored.getCurrency().equalsIgnoreCase(result.currency())) {
      throw new BadRequestException("Payment details do not match the order");
    }
  }

  private void recordAttempt(PaymentEntity payment, PaymentResult result) {
    if (result == null || result.id() == null || result.id().isBlank()) return;
    PaymentAttemptEntity attempt = attempts.findByProviderPaymentId(result.id()).orElseGet(PaymentAttemptEntity::new);
    if (attempt.getPaymentId() != null && !attempt.getPaymentId().equals(payment.getId())) {
      throw new BadRequestException("Provider payment is linked to a different order");
    }
    attempt.setPaymentId(payment.getId());
    attempt.setProviderPaymentId(result.id());
    String attemptStatus = safeAttemptStatus(result.status());
    if (result.amountPaise() > 0 && result.amountRefundedPaise() >= result.amountPaise()) {
      attemptStatus = "REFUNDED";
    } else if (result.amountRefundedPaise() > 0) {
      attemptStatus = "PARTIALLY_REFUNDED";
    }
    attempt.setStatus(attemptStatus);
    attempt.setMethod(safeMethod(result.method()));
    attempt.setAmountPaise(Math.max(0, result.amountPaise()));
    attempt.setAmountRefundedPaise(Math.max(0, result.amountRefundedPaise()));
    attempt.setCurrency(result.currency() == null ? INR : result.currency().toUpperCase(Locale.ROOT));
    attempt.setFailureCode(safeCode(result.errorCode()));
    attempt.setFailureDescription(safeMessage(result.errorDescription()));
    attempts.save(attempt);
  }

  private PaymentWebhookEventEntity beginWebhookEvent(String eventId, String eventType, String hash) {
    PaymentWebhookEventEntity existing = webhookEvents.findByProviderEventId(eventId).orElse(null);
    if (existing != null) {
      if (!hash.equals(existing.getPayloadSha256()) || !eventType.equals(existing.getEventType())) {
        throw new BadRequestException("Webhook event ID does not match its original payload");
      }
      return existing;
    }
    PaymentWebhookEventEntity event = new PaymentWebhookEventEntity();
    event.setProviderEventId(eventId);
    event.setEventType(eventType);
    event.setPayloadSha256(hash);
    event.setStatus("RECEIVED");
    try {
      return webhookEvents.saveAndFlush(event);
    } catch (DataIntegrityViolationException race) {
      return webhookEvents.findByProviderEventId(eventId).orElseThrow();
    }
  }

  private CreateOrderResponse responseFor(PaymentEntity payment, UserEntity user, boolean alreadyPaid) {
    return new CreateOrderResponse(
        payment.getId(), payment.getTaskId(), payment.getBatchId(), payment.getPaymentScope(),
        razorpay.keyId(), payment.getProviderOrderId(),
        payment.getAmountPaise(), payment.getCurrency(), payment.getReceipt(), payment.getStatus(), alreadyPaid,
        new CheckoutPrefill(user.getDisplayName(), user.getEmail(), normalizeContact(user.getPhone())));
  }

  private PaymentResponse toResponse(PaymentEntity payment, String title) {
    return new PaymentResponse(
        payment.getId(), payment.getTaskId(), payment.getBatchId(), payment.getPaymentScope(),
        title, payment.getAmountPaise(), payment.getCurrency(),
        payment.getProvider(), payment.getMethod(), payment.getStatus(), payment.getProviderPaymentId(),
        payment.getAmountRefundedPaise(), payment.getPaidAt(), payment.getCapturedAt(),
        payment.getCreatedAt(), payment.getUpdatedAt(), PAID.contains(payment.getStatus()));
  }

  private void requireTaskAccess(UUID userId, UserRole role, TaskEntity task) {
    if (role == UserRole.BUYER && task.getBuyerId().equals(userId)) return;
    if (role == UserRole.HELPER && userId.equals(task.getAssignedHelperId())) return;
    if (role == UserRole.ADMIN || role == UserRole.SUPPORT) return;
    throw new ForbiddenException("Not allowed to view this payment");
  }

  private void requireBatchAccess(UUID userId, UserRole role, BookingBatchEntity batch) {
    if (role == UserRole.BUYER && batch.getCreatedByUserId().equals(userId)) return;
    if (role == UserRole.MEDIATOR && userId.equals(batch.getMediatorId())) return;
    if (role == UserRole.ADMIN || role == UserRole.SUPPORT) return;
    throw new ForbiddenException("Not allowed to view this bulk payment");
  }

  private void requireBuyer(UserRole role) {
    if (role != UserRole.BUYER) throw new ForbiddenException("Only citizens can make task payments");
  }

  private void requireConfigured() {
    if (!razorpay.isConfigured()) throw new PaymentProviderException("Razorpay credentials are not configured");
  }

  private String paymentTitle(PaymentEntity payment) {
    if (payment.getTaskId() != null) {
      return tasks.findById(payment.getTaskId()).map(TaskEntity::getTitle).orElse("Task");
    }
    return batches.findById(payment.getBatchId()).map(BookingBatchEntity::getTitle).orElse("Bulk request");
  }

  private void validateVerificationTarget(VerifyPaymentRequest request) {
    if ((request.taskId() == null) == (request.batchId() == null)) {
      throw new BadRequestException("Provide exactly one payment target");
    }
  }

  private void requireCompletedMediatorBatch(BookingBatchEntity batch) {
    if (batch.getMediatorId() == null) {
      throw new BadRequestException("Consolidated payment is available only for mediator-managed bulk requests");
    }
    if (batch.getStatus() != BookingBatchStatus.MEDIATOR_COMPLETED) {
      throw new BadRequestException("Payment is available after bulk request completion");
    }
  }

  private List<UUID> mediatorTaskIds(UUID batchId) {
    return mediatorWorkers.findByBatchId(batchId).stream()
        .map(MediatorJobWorkerEntity::getTaskId)
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  private static String displayName(UserEntity user) {
    if (user == null || user.getDisplayName() == null || user.getDisplayName().isBlank()) return "Partner";
    return user.getDisplayName().trim();
  }

  private static String validateIdempotencyKey(String value) {
    String key = value == null ? "" : value.trim();
    if (key.length() < 8 || key.length() > 100 || !key.matches("[A-Za-z0-9._:-]+")) {
      throw new BadRequestException("Invalid Idempotency-Key header");
    }
    return key;
  }

  private static String receipt(UUID taskId, UUID paymentId) {
    return receipt("task", taskId, paymentId);
  }

  private static String receipt(String prefix, UUID targetId, UUID paymentId) {
    return prefix + "_" + targetId.toString().substring(0, 8) + "_" + paymentId.toString().substring(0, 12);
  }

  private static String normalizeContact(String phone) {
    if (phone == null || phone.isBlank()) return null;
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() == 10) return "+91" + digits;
    if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
    return phone;
  }

  private static String safeMessage(String value) {
    return safeText(value, 500);
  }

  private static String safeCode(String value) {
    return safeText(value, 80);
  }

  private static String safeMethod(String value) {
    return safeText(value, 30);
  }

  private static String safeText(String value, int maxLength) {
    if (value == null) return null;
    String clean = value.replaceAll("[\\r\\n]", " ").trim();
    return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
  }

  private static String safeAttemptStatus(String value) {
    String normalized = value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    return normalized.matches("[A-Z_]{1,30}") ? normalized : "UNKNOWN";
  }

  private static void resetOrderCreationIntent(PaymentEntity payment) {
    payment.setStatus(PaymentStatus.CREATING);
    payment.setProviderOrderId(null);
    payment.setProviderPaymentId(null);
    payment.setMethod(null);
    payment.setFailureCode(null);
    payment.setFailureDescription(null);
    payment.setFailedAt(null);
  }

  private static String sha256(String body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static PaymentResult nodeToPayment(JsonNode node) {
    return new PaymentResult(
        node.path("id").asText(null), node.path("order_id").asText(null), node.path("amount").asLong(),
        node.path("currency").asText(null), node.path("status").asText(null), node.path("method").asText(null),
        node.path("amount_refunded").asLong(0), node.path("error_code").asText(null),
        node.path("error_description").asText(null));
  }

  private record PreparedOrder(PaymentEntity intent, CreateOrderResponse response, UserEntity user) {}
}
