package com.helpinminutes.api.tasks.repo;

import com.helpinminutes.api.tasks.model.TaskAiReviewEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskAiReviewRepository extends JpaRepository<TaskAiReviewEntity, UUID> {
  Optional<TaskAiReviewEntity> findTopByTaskIdOrderByCreatedAtDesc(UUID taskId);

  /**
   * Date-bounded, so the moderation report stops loading every row — including
   * every {@code raw_response} JSONB blob — and filtering by date in Java.
   */
  List<TaskAiReviewEntity> findAllByCreatedAtBetween(Instant start, Instant end);

  /**
   * Batched replacement for calling {@code findTopByTaskIdOrderByCreatedAtDesc}
   * once per row of an admin page. Returns every review for the given tasks,
   * newest first; callers reduce to the latest per task. Backed by
   * {@code idx_task_ai_reviews_task_id}.
   */
  List<TaskAiReviewEntity> findByTaskIdInOrderByCreatedAtDesc(Collection<UUID> taskIds);
}
