package com.helpinminutes.api.notifications.service;

import static com.helpinminutes.api.config.RabbitConfig.EXCHANGE_NOTIFICATIONS;
import static com.helpinminutes.api.config.RabbitConfig.ROUTING_KEY_NOTIFICATION_SEND;

import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.notifications.queue.NotificationType;
import com.helpinminutes.api.tasks.model.TaskEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationQueueService {
    private final RabbitTemplate rabbitTemplate;

    public NotificationQueueService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void enqueueTaskOffered(List<UUID> helperIds, TaskEntity task) {
        if (helperIds == null || helperIds.isEmpty() || task == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.TASK_OFFERED, task.getId(), task.getBuyerId(), helperIds);
        rabbitTemplate.convertAndSend(EXCHANGE_NOTIFICATIONS, ROUTING_KEY_NOTIFICATION_SEND, job);
    }

    public void enqueueTaskAccepted(UUID buyerId, TaskEntity task) {
        if (buyerId == null || task == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.TASK_ACCEPTED, task.getId(), buyerId, null);
        rabbitTemplate.convertAndSend(EXCHANGE_NOTIFICATIONS, ROUTING_KEY_NOTIFICATION_SEND, job);
    }

    public void enqueueTaskCompleted(UUID buyerId, TaskEntity task) {
        if (buyerId == null || task == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.TASK_COMPLETED, task.getId(), buyerId, null);
        rabbitTemplate.convertAndSend(EXCHANGE_NOTIFICATIONS, ROUTING_KEY_NOTIFICATION_SEND, job);
    }

    public void enqueueKycApproved(UUID helperId) {
        if (helperId == null) return;
        NotificationJob job = NotificationJob.now(NotificationType.KYC_APPROVED, null, null, List.of(helperId));
        rabbitTemplate.convertAndSend(EXCHANGE_NOTIFICATIONS, ROUTING_KEY_NOTIFICATION_SEND, job);
    }
}
