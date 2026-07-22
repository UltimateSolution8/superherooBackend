package com.helpinminutes.api.tasks.repo;

import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskAiReviewRepository extends JpaRepository<TaskAiReviewEntity, UUID> {
  Optional<TaskAiReviewEntity> findTopByTaskIdOrderByCreatedAtDesc(UUID taskId);
}
