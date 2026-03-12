package com.helpinminutes.api.notifications.queue;

public enum NotificationType {
    TASK_OFFERED,
    TASK_ACCEPTED,
    TASK_COMPLETED,
    TASK_CREATED,    // helpers should be alerted when a new task is created
    KYC_APPROVED
}
