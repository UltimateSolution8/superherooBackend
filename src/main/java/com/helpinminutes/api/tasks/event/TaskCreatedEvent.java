package com.helpinminutes.api.tasks.event;

import java.util.UUID;

public record TaskCreatedEvent(
    UUID taskId,
    boolean sendOfferNotifications
) {}
