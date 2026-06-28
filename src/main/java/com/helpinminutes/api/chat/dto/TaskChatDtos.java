package com.helpinminutes.api.chat.dto;

import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class TaskChatDtos {
  private TaskChatDtos() {}

  public record SendMessageRequest(@NotBlank @Size(max = 1000) String message) {}

  public record MessageResponse(
      UUID id,
      UUID taskId,
      UUID senderUserId,
      UserRole senderRole,
      String senderName,
      String message,
      Instant createdAt
  ) {}
}
