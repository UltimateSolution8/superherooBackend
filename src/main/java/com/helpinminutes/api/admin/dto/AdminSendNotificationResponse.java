package com.helpinminutes.api.admin.dto;

public record AdminSendNotificationResponse(
    int targetedUsers,
    int usersWithPushTokens,
    int deviceTokens,
    boolean queued) {}
