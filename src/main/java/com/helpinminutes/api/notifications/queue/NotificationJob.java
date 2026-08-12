package com.helpinminutes.api.notifications.queue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationJob(
        NotificationType type,
        UUID taskId,
        UUID buyerId,
        List<UUID> helperIds,
        UUID batchId,
        Integer dispatchWave,
        Boolean sendOfferNotifications,
        Instant createdAt) {
    public static NotificationJob now(NotificationType type, UUID taskId, UUID buyerId, List<UUID> helperIds) {
        return new NotificationJob(type, taskId, buyerId, helperIds, null, null, null, Instant.now());
    }

    public static NotificationJob matchingDispatch(
            UUID taskId, UUID buyerId, int dispatchWave, boolean sendOfferNotifications) {
        return new NotificationJob(
                NotificationType.MATCHING_DISPATCH,
                taskId,
                buyerId,
                null,
                null,
                dispatchWave,
                sendOfferNotifications,
                Instant.now());
    }

    public static NotificationJob mediatorJobAvailable(UUID batchId, List<UUID> mediatorIds) {
        return new NotificationJob(
                NotificationType.MEDIATOR_JOB_AVAILABLE,
                null,
                null,
                mediatorIds,
                batchId,
                null,
                null,
                Instant.now());
    }
}
