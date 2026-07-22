package com.helpinminutes.api.moderation.dto;

public record AdminEditApproveRequest(
    String title,
    String description,
    String remarks
) {}
