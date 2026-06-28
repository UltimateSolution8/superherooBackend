package com.helpinminutes.api.chat.repo;

import com.helpinminutes.api.chat.model.TaskChatMessageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskChatMessageRepository extends JpaRepository<TaskChatMessageEntity, UUID> {
  List<TaskChatMessageEntity> findTop100ByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
