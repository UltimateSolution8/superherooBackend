package com.helpinminutes.api.payments.service;

import com.helpinminutes.api.batches.model.BookingBatchEntity;
import com.helpinminutes.api.batches.model.BookingBatchStatus;
import com.helpinminutes.api.batches.model.BatchPaymentMode;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.errors.ConflictException;
import com.helpinminutes.api.matching.MatchingService;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentLifecycleService {
  private static final Logger log = LoggerFactory.getLogger(PaymentLifecycleService.class);
  private static final int MAX_REFUND_ATTEMPTS = 10;

  private final PaymentRepository payments;
  private final TaskRepository tasks;
  private final BookingBatchRepository batches;
  private final MediatorJobWorkerRepository workers;
  private final MatchingService matching;
  private final RealtimePublisher realtime;
  private final PushNotificationService pushNotifications;
  private final RazorpayGateway razorpay;
  private final TransactionTemplate transactions;

  @Value("${PAYMENT_PENDING_EXPIRY_MINUTES:30}")
  private long paymentPendingExpiryMinutes = 30;

  public PaymentLifecycleService(
      PaymentRepository payments,
      TaskRepository tasks,
      BookingBatchRepository batches,
      MediatorJobWorkerRepository workers,
      MatchingService matching,
      RealtimePublisher realtime,
      PushNotificationService pushNotifications,
      RazorpayGateway razorpay,
      PlatformTransactionManager transactionManager) {
    this.payments = payments;
    this.tasks = tasks;
    this.batches = batches;
    this.workers = workers;
    this.matching = matching;
    this.realtime = realtime;
    this.pushNotifications = pushNotifications;
    this.razorpay = razorpay;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Transactional
  public void activateCapturedPayment(PaymentEntity payment) {
    if (payment == null || payment.getStatus() != PaymentStatus.CAPTURED) return;
    if (payment.getTaskId() != null) {
      TaskEntity task = tasks.findById(payment.getTaskId()).orElse(null);
      if (task != null && task.getStatus() == TaskStatus.CANCELLED) {
        queueRefund(payment, payment.getAmountPaise());
        return;
      }
      activateTask(payment.getTaskId());
    }
    if (payment.getBatchId() != null) {
      BookingBatchEntity batch = batches.findById(payment.getBatchId()).orElse(null);
      if (batch != null && batch.getStatus() == BookingBatchStatus.CANCELLED) {
        queueRefund(payment, payment.getAmountPaise());
        return;
      }
      activateBatch(payment.getBatchId());
    }
  }

  private void activateTask(UUID taskId) {
    TaskEntity task = tasks.findByIdForUpdate(taskId).orElse(null);
    if (task == null || task.getStatus() != TaskStatus.PAYMENT_PENDING) return;
    Instant now = Instant.now();
    boolean scheduled = task.getScheduledAt() != null && task.getScheduledAt().isAfter(now.plusSeconds(60));
    task.setStatus(scheduled ? TaskStatus.SCHEDULED_PENDING : TaskStatus.SEARCHING);
    tasks.save(task);

    if (!scheduled) {
      try {
        matching.dispatchOffers(task);
      } catch (Exception e) {
        log.error("Captured task {} could not be dispatched", taskId, e);
      }
    }
    publishTaskActivated(task);
  }

  private void activateBatch(UUID batchId) {
    BookingBatchEntity batch = batches.findAndLockById(batchId).orElse(null);
    if (batch == null || batch.getStatus() != BookingBatchStatus.PAYMENT_PENDING) return;
    batch.setStatus(BookingBatchStatus.PENDING_AUDIT);
    batches.save(batch);
    try {
      realtime.publish("mediator.job_pending_audit", Map.of(
          "batchId", batchId.toString(),
          "buyerId", batch.getCreatedByUserId().toString(),
          "helperCount", batch.getRequestedHelperCount() == null ? 1 : batch.getRequestedHelperCount()));
    } catch (Exception e) {
      log.warn("Could not publish paid bulk request {}", batchId, e);
    }
  }

  @Transactional
  public void bindHelper(UUID taskId, UUID helperId) {
    payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(
        taskId, java.util.List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED))
        .ifPresent(payment -> {
          payment.setHelperId(helperId);
          payments.save(payment);
        });
  }

  @Transactional
  public void bindMediator(UUID batchId, UUID mediatorId) {
    payments.findTopByBatchIdAndStatusInOrderByCreatedAtDesc(
        batchId, java.util.List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED))
        .ifPresent(payment -> {
          payment.setMediatorId(mediatorId);
          payments.save(payment);
        });
  }

  @Transactional
  public void releaseTaskEarning(TaskEntity task) {
    if (task == null || task.getPaymentCollectionMode() != PaymentCollectionMode.ONLINE_PREPAID) return;
    // Mediator-managed crew is settled from the consolidated batch payment.
    if (workers.findByTaskId(task.getId()).isPresent()) return;
    PaymentEntity payment = payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(
            task.getId(), java.util.List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED))
        .orElseThrow(() -> new ConflictException("Prepaid funds could not be verified"));
    payment.setHelperId(task.getAssignedHelperId());
    payment.setFulfillmentStatus(PaymentFulfillmentStatus.EARNED);
    if (payment.getEarningReleasedAt() == null) payment.setEarningReleasedAt(Instant.now());
    payments.save(payment);
  }

  @Transactional
  public void releaseBatchEarnings(BookingBatchEntity batch) {
    if (batch == null || batch.getPaymentCollectionMode() != PaymentCollectionMode.ONLINE_PREPAID) return;
    PaymentEntity payment = payments.findTopByBatchIdAndStatusInOrderByCreatedAtDesc(
            batch.getId(), java.util.List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED))
        .orElseThrow(() -> new ConflictException("Prepaid crew funds could not be verified"));
    payment.setMediatorId(batch.getPaymentMode() == BatchPaymentMode.CONSOLIDATED_MEDIATOR
        ? batch.getMediatorId()
        : null);
    long earnedPaise = workers.findByBatchId(batch.getId()).stream()
        .filter(worker -> "EARNED".equals(worker.getPaymentStatus())
            || "VIA_MEDIATOR".equals(worker.getPaymentStatus()))
        .map(worker -> worker.getPaymentAmountPaise() == null ? 0L : worker.getPaymentAmountPaise())
        .reduce(0L, Math::addExact);
    long refundPaise = Math.max(0L, payment.getAmountPaise() - earnedPaise);
    if (refundPaise > 0L) {
      queueRefund(payment, refundPaise);
      return;
    }
    markEarned(payment);
    payments.save(payment);
  }

  @Transactional
  public void requestTaskRefund(TaskEntity task) {
    if (task == null || task.getPaymentCollectionMode() != PaymentCollectionMode.ONLINE_PREPAID) return;
    payments.findTopByTaskIdAndStatusInOrderByCreatedAtDesc(
        task.getId(), java.util.List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED))
        .ifPresent(payment -> queueRefund(payment, payment.getAmountPaise()));
  }

  @Transactional
  public void requestBatchRefund(BookingBatchEntity batch) {
    if (batch == null || batch.getPaymentCollectionMode() != PaymentCollectionMode.ONLINE_PREPAID) return;
    payments.findTopByBatchIdAndStatusInOrderByCreatedAtDesc(
        batch.getId(), java.util.List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED))
        .ifPresent(payment -> queueRefund(payment, payment.getAmountPaise()));
  }

  private void queueRefund(PaymentEntity payment, long amountPaise) {
    if (payment.getFulfillmentStatus() == PaymentFulfillmentStatus.REFUNDED
        || payment.getFulfillmentStatus() == PaymentFulfillmentStatus.EARNED) return;
    long requested = Math.min(payment.getAmountPaise(), Math.max(1L, amountPaise));
    payment.setFulfillmentStatus(PaymentFulfillmentStatus.REFUND_PENDING);
    payment.setRefundRequestedAmountPaise(requested);
    if (payment.getRefundRequestedAt() == null) payment.setRefundRequestedAt(Instant.now());
    payment.setRefundLastError(null);
    payments.save(payment);
  }

  @Scheduled(fixedDelayString = "${PAYMENT_REFUND_RETRY_MS:60000}")
  public void processPendingRefunds() {
    for (PaymentEntity row : payments.findTop50ByFulfillmentStatusAndRefundAttemptsLessThanOrderByUpdatedAtAsc(
        PaymentFulfillmentStatus.REFUND_PENDING, MAX_REFUND_ATTEMPTS)) {
      // Scheduled calls do not pass through this bean's transactional proxy.
      // Give every refund its own transaction so one provider failure cannot block the batch.
      transactions.executeWithoutResult(ignored -> processRefund(row.getId()));
    }
  }

  @Transactional
  public void processRefund(UUID paymentId) {
    PaymentEntity payment = payments.findByIdForUpdate(paymentId).orElse(null);
    if (payment == null || payment.getFulfillmentStatus() != PaymentFulfillmentStatus.REFUND_PENDING) return;
    if (payment.getProviderPaymentId() == null || payment.getProviderPaymentId().isBlank()) {
      payment.setRefundAttempts(payment.getRefundAttempts() + 1);
      payment.setRefundLastError("Captured payment ID is unavailable");
      payments.save(payment);
      return;
    }
    long requested = payment.getRefundRequestedAmountPaise() == null
        ? payment.getAmountPaise()
        : payment.getRefundRequestedAmountPaise();
    long remaining = requested - Math.max(0L, payment.getAmountRefundedPaise());
    if (remaining <= 0) {
      completeRefundReconciliation(payment);
      return;
    }
    try {
      String receipt = "refund_" + payment.getId().toString().replace("-", "").substring(0, 20);
      RazorpayGateway.RefundResult refund = razorpay.refundPayment(payment.getProviderPaymentId(), remaining, receipt);
      payment.setRefundAttempts(payment.getRefundAttempts() + 1);
      payment.setRefundLastError(null);
      payment.setProviderRefundId(refund.id());
      payment.setFulfillmentStatus(PaymentFulfillmentStatus.REFUND_PROCESSING);
      payments.save(payment);
    } catch (Exception e) {
      payment.setRefundAttempts(payment.getRefundAttempts() + 1);
      payment.setRefundLastError(safeError(e.getMessage()));
      payments.save(payment);
      log.warn("Refund request failed for payment {} attempt {}", paymentId, payment.getRefundAttempts());
    }
  }

  public void completeRefundReconciliation(PaymentEntity payment) {
    if (payment.getAmountRefundedPaise() >= payment.getAmountPaise()) {
      payment.setStatus(PaymentStatus.REFUNDED);
      payment.setFulfillmentStatus(PaymentFulfillmentStatus.REFUNDED);
      payment.setRefundedAt(Instant.now());
    } else if (isCompletedTarget(payment)) {
      markEarned(payment);
    } else {
      payment.setFulfillmentStatus(PaymentFulfillmentStatus.HELD);
    }
    payments.save(payment);
  }

  @Scheduled(fixedDelayString = "${PAYMENT_PENDING_CLEANUP_MS:60000}")
  @Transactional
  public void expireUnpaidBookings() {
    Instant cutoff = Instant.now().minusSeconds(Math.max(10L, paymentPendingExpiryMinutes) * 60L);
    for (TaskEntity task : tasks.findTop100ByStatusAndCreatedAtBefore(TaskStatus.PAYMENT_PENDING, cutoff)) {
      task.setStatus(TaskStatus.CANCELLED);
      task.setCancelReason("Payment was not completed in time");
      task.setCancelledByRole("SYSTEM");
      task.setCancelledAt(Instant.now());
      tasks.save(task);
      publishCancelledTask(task);
    }
    for (BookingBatchEntity batch : batches.findTop100ByStatusAndCreatedAtBefore(
        BookingBatchStatus.PAYMENT_PENDING, cutoff)) {
      batch.setStatus(BookingBatchStatus.CANCELLED);
      batches.save(batch);
    }
  }

  private boolean isCompletedTarget(PaymentEntity payment) {
    if (payment.getTaskId() != null) {
      return tasks.findById(payment.getTaskId())
          .map(task -> task.getStatus() == TaskStatus.COMPLETED).orElse(false);
    }
    if (payment.getBatchId() != null) {
      return batches.findById(payment.getBatchId())
          .map(batch -> batch.getStatus() == BookingBatchStatus.MEDIATOR_COMPLETED).orElse(false);
    }
    return false;
  }

  private static void markEarned(PaymentEntity payment) {
    payment.setFulfillmentStatus(PaymentFulfillmentStatus.EARNED);
    if (payment.getEarningReleasedAt() == null) payment.setEarningReleasedAt(Instant.now());
  }

  private void publishCancelledTask(TaskEntity task) {
    try {
      realtime.publish("task_status_changed", Map.of(
          "taskId", task.getId().toString(),
          "buyerId", task.getBuyerId().toString(),
          "status", TaskStatus.CANCELLED.name()));
    } catch (Exception ignored) {}
  }

  private void publishTaskActivated(TaskEntity task) {
    try {
      realtime.publish("task_created", Map.of(
          "taskId", task.getId().toString(),
          "buyerId", task.getBuyerId().toString(),
          "title", task.getTitle(),
          "urgency", task.getUrgency().name(),
          "status", task.getStatus().name()));
    } catch (Exception ignored) {}
    try {
      pushNotifications.notifyTaskCreatedMonitor(task);
    } catch (Exception ignored) {}
  }

  private static String safeError(String message) {
    String value = message == null ? "Refund provider unavailable" : message.replaceAll("[\\r\\n]", " ").trim();
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}
