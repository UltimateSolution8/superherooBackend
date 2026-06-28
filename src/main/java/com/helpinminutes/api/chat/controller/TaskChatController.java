package com.helpinminutes.api.chat.controller;

import com.helpinminutes.api.chat.dto.TaskChatDtos.MessageResponse;
import com.helpinminutes.api.chat.dto.TaskChatDtos.SendMessageRequest;
import com.helpinminutes.api.chat.service.TaskChatService;
import com.helpinminutes.api.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/chat/messages")
public class TaskChatController {
  private final TaskChatService chat;

  public TaskChatController(TaskChatService chat) {
    this.chat = chat;
  }

  @GetMapping
  public List<MessageResponse> list(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID taskId) {
    return chat.list(taskId, principal.userId(), principal.role());
  }

  @PostMapping
  public MessageResponse send(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID taskId,
      @Valid @RequestBody SendMessageRequest req) {
    return chat.send(taskId, principal.userId(), principal.role(), req.message());
  }
}
