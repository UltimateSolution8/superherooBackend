package com.helpinminutes.api.helpers.dto;

import jakarta.validation.constraints.NotNull;

public record SetOnlineRequest(
    @NotNull Boolean online,
    Double lat,
    Double lng
) {}
