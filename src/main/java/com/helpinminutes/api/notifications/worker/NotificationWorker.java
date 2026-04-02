package com.helpinminutes.api.notifications.worker;

import static com.helpinminutes.api.config.RabbitConfig.QUEUE_NOTIFICATION_SEND;

import com.helpinminutes.api.common.GeoUtils;
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

    public NotificationWorker(PushNotificationService pushNotifications, TaskRepository tasks, HelperPresenceService presence) {
        this.pushNotifications = pushNotifications;
        this.tasks = tasks;
        this.presence = presence;
    }

    @RabbitListener(queues = QUEUE_NOTIFICATION_SEND)
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
                        Map<UUID, Double> distanceByHelper = new HashMap<>();
                        if (helperIds != null) {
                            for (UUID helperId : helperIds) {
                                var state = presence.getHelperState(helperId);
                                if (state == null) continue;
                                double dist = GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng());
                                distanceByHelper.put(helperId, dist);
                            }
                        }
                        pushNotifications.notifyTaskOffered(helperIds, task, distanceByHelper);
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
                case TASK_CREATED -> {
                    if (task != null) {
                        List<UUID> helperIds = job.helperIds();
                        Map<UUID, Double> distanceByHelper = new HashMap<>();
                        if (helperIds != null) {
                            for (UUID helperId : helperIds) {
                                var state = presence.getHelperState(helperId);
                                if (state == null) continue;
                                double dist = GeoUtils.distanceMeters(task.getLat(), task.getLng(), state.lat(), state.lng());
                                distanceByHelper.put(helperId, dist);
                            }
                        }
                        // just in case, reuse the offered notification logic which already filters tokens
                        pushNotifications.notifyTaskCreated(helperIds, task, distanceByHelper);
                    }
                }
                case KYC_APPROVED -> {
                    if (job.helperIds() != null && !job.helperIds().isEmpty()) {
                        pushNotifications.notifyHelperKycApproved(job.helperIds().get(0));
                    }
                }
                default -> log.warn("Unknown notification type {}", type);
            }
        } catch (Exception e) {
            log.error("Failed to process notification job type={} taskId={}", job.type(), job.taskId(), e);
        }
    }
}
