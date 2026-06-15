package com.helpinminutes.api.chat.service;

import com.helpinminutes.api.chat.dto.TaskChatDtos.MessageResponse;
import com.helpinminutes.api.chat.model.TaskChatMessageEntity;
import com.helpinminutes.api.chat.repo.TaskChatMessageRepository;
import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.errors.NotFoundException;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskChatService {
  private final TaskRepository tasks;
  private final TaskChatMessageRepository messages;
  private final UserRepository users;

  public TaskChatService(TaskRepository tasks, TaskChatMessageRepository messages, UserRepository users) {
    this.tasks = tasks;
    this.messages = messages;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public List<MessageResponse> list(UUID taskId, UUID userId, UserRole role) {
    TaskEntity task = authorize(taskId, userId, role);
    List<TaskChatMessageEntity> rows = messages.findTop100ByTaskIdOrderByCreatedAtAsc(task.getId());
    Map<UUID, UserEntity> userMap = users.findAllById(rows.stream().map(TaskChatMessageEntity::getSenderUserId).distinct().toList())
        .stream().collect(Collectors.toMap(UserEntity::getId, u -> u));
    return rows.stream().map(m -> toResponse(m, userMap.get(m.getSenderUserId()))).toList();
  }

  @Transactional
  public MessageResponse send(UUID taskId, UUID userId, UserRole role, String text) {
    TaskEntity task = authorize(taskId, userId, role);
    String clean = text == null ? "" : text.trim();
    if (clean.isBlank()) {
      throw new com.helpinminutes.api.errors.BadRequestException("Message is required");
    }
    TaskChatMessageEntity row = new TaskChatMessageEntity();
    row.setTaskId(task.getId());
    row.setSenderUserId(userId);
    row.setSenderRole(role);
    row.setMessage(clean.length() > 1000 ? clean.substring(0, 1000) : clean);
    TaskChatMessageEntity saved = messages.save(row);
    UserEntity sender = users.findById(userId).orElse(null);
    return toResponse(saved, sender);
  }

  private TaskEntity authorize(UUID taskId, UUID userId, UserRole role) {
    TaskEntity task = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("Task not found"));
    boolean allowed = role == UserRole.ADMIN
        || (role == UserRole.BUYER && userId.equals(task.getBuyerId()))
        || (role == UserRole.HELPER && userId.equals(task.getAssignedHelperId()));
    if (!allowed) {
      throw new ForbiddenException("Not allowed");
    }
    return task;
  }

  private static MessageResponse toResponse(TaskChatMessageEntity m, UserEntity u) {
    String name = u != null && u.getDisplayName() != null && !u.getDisplayName().isBlank()
        ? u.getDisplayName()
        : (u != null && u.getPhone() != null ? u.getPhone() : null);
    return new MessageResponse(m.getId(), m.getTaskId(), m.getSenderUserId(), m.getSenderRole(), name, m.getMessage(), m.getCreatedAt());
  }
}
