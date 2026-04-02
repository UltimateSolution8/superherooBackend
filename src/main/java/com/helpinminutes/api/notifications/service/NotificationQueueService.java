package com.helpinminutes.api.notifications.service;

import static com.helpinminutes.api.config.RabbitConfig.EXCHANGE_NOTIFICATIONS;
import static com.helpinminutes.api.config.RabbitConfig.ROUTING_KEY_NOTIFICATION_SEND;

import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.notifications.queue.NotificationType;
import com.helpinminutes.api.tasks.model.TaskEntity;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationQueueService {
    private static final Logger log = LoggerFactory.getLogger(NotificationQueueService.class);
    private final RabbitTemplate rabbitTemplate;

    public NotificationQueueService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void enqueueTaskOffered(List<UUID> helperIds, TaskEntity task) {
        if (helperIds == null || helperIds.isEmpty() || task == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.TASK_OFFERED, task.getId(), task.getBuyerId(), helperIds);
        publishAfterCommit(job);
    }

    public void enqueueTaskAccepted(UUID buyerId, TaskEntity task) {
        if (buyerId == null || task == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.TASK_ACCEPTED, task.getId(), buyerId, null);
        publishAfterCommit(job);
    }

    public void enqueueTaskCompleted(UUID buyerId, TaskEntity task) {
        if (buyerId == null || task == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.TASK_COMPLETED, task.getId(), buyerId, null);
        publishAfterCommit(job);
    }

    /**
     * Notify a set of helpers that a brand‑new task has been created and they should refresh.
     */
    public void enqueueTaskCreated(List<UUID> helperIds, TaskEntity task) {
        if (helperIds == null || helperIds.isEmpty() || task == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.TASK_CREATED, task.getId(), null, helperIds);
        publishAfterCommit(job);
    }

    public void enqueueKycApproved(UUID helperId) {
        if (helperId == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.KYC_APPROVED, null, null, List.of(helperId));
        publishAfterCommit(job);
    }

    private void publishAfterCommit(NotificationJob job) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(job);
                }
            });
        } else {
            publish(job);
        }
    }

    private void publish(NotificationJob job) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE_NOTIFICATIONS, ROUTING_KEY_NOTIFICATION_SEND, job);
            log.info("Notification job queued type={} taskId={} helperCount={}",
                    job.type(), job.taskId(), job.helperIds() == null ? 0 : job.helperIds().size());
        } catch (Exception e) {
            log.error("Failed to queue notification job type={} taskId={}", job.type(), job.taskId(), e);
        }
    }
}
