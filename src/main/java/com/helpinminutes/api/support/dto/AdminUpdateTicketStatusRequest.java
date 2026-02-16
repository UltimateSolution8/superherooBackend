package com.helpinminutes.api.support.dto;

import com.helpinminutes.api.support.model.SupportTicketStatus;
import jakarta.validation.constraints.NotNull;

public record AdminUpdateTicketStatusRequest(
    @NotNull SupportTicketStatus status
) {}

