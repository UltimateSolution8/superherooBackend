package com.helpinminutes.api.support.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AdminAssignTicketRequest(
    @NotNull UUID assigneeUserId
) {}

