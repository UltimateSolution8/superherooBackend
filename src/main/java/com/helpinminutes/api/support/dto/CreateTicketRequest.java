package com.helpinminutes.api.support.dto;

import com.helpinminutes.api.support.model.SupportTicketCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateTicketRequest(
    @NotNull SupportTicketCategory category,
    @Size(max = 140) String subject,
    @NotBlank @Size(max = 4000) String message,
    UUID relatedTaskId
) {}

