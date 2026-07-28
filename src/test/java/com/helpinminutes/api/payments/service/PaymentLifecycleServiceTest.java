package com.helpinminutes.api.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.batches.model.BatchPaymentMode;
import com.helpinminutes.api.batches.model.BookingBatchEntity;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.matching.MatchingService;
import com.helpinminutes.api.mediator.model.MediatorJobWorkerEntity;
import com.helpinminutes.api.mediator.repo.MediatorJobWorkerRepository;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.payments.gateway.RazorpayGateway;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.payments.model.PaymentEntity;
import com.helpinminutes.api.payments.model.PaymentFulfillmentStatus;
import com.helpinminutes.api.payments.model.PaymentStatus;
import com.helpinminutes.api.payments.repo.PaymentRepository;
import com.helpinminutes.api.realtime.RealtimePublisher;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class PaymentLifecycleServiceTest {
  private PaymentRepository payments;
  private TaskRepository tasks;
  private MatchingService matching;
  private RazorpayGateway razorpay;
  private BookingBatchRepository batches;
  private MediatorJobWorkerRepository workers;
  private PaymentLifecycleService service;
  private PlatformTransactionManager transactionManager;
  private org.springframework.context.ApplicationEventPublisher eventPublisher;
  private com.helpinminutes.api.moderation.service.AiTaskModerationService aiTaskModeration;

  @BeforeEach
  void setUp() {
    payments = mock(PaymentRepository.class);
    tasks = mock(TaskRepository.class);
    matching = mock(MatchingService.class);
    razorpay = mock(RazorpayGateway.class);
    batches = mock(BookingBatchRepository.class);
    workers = mock(MediatorJobWorkerRepository.class);
    transactionManager = mock(PlatformTransactionManager.class);
    eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
    aiTaskModeration = mock(com.helpinminutes.api.moderation.service.AiTaskModerationService.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    com.helpinminutes.api.batches.service.BookingBatchService bookingBatchService = mock(com.helpinminutes.api.batches.service.BookingBatchService.class);
    service = new PaymentLifecycleService(
        payments, tasks, batches, workers,
        matching, mock(RealtimePublisher.class), mock(PushNotificationService.class), razorpay,
        transactionManager, Runnable::run, eventPublisher, aiTaskModeration, bookingBatchService);
    when(payments.save(any(PaymentEntity.class))).thenAnswer(call -> call.getArgument(0));
  }

  @Test
  void capturedPrepaymentActivatesMatchingOnlyAfterCapture() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = task(taskId, TaskStatus.PAYMENT_PENDING);
    PaymentEntity payment = payment(taskId, PaymentStatus.CAPTURED, PaymentFulfillmentStatus.HELD);
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));
    when(tasks.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
    when(matching.dispatchOffers(task)).thenReturn(List.of());
    when(aiTaskModeration.moderateTaskSynchronously(task)).thenAnswer(invocation -> {
      TaskEntity t = invocation.getArgument(0);
      t.setStatus(TaskStatus.SEARCHING);
      return TaskStatus.SEARCHING;
    });

    service.activateCapturedPayment(payment);

    assertEquals(TaskStatus.SEARCHING, task.getStatus());
  }

  @Test
  void nonCapturedPaymentNeverActivatesTask() {
    UUID taskId = UUID.randomUUID();
    service.activateCapturedPayment(payment(taskId, PaymentStatus.CREATED, null));
    verify(tasks, never()).findByIdForUpdate(taskId);
  }

  @Test
  void completionReleasesHeldTaskEarningExactlyOnce() {
    UUID taskId = UUID.randomUUID();
    UUID helperId = UUID.randomUUID();
    TaskEntity task = task(taskId, TaskStatus.COMPLETED);
    task.setPaymentCollectionMode(PaymentCollectionMode.ONLINE_PREPAID);
    task.setAssignedHelperId(helperId);
    PaymentEntity payment = payment(taskId, PaymentStatus.CAPTURED, PaymentFulfillmentStatus.HELD);
    when(payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.of(payment));

    service.releaseTaskEarning(task);

    assertEquals(PaymentFulfillmentStatus.EARNED, payment.getFulfillmentStatus());
    assertEquals(helperId, payment.getHelperId());
  }

  @Test
  void successfulRefundSubmissionStopsAutomaticDuplicateRequests() {
    UUID taskId = UUID.randomUUID();
    PaymentEntity payment = payment(taskId, PaymentStatus.CAPTURED, PaymentFulfillmentStatus.REFUND_PENDING);
    payment.setProviderPaymentId("pay_test");
    when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
    when(razorpay.refundPayment("pay_test", payment.getAmountPaise(), "refund_" + payment.getId().toString().replace("-", "").substring(0, 20)))
        .thenReturn(new RazorpayGateway.RefundResult("rfnd_test", "pay_test", payment.getAmountPaise(), "processed"));

    service.processRefund(payment.getId());
    service.processRefund(payment.getId());

    assertEquals(PaymentFulfillmentStatus.REFUND_PROCESSING, payment.getFulfillmentStatus());
    verify(razorpay).refundPayment(any(), anyLong(), any());
  }

  @Test
  void scheduledRefundRunsInsideATransaction() {
    PaymentEntity payment = payment(UUID.randomUUID(), PaymentStatus.CAPTURED,
        PaymentFulfillmentStatus.REFUND_PENDING);
    payment.setProviderPaymentId("pay_scheduled");
    when(payments.findTop50ByFulfillmentStatusAndRefundAttemptsLessThanOrderByUpdatedAtAsc(
        PaymentFulfillmentStatus.REFUND_PENDING, 10)).thenReturn(List.of(payment));
    when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
    when(razorpay.refundPayment(any(), anyLong(), any()))
        .thenReturn(new RazorpayGateway.RefundResult(
            "rfnd_scheduled", "pay_scheduled", payment.getAmountPaise(), "processed"));

    service.processPendingRefunds();

    assertEquals(PaymentFulfillmentStatus.REFUND_PROCESSING, payment.getFulfillmentStatus());
    verify(transactionManager).getTransaction(any());
    verify(transactionManager).commit(any());
    verify(razorpay).refundPayment("pay_scheduled", payment.getAmountPaise(),
        "refund_" + payment.getId().toString().replace("-", "").substring(0, 20));
  }

  @Test
  void capturedScheduledPrepaymentWaitsUntilScheduledDispatch() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = task(taskId, TaskStatus.PAYMENT_PENDING);
    task.setScheduledAt(Instant.now().plusSeconds(7_200));
    PaymentEntity payment = payment(taskId, PaymentStatus.CAPTURED, PaymentFulfillmentStatus.HELD);
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));
    when(tasks.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
    when(aiTaskModeration.moderateTaskSynchronously(task)).thenAnswer(invocation -> {
      TaskEntity t = invocation.getArgument(0);
      t.setStatus(TaskStatus.SCHEDULED_PENDING);
      return TaskStatus.SCHEDULED_PENDING;
    });

    service.activateCapturedPayment(payment);

    assertEquals(TaskStatus.SCHEDULED_PENDING, task.getStatus());
  }

  @Test
  void absentCrewAmountIsQueuedAsPartialRefund() {
    UUID batchId = UUID.randomUUID();
    BookingBatchEntity batch = new BookingBatchEntity();
    ReflectionTestUtils.setField(batch, "id", batchId);
    batch.setStatus(BookingBatchStatus.MEDIATOR_COMPLETED);
    batch.setPaymentCollectionMode(PaymentCollectionMode.ONLINE_PREPAID);
    batch.setPaymentMode(BatchPaymentMode.PER_HELPER);
    PaymentEntity payment = payment(null, PaymentStatus.CAPTURED, PaymentFulfillmentStatus.HELD);
    payment.setBatchId(batchId);
    payment.setAmountPaise(30_000L);
    MediatorJobWorkerEntity presentA = worker(10_000L, "EARNED");
    MediatorJobWorkerEntity presentB = worker(10_000L, "EARNED");
    MediatorJobWorkerEntity absent = worker(0L, "SKIPPED");
    when(payments.findTopByBatchIdAndStatusInOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.of(payment));
    when(workers.findByBatchId(batchId)).thenReturn(List.of(presentA, presentB, absent));

    service.releaseBatchEarnings(batch);

    assertEquals(PaymentFulfillmentStatus.REFUND_PENDING, payment.getFulfillmentStatus());
    assertEquals(10_000L, payment.getRefundRequestedAmountPaise());
  }

  @Test
  void staleUnpaidTaskAndBatchExpireWithoutEnteringMatching() {
    TaskEntity task = task(UUID.randomUUID(), TaskStatus.PAYMENT_PENDING);
    BookingBatchEntity batch = new BookingBatchEntity();
    ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
    batch.setStatus(BookingBatchStatus.PAYMENT_PENDING);
    when(tasks.findTop100ByStatusAndCreatedAtBefore(any(), any())).thenReturn(List.of(task));
    when(batches.findTop100ByStatusAndCreatedAtBefore(any(), any())).thenReturn(List.of(batch));

    service.expireUnpaidBookings();

    assertEquals(TaskStatus.CANCELLED, task.getStatus());
    assertEquals(BookingBatchStatus.CANCELLED, batch.getStatus());
    verify(matching, never()).dispatchOffers(any(TaskEntity.class));
  }

  @Test
  void lateCaptureForExpiredTaskIsRefundedInsteadOfDispatched() {
    UUID taskId = UUID.randomUUID();
    TaskEntity task = task(taskId, TaskStatus.CANCELLED);
    task.setPaymentCollectionMode(PaymentCollectionMode.ONLINE_PREPAID);
    PaymentEntity payment = payment(taskId, PaymentStatus.CAPTURED, PaymentFulfillmentStatus.HELD);
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));

    service.activateCapturedPayment(payment);

    assertEquals(PaymentFulfillmentStatus.REFUND_PENDING, payment.getFulfillmentStatus());
    assertEquals(payment.getAmountPaise(), payment.getRefundRequestedAmountPaise());
    verify(tasks, never()).findByIdForUpdate(taskId);
    verify(matching, never()).dispatchOffers(any(TaskEntity.class));
  }

  private static MediatorJobWorkerEntity worker(long amountPaise, String status) {
    MediatorJobWorkerEntity worker = new MediatorJobWorkerEntity();
    worker.setPaymentAmountPaise(amountPaise);
    worker.setPaymentStatus(status);
    return worker;
  }

  private static TaskEntity task(UUID id, TaskStatus status) {
    TaskEntity task = new TaskEntity();
    ReflectionTestUtils.setField(task, "id", id);
    task.setBuyerId(UUID.randomUUID());
    task.setTitle("Test task");
    task.setStatus(status);
    return task;
  }

  private static PaymentEntity payment(UUID taskId, PaymentStatus status, PaymentFulfillmentStatus fulfillment) {
    PaymentEntity payment = new PaymentEntity();
    payment.setId(UUID.randomUUID());
    payment.setTaskId(taskId);
    payment.setAmountPaise(10_000L);
    payment.setCurrency("INR");
    payment.setStatus(status);
    payment.setFulfillmentStatus(fulfillment);
    return payment;
  }
}
