package com.helpinminutes.api.notifications.worker;

import static com.helpinminutes.api.config.RabbitConfig.QUEUE_NOTIFICATION_SEND;

import com.helpinminutes.api.common.GeoUtils;
import com.helpinminutes.api.batches.repo.BookingBatchRepository;
import com.helpinminutes.api.helpers.presence.HelperPresenceService;
import com.helpinminutes.api.notifications.queue.NotificationJob;
import com.helpinminutes.api.notifications.queue.NotificationType;
import com.helpinminutes.api.notifications.service.PushNotificationService;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final PushNotificationService pushNotifications;
    private final TaskRepository tasks;
    private final HelperPresenceService presence;
    private final BookingBatchRepository batches;

    public NotificationWorker(
            PushNotificationService pushNotifications,
            TaskRepository tasks,
            HelperPresenceService presence,
            BookingBatchRepository batches) {
        this.pushNotifications = pushNotifications;
        this.tasks = tasks;
        this.presence = presence;
        this.batches = batches;
    }

    @RabbitListener(
            queues = QUEUE_NOTIFICATION_SEND,
            concurrency = "${NOTIFICATION_LISTENER_CONCURRENCY:3-8}")
    public void handle(NotificationJob job) {
        if (job == null || job.type() == null) return;
        log.info("Processing notification job type={} taskId={} helperCount={}",
                job.type(), job.taskId(), job.helperIds() == null ? 0 : job.helperIds().size());

        TaskEntity task = null;
        if (job.taskId() != null) {
            task = tasks.findById(job.taskId()).orElse(null);
            if (task == null) {
                log.warn("Notification task {} not found for type {}", job.taskId(), job.type());
            }
        }

        try {
            NotificationType type = job.type();
            switch (type) {
                case TASK_OFFERED -> {
                    if (task != null) {
                        List<UUID> helperIds = job.helperIds();
                        pushNotifications.notifyTaskOffered(
                                helperIds, task, distancesToTask(helperIds, task));
                    }
                }
                case TASK_ACCEPTED -> {
                    if (task != null) {
                        UUID buyerId = job.buyerId() != null ? job.buyerId() : task.getBuyerId();
                        pushNotifications.notifyBuyerTaskAccepted(buyerId, task);
                    }
                }
                case TASK_COMPLETED -> {
                    if (task != null) {
                        UUID buyerId = job.buyerId() != null ? job.buyerId() : task.getBuyerId();
                        pushNotifications.notifyBuyerTaskCompleted(buyerId, task);
                    }
                }
                case KYC_APPROVED -> {
                    if (job.helperIds() != null && !job.helperIds().isEmpty()) {
                        pushNotifications.notifyHelperKycApproved(job.helperIds().get(0));
                    }
                }
                case MEDIATOR_JOB_AVAILABLE -> {
                    if (job.batchId() != null && job.helperIds() != null) {
                        batches.findById(job.batchId()).ifPresent(batch ->
                                pushNotifications.notifyMediatorBulkJobAvailable(batch, job.helperIds()));
                    }
                }
                default -> log.warn("Unknown notification type {}", type);
            }
        } catch (Exception e) {
            log.error("Failed to process notification job type={} taskId={}", job.type(), job.taskId(), e);
            // Do not acknowledge a failed delivery. Spring AMQP retries five
            // times (application.yml) and then rejects to notifications.dlq.
            // Swallowing here previously turned every transient FCM/database
            // failure into a permanently lost task offer.
            throw e instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Notification delivery failed", e);
        }
    }

    /**
     * Distance from each recipient to the task, for the notification copy.
     *
     * <p>One pipelined read. This was a loop calling {@code getHelperState} per
     * helper — a separate round trip each, against a network-remote Redis. The
     * recipient list is bounded by the matching wave's configured offer fanout.
     */
    private Map<UUID, Double> distancesToTask(List<UUID> helperIds, TaskEntity task) {
        if (helperIds == null || helperIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Double> distanceByHelper = new HashMap<>();
        presence.getHelperStates(helperIds).forEach((helperId, state) -> {
            if (state == null) return;
            distanceByHelper.put(
                    helperId,
                    GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng()));
        });
        return distanceByHelper;
    }
}
