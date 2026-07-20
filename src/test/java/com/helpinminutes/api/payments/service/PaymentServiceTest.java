package com.helpinminutes.api.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.batches.model.BatchPaymentMode;
import com.helpinminutes.api.batches.model.BookingBatchEntity;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.errors.ConflictException;
import com.helpinminutes.api.mediator.model.MediatorAttendanceStatus;
import com.helpinminutes.api.mediator.model.MediatorJobWorkerEntity;
import com.helpinminutes.api.mediator.repo.MediatorJobWorkerRepository;
import com.helpinminutes.api.payments.dto.PaymentDtos.VerifyPaymentRequest;
import com.helpinminutes.api.payments.gateway.RazorpayGateway;
import com.helpinminutes.api.payments.model.PaymentEntity;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.payments.model.PaymentFulfillmentStatus;
import com.helpinminutes.api.payments.model.PaymentScope;
import com.helpinminutes.api.payments.model.PaymentStatus;
import com.helpinminutes.api.payments.model.PaymentWebhookEventEntity;
import com.helpinminutes.api.payments.repo.PaymentRepository;
import com.helpinminutes.api.payments.repo.PaymentAttemptRepository;
import com.helpinminutes.api.payments.repo.PaymentWebhookEventRepository;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Map;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentServiceTest {
  private PaymentRepository payments;
  private TaskRepository tasks;
  private UserRepository users;
  private BookingBatchRepository batches;
  private MediatorJobWorkerRepository workers;
  private PaymentAttemptRepository attempts;
  private PaymentWebhookEventRepository webhookEvents;
  private RazorpayGateway gateway;
  private PaymentService service;

  @Test
  void helperCanConfirmCashOnlyAfterACompletedPayLaterTask() {
    UUID buyerId = UUID.randomUUID();
    UUID helperId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 15_000L);
    task.setAssignedHelperId(helperId);
    task.setPaymentCollectionMode(PaymentCollectionMode.PAY_AFTER_SERVICE);
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(eq(task.getId()), any())).thenReturn(Optional.empty());
    AtomicReference<PaymentEntity> saved = new AtomicReference<>();
    when(payments.saveAndFlush(any(PaymentEntity.class))).thenAnswer(call -> {
      saved.set(call.getArgument(0));
      return call.getArgument(0);
    });

    var response = service.confirmDirectPayment(helperId, UserRole.HELPER, task.getId(), "cash");

    assertEquals("DIRECT", response.provider());
    assertEquals(PaymentFulfillmentStatus.EARNED, response.fulfillmentStatus());
    assertEquals(helperId, saved.get().getHelperId());
  }

  @BeforeEach
  void setUp() {
    payments = mock(PaymentRepository.class);
    tasks = mock(TaskRepository.class);
    users = mock(UserRepository.class);
    batches = mock(BookingBatchRepository.class);
    workers = mock(MediatorJobWorkerRepository.class);
    attempts = mock(PaymentAttemptRepository.class);
    webhookEvents = mock(PaymentWebhookEventRepository.class);
    gateway = mock(RazorpayGateway.class);
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(ignored -> new SimpleTransactionStatus());
    service = new PaymentService(
        payments,
        attempts,
        webhookEvents,
        tasks,
        batches,
        workers,
        users,
        gateway,
        new ObjectMapper(),
        manager,
        mock(PaymentLifecycleService.class));
    when(payments.save(any(PaymentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(attempts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createsOrderUsingServerTaskAmount() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 25_000L);
    UserEntity buyer = new UserEntity();
    buyer.setId(buyerId);
    buyer.setRole(UserRole.BUYER);
    buyer.setPhone("9000000101");
    buyer.setDisplayName("Test Buyer");

    AtomicReference<PaymentEntity> saved = new AtomicReference<>();
    when(gateway.isConfigured()).thenReturn(true);
    when(gateway.keyId()).thenReturn("rzp_test_key");
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(users.findById(buyerId)).thenReturn(Optional.of(buyer));
    when(payments.findByBuyerIdAndIdempotencyKey(eq(buyerId), any())).thenReturn(Optional.empty());
    when(payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(eq(task.getId()), any())).thenReturn(Optional.empty());
    when(payments.saveAndFlush(any(PaymentEntity.class))).thenAnswer(invocation -> {
      PaymentEntity entity = invocation.getArgument(0);
      saved.set(entity);
      return entity;
    });
    when(payments.findById(any(UUID.class))).thenAnswer(ignored -> Optional.ofNullable(saved.get()));
    when(payments.save(any(PaymentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(gateway.createOrder(eq(25_000L), eq("INR"), any(), any(Map.class)))
        .thenReturn(new RazorpayGateway.OrderResult("order_test", 25_000L, "INR", "created"));

    var response = service.createTaskOrder(buyerId, UserRole.BUYER, task.getId(), "mobile:test:12345678");

    assertEquals(25_000L, response.amount());
    assertEquals("order_test", response.orderId());
    assertEquals(PaymentStatus.CREATED, response.status());
    verify(gateway).createOrder(eq(25_000L), eq("INR"), any(), any(Map.class));
  }

  @Test
  void createsPrepaidOrderBeforeWorkWithoutDispatchingTheTask() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 19_900L);
    task.setStatus(TaskStatus.PAYMENT_PENDING);
    task.setPaymentCollectionMode(PaymentCollectionMode.ONLINE_PREPAID);
    UserEntity buyer = buyer(buyerId);
    AtomicReference<PaymentEntity> saved = new AtomicReference<>();

    when(gateway.isConfigured()).thenReturn(true);
    when(gateway.keyId()).thenReturn("rzp_test_key");
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(users.findById(buyerId)).thenReturn(Optional.of(buyer));
    when(payments.findByBuyerIdAndIdempotencyKey(eq(buyerId), any())).thenReturn(Optional.empty());
    when(payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(eq(task.getId()), any())).thenReturn(Optional.empty());
    when(payments.saveAndFlush(any(PaymentEntity.class))).thenAnswer(invocation -> {
      PaymentEntity entity = invocation.getArgument(0);
      saved.set(entity);
      return entity;
    });
    when(payments.findById(any(UUID.class))).thenAnswer(ignored -> Optional.ofNullable(saved.get()));
    when(gateway.createOrder(eq(19_900L), eq("INR"), any(), any(Map.class)))
        .thenReturn(new RazorpayGateway.OrderResult("order_prepaid", 19_900L, "INR", "created"));

    var response = service.createTaskOrder(
        buyerId, UserRole.BUYER, task.getId(), "mobile:prepaid:12345678");

    assertEquals("order_prepaid", response.orderId());
    assertEquals(TaskStatus.PAYMENT_PENDING, task.getStatus());
    assertEquals(PaymentStatus.CREATED, saved.get().getStatus());
  }

  @Test
  void blocksASecondOrderWhileAnotherCheckoutIsBeingPrepared() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 19_900L);
    task.setStatus(TaskStatus.PAYMENT_PENDING);
    task.setPaymentCollectionMode(PaymentCollectionMode.ONLINE_PREPAID);
    PaymentEntity creating = taskPayment(task, null, PaymentStatus.CREATING);
    ReflectionTestUtils.setField(creating, "createdAt", Instant.now());

    when(gateway.isConfigured()).thenReturn(true);
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(users.findById(buyerId)).thenReturn(Optional.of(buyer(buyerId)));
    when(payments.findByBuyerIdAndIdempotencyKey(buyerId, "mobile:new-attempt:12345678"))
        .thenReturn(Optional.empty());
    when(payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(eq(task.getId()), any()))
        .thenReturn(Optional.empty(), Optional.of(creating));

    assertThrows(ConflictException.class, () -> service.createTaskOrder(
        buyerId, UserRole.BUYER, task.getId(), "mobile:new-attempt:12345678"));
    verify(gateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
  }

  @Test
  void rejectsInvalidSignatureWithoutFetchingProviderPayment() {
    UUID buyerId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    PaymentEntity payment = new PaymentEntity();
    payment.setId(UUID.randomUUID());
    payment.setBuyerId(buyerId);
    payment.setTaskId(taskId);
    payment.setAmountPaise(10_000L);
    payment.setCurrency("INR");
    payment.setPaymentScope(PaymentScope.TASK);
    payment.setProviderOrderId("order_server");
    payment.setStatus(PaymentStatus.CREATED);
    when(payments.findByProviderOrderId("order_server")).thenReturn(Optional.of(payment));
    when(gateway.verifyPaymentSignature("order_server", "pay_fake", "bad_signature")).thenReturn(false);

    assertThrows(BadRequestException.class, () -> service.verify(
        buyerId,
        UserRole.BUYER,
        new VerifyPaymentRequest(taskId, "order_server", "pay_fake", "bad_signature")));
    verify(gateway, never()).fetchPayment(any());
  }

  @Test
  void rejectsPaymentBeforeTaskCompletion() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 10_000L);
    task.setStatus(TaskStatus.STARTED);
    when(gateway.isConfigured()).thenReturn(true);
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));

    assertThrows(BadRequestException.class, () -> service.createTaskOrder(
        buyerId, UserRole.BUYER, task.getId(), "mobile:test:not-complete"));
    verify(gateway, never()).createOrder(anyLong(), any(), any(), any());
  }

  @Test
  void createsConsolidatedOrderForCompletedMediatorBatchUsingExactTaskTotal() {
    UUID buyerId = UUID.randomUUID();
    UUID mediatorId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    UUID helperOne = UUID.randomUUID();
    UUID helperTwo = UUID.randomUUID();
    BookingBatchEntity batch = completedBatch(batchId, buyerId, mediatorId);
    MediatorJobWorkerEntity first = payableWorker(batchId, helperOne, 12_000L);
    MediatorJobWorkerEntity second = payableWorker(batchId, helperTwo, 18_000L);
    TaskEntity firstTask = completedTask(buyerId, 12_000L);
    firstTask.setAssignedHelperId(helperOne);
    TaskEntity secondTask = completedTask(buyerId, 18_000L);
    secondTask.setAssignedHelperId(helperTwo);
    first.setTaskId(firstTask.getId());
    second.setTaskId(secondTask.getId());
    UserEntity buyer = buyer(buyerId);
    AtomicReference<PaymentEntity> saved = new AtomicReference<>();

    when(gateway.isConfigured()).thenReturn(true);
    when(gateway.keyId()).thenReturn("rzp_test_key");
    when(batches.findAndLockById(batchId)).thenReturn(Optional.of(batch));
    when(workers.findByBatchId(batchId)).thenReturn(List.of(first, second));
    when(tasks.findById(firstTask.getId())).thenReturn(Optional.of(firstTask));
    when(tasks.findById(secondTask.getId())).thenReturn(Optional.of(secondTask));
    when(users.findById(buyerId)).thenReturn(Optional.of(buyer));
    when(payments.findByBuyerIdAndIdempotencyKey(eq(buyerId), any())).thenReturn(Optional.empty());
    when(payments.findTopByBatchIdAndStatusInOrderByCreatedAtDesc(eq(batchId), any())).thenReturn(Optional.empty());
    when(payments.saveAndFlush(any(PaymentEntity.class))).thenAnswer(invocation -> {
      PaymentEntity entity = invocation.getArgument(0);
      saved.set(entity);
      return entity;
    });
    when(payments.findById(any(UUID.class))).thenAnswer(ignored -> Optional.ofNullable(saved.get()));
    when(gateway.createOrder(eq(30_000L), eq("INR"), any(), any(Map.class)))
        .thenReturn(new RazorpayGateway.OrderResult("order_batch", 30_000L, "INR", "created"));

    var response = service.createBatchOrder(
        buyerId, UserRole.BUYER, batchId, "mobile:batch:completed:123");

    assertEquals(30_000L, response.amount());
    assertEquals(batchId, response.batchId());
    assertEquals(PaymentScope.MEDIATOR_BATCH, response.paymentScope());
    assertEquals(BatchPaymentMode.CONSOLIDATED_MEDIATOR, batch.getPaymentMode());
  }

  @Test
  void preventsMixingConsolidatedAndPerHelperPaymentModes() {
    UUID buyerId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    BookingBatchEntity batch = completedBatch(batchId, buyerId, UUID.randomUUID());
    batch.setPaymentMode(BatchPaymentMode.PER_HELPER);
    when(gateway.isConfigured()).thenReturn(true);
    when(batches.findAndLockById(batchId)).thenReturn(Optional.of(batch));

    assertThrows(ConflictException.class, () -> service.createBatchOrder(
        buyerId, UserRole.BUYER, batchId, "mobile:batch:mode:123456"));

    TaskEntity task = completedTask(buyerId, 10_000L);
    MediatorJobWorkerEntity worker = payableWorker(batchId, UUID.randomUUID(), 10_000L);
    worker.setTaskId(task.getId());
    batch.setPaymentMode(BatchPaymentMode.CONSOLIDATED_MEDIATOR);
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(workers.findByTaskId(task.getId())).thenReturn(Optional.of(worker));

    assertThrows(ConflictException.class, () -> service.createTaskOrder(
        buyerId, UserRole.BUYER, task.getId(), "mobile:task:mode:123456"));
  }

  @Test
  void verifiesCapturedProviderPaymentAndPersistsPaidState() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 10_000L);
    PaymentEntity payment = taskPayment(task, "order_verified", PaymentStatus.CREATED);
    when(payments.findByProviderOrderId("order_verified")).thenReturn(Optional.of(payment));
    when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(gateway.verifyPaymentSignature("order_verified", "pay_verified", "valid")).thenReturn(true);
    when(gateway.fetchPayment("pay_verified")).thenReturn(new RazorpayGateway.PaymentResult(
        "pay_verified", "order_verified", 10_000L, "INR", "captured", "upi", 0L, null, null));

    var result = service.verify(buyerId, UserRole.BUYER,
        new VerifyPaymentRequest(task.getId(), "order_verified", "pay_verified", "valid"));

    assertTrue(result.paid());
    assertEquals(PaymentStatus.CAPTURED, payment.getStatus());
    assertEquals("pay_verified", payment.getProviderPaymentId());
  }

  @Test
  void capturedPaymentIsNotRegressedByLateFailedWebhook() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 10_000L);
    PaymentEntity payment = taskPayment(task, "order_late", PaymentStatus.CAPTURED);
    configureWebhookEvent("evt_late_failed", "payment.failed");
    when(payments.findByProviderOrderId("order_late")).thenReturn(Optional.of(payment));

    String body = paymentWebhook("payment.failed", "pay_failed_retry", "order_late", "failed", 0L);
    service.processWebhook(body, "valid", "evt_late_failed");

    assertEquals(PaymentStatus.CAPTURED, payment.getStatus());
    assertFalse(payment.getProviderPaymentId().equals("pay_failed_retry"));
  }

  @Test
  void refundWebhookWithoutPaymentSnapshotFetchesProviderAndMarksRefunded() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 10_000L);
    PaymentEntity payment = taskPayment(task, "order_refund", PaymentStatus.CAPTURED);
    payment.setProviderPaymentId("pay_refund");
    configureWebhookEvent("evt_refund", "refund.processed");
    when(payments.findByProviderPaymentId("pay_refund")).thenReturn(Optional.of(payment));
    when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    when(gateway.fetchPayment("pay_refund")).thenReturn(new RazorpayGateway.PaymentResult(
        "pay_refund", "order_refund", 10_000L, "INR", "refunded", "upi", 10_000L, null, null));
    String body = "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"payment_id\":\"pay_refund\"}}}}";

    service.processWebhook(body, "valid", "evt_refund");

    assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    assertEquals(10_000L, payment.getAmountRefundedPaise());
  }

  @Test
  void retriesOrderCreationUsingTheSameIdempotencyIntent() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 15_000L);
    UserEntity buyer = buyer(buyerId);
    PaymentEntity failedIntent = taskPayment(task, null, PaymentStatus.FAILED);
    failedIntent.setIdempotencyKey("mobile:retry:12345678");
    failedIntent.setReceipt("task_retry_123456");
    failedIntent.setFailureCode("ORDER_CREATION_FAILED");
    AtomicReference<PaymentEntity> stored = new AtomicReference<>(failedIntent);

    when(gateway.isConfigured()).thenReturn(true);
    when(gateway.keyId()).thenReturn("rzp_test_key");
    when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
    when(users.findById(buyerId)).thenReturn(Optional.of(buyer));
    when(payments.findByBuyerIdAndIdempotencyKey(buyerId, "mobile:retry:12345678"))
        .thenReturn(Optional.of(failedIntent));
    when(payments.findById(failedIntent.getId())).thenAnswer(ignored -> Optional.of(stored.get()));
    when(payments.save(any(PaymentEntity.class))).thenAnswer(invocation -> {
      PaymentEntity entity = invocation.getArgument(0);
      stored.set(entity);
      return entity;
    });
    when(gateway.createOrder(eq(15_000L), eq("INR"), eq("task_retry_123456"), any(Map.class)))
        .thenReturn(new RazorpayGateway.OrderResult("order_retry", 15_000L, "INR", "created"));

    var response = service.createTaskOrder(
        buyerId, UserRole.BUYER, task.getId(), "mobile:retry:12345678");

    assertEquals("order_retry", response.orderId());
    assertEquals(PaymentStatus.CREATED, failedIntent.getStatus());
    assertEquals(null, failedIntent.getFailureCode());
    verify(payments, never()).saveAndFlush(any(PaymentEntity.class));
  }

  @Test
  void staleCapturedWebhookDoesNotUndoACompletedRefund() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 10_000L);
    PaymentEntity payment = taskPayment(task, "order_refunded", PaymentStatus.REFUNDED);
    payment.setAmountRefundedPaise(10_000L);
    configureWebhookEvent("evt_stale_capture", "payment.captured");
    when(payments.findByProviderOrderId("order_refunded")).thenReturn(Optional.of(payment));

    service.processWebhook(
        paymentWebhook("payment.captured", "pay_original", "order_refunded", "captured", 0L),
        "valid",
        "evt_stale_capture");

    assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    assertEquals(10_000L, payment.getAmountRefundedPaise());
  }

  @Test
  void rejectsWebhookBeforePersistingWhenSignatureIsInvalid() {
    when(gateway.verifyWebhookSignature(any(), eq("invalid"))).thenReturn(false);

    assertThrows(BadRequestException.class, () -> service.processWebhook(
        "{\"event\":\"payment.captured\"}", "invalid", "evt_invalid"));

    verify(webhookEvents, never()).saveAndFlush(any(PaymentWebhookEventEntity.class));
  }

  @Test
  void duplicateCapturedPaymentIsQueuedForAutomaticRefund() {
    UUID buyerId = UUID.randomUUID();
    TaskEntity task = completedTask(buyerId, 25_000L);
    PaymentEntity duplicate = taskPayment(task, "order_duplicate", PaymentStatus.CREATED);
    duplicate.setProviderPaymentId(null);
    PaymentEntity original = taskPayment(task, "order_original", PaymentStatus.CAPTURED);
    original.setId(UUID.randomUUID());
    original.setStatus(PaymentStatus.CAPTURED);
    original.setProviderPaymentId("pay_original");
    when(payments.findByProviderOrderId("order_duplicate")).thenReturn(Optional.of(duplicate));
    when(payments.findById(duplicate.getId())).thenReturn(Optional.of(duplicate));
    when(payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(eq(task.getId()), any()))
        .thenReturn(Optional.of(original));
    when(gateway.verifyPaymentSignature("order_duplicate", "pay_duplicate", "sig")).thenReturn(true);
    when(gateway.fetchPayment("pay_duplicate")).thenReturn(new RazorpayGateway.PaymentResult(
        "pay_duplicate", "order_duplicate", 25_000L, "INR", "captured", "upi", 0L, null, null));

    assertThrows(ConflictException.class, () -> service.verify(
        buyerId,
        UserRole.BUYER,
        new VerifyPaymentRequest(task.getId(), null, "order_duplicate", "pay_duplicate", "sig")));

    assertEquals(PaymentStatus.FAILED, duplicate.getStatus());
    assertEquals(PaymentFulfillmentStatus.REFUND_PENDING, duplicate.getFulfillmentStatus());
    assertEquals(25_000L, duplicate.getRefundRequestedAmountPaise());
    assertEquals("DUPLICATE_PAYMENT", duplicate.getFailureCode());
  }

  private static TaskEntity completedTask(UUID buyerId, long amountPaise) {
    TaskEntity task = new TaskEntity();
    task.setBuyerId(buyerId);
    task.setTitle("Test task");
    task.setDescription("Test task description");
    task.setUrgency(TaskUrgency.NORMAL);
    task.setTimeMinutes(30);
    task.setBudgetPaise(amountPaise);
    task.setStatus(TaskStatus.COMPLETED);
    task.prePersist();
    return task;
  }

  private static UserEntity buyer(UUID buyerId) {
    UserEntity buyer = new UserEntity();
    buyer.setId(buyerId);
    buyer.setRole(UserRole.BUYER);
    buyer.setPhone("9000000101");
    buyer.setDisplayName("Test Buyer");
    return buyer;
  }

  private static BookingBatchEntity completedBatch(UUID batchId, UUID buyerId, UUID mediatorId) {
    BookingBatchEntity batch = new BookingBatchEntity();
    batch.setId(batchId);
    batch.setCreatedByUserId(buyerId);
    batch.setMediatorId(mediatorId);
    batch.setStatus(BookingBatchStatus.MEDIATOR_COMPLETED);
    batch.setTitle("Completed crew job");
    return batch;
  }

  private static MediatorJobWorkerEntity payableWorker(UUID batchId, UUID helperId, long amount) {
    MediatorJobWorkerEntity worker = new MediatorJobWorkerEntity();
    worker.setBatchId(batchId);
    worker.setHelperId(helperId);
    worker.setAttendanceStatus(MediatorAttendanceStatus.PRESENT);
    worker.setPaymentAmountPaise(amount);
    return worker;
  }

  private static PaymentEntity taskPayment(TaskEntity task, String orderId, PaymentStatus status) {
    PaymentEntity payment = new PaymentEntity();
    payment.setId(UUID.randomUUID());
    payment.setTaskId(task.getId());
    payment.setBuyerId(task.getBuyerId());
    payment.setHelperId(task.getAssignedHelperId());
    payment.setPaymentScope(PaymentScope.TASK);
    payment.setAmountPaise(task.getBudgetPaise());
    payment.setCurrency("INR");
    payment.setProvider("RAZORPAY");
    payment.setProviderOrderId(orderId);
    payment.setProviderPaymentId("pay_original");
    payment.setStatus(status);
    return payment;
  }

  private void configureWebhookEvent(String eventId, String eventType) {
    PaymentWebhookEventEntity event = mock(PaymentWebhookEventEntity.class);
    UUID id = UUID.randomUUID();
    when(event.getId()).thenReturn(id);
    when(event.getStatus()).thenReturn("RECEIVED");
    when(webhookEvents.findByProviderEventId(eventId)).thenReturn(Optional.empty());
    when(webhookEvents.saveAndFlush(any(PaymentWebhookEventEntity.class))).thenReturn(event);
    when(webhookEvents.findById(id)).thenReturn(Optional.of(event));
    when(gateway.verifyWebhookSignature(any(), eq("valid"))).thenReturn(true);
  }

  private static String paymentWebhook(
      String event,
      String paymentId,
      String orderId,
      String status,
      long refunded) {
    return "{\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":{" +
        "\"id\":\"" + paymentId + "\",\"order_id\":\"" + orderId + "\"," +
        "\"amount\":10000,\"currency\":\"INR\",\"status\":\"" + status + "\"," +
        "\"method\":\"upi\",\"amount_refunded\":" + refunded + "}}}}";
  }
}
