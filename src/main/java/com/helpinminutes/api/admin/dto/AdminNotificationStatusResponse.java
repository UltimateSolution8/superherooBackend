package com.helpinminutes.api.admin.dto;

public record AdminNotificationStatusResponse(
    boolean firebaseReady,
    long registeredDeviceTokens,
    String deliveryMode) {}
