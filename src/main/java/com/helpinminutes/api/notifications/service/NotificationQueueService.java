package com.helpinminutes.api.notifications.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.notifications.outbox.NotificationOutboxDispatcher;
import com.helpinminutes.api.notifications.outbox.NotificationOutboxEntity;
import com.helpinminutes.api.notifications.outbox.NotificationOutboxRepository;
import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.notifications.queue.NotificationType;
import com.helpinminutes.api.tasks.model.TaskEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional producer for push-notification jobs.
 *
 * <p>The job row is committed with the task/offer state. Rabbit delivery happens
 * immediately after commit and is retried from the database if the process or
 * broker is unavailable. This removes the former commit-to-callback loss window.
 */
@Service
public class NotificationQueueService {
  private final NotificationOutboxRepository outbox;
  private final NotificationOutboxDispatcher dispatcher;
  private final ObjectMapper objectMapper;
  private final Executor executor;

  public NotificationQueueService(
      NotificationOutboxRepository outbox,
      NotificationOutboxDispatcher dispatcher,
      ObjectMapper objectMapper,
      @Qualifier("realtimeDispatchExecutor") Executor executor) {
    this.outbox = outbox;
    this.dispatcher = dispatcher;
    this.objectMapper = objectMapper;
    this.executor = executor;
  }

  public void enqueueTaskOffered(List<UUID> helperIds, TaskEntity task) {
    if (helperIds == null || helperIds.isEmpty() || task == null) return;
    enqueue(NotificationJob.now(
        NotificationType.TASK_OFFERED, task.getId(), task.getBuyerId(), helperIds));
  }

  /**
   * Durably schedules the first matching wave in the same transaction as the task.
   *
   * <p>This is intentionally carried by the transactional job outbox: an
   * {@code afterCommit} callback alone is lost if the API process exits between
   * committing the task and starting candidate discovery.
   */
  public void enqueueMatchingDispatch(TaskEntity task) {
    enqueueMatchingDispatch(task, true);
  }

  public void enqueueMatchingDispatch(TaskEntity task, boolean sendOfferNotifications) {
    if (task == null || task.getId() == null) return;
    enqueue(NotificationJob.matchingDispatch(
        task.getId(), task.getBuyerId(), Math.max(0, task.getDispatchWave()), sendOfferNotifications));
  }

  public void enqueueTaskAccepted(UUID buyerId, TaskEntity task) {
    if (buyerId == null || task == null) return;
    enqueue(NotificationJob.now(NotificationType.TASK_ACCEPTED, task.getId(), buyerId, null));
  }

  public void enqueueTaskCompleted(UUID buyerId, TaskEntity task) {
    if (buyerId == null || task == null) return;
    enqueue(NotificationJob.now(NotificationType.TASK_COMPLETED, task.getId(), buyerId, null));
  }

  public void enqueueKycApproved(UUID helperId) {
    if (helperId == null) return;
    enqueue(NotificationJob.now(NotificationType.KYC_APPROVED, null, null, List.of(helperId)));
  }

  public void enqueueMediatorJobAvailable(UUID batchId, List<UUID> mediatorIds) {
    if (batchId == null || mediatorIds == null || mediatorIds.isEmpty()) return;
    enqueue(NotificationJob.mediatorJobAvailable(batchId, mediatorIds));
  }

  private void enqueue(NotificationJob job) {
    NotificationOutboxEntity event = new NotificationOutboxEntity();
    event.setId(UUID.randomUUID());
    try {
      event.setJobJson(objectMapper.writeValueAsString(job));
    } catch (Exception e) {
      throw new IllegalArgumentException("Notification job is not serializable", e);
    }
    Instant now = Instant.now();
    event.setStatus("PENDING");
    event.setAttempts(0);
    event.setNextAttemptAt(now);
    event.setCreatedAt(now);
    event.setUpdatedAt(now);
    outbox.save(event);

    Runnable deliver = () -> executor.execute(() -> dispatcher.dispatchOne(event.getId()));
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          deliver.run();
        }
      });
    } else {
      deliver.run();
    }
  }
}
