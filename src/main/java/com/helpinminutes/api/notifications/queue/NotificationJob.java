package com.helpinminutes.api.notifications.queue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationJob(
        NotificationType type,
        UUID taskId,
        UUID buyerId,
        List<UUID> helperIds,
        Instant createdAt) {
    public static NotificationJob now(NotificationType type, UUID taskId, UUID buyerId, List<UUID> helperIds) {
        return new NotificationJob(type, taskId, buyerId, helperIds, Instant.now());
    }
}
