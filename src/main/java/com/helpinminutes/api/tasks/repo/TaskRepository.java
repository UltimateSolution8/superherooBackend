package com.helpinminutes.api.tasks.repo;

import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskEscrowStatus;
import com.helpinminutes.api.tasks.model.TaskStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {
  Optional<TaskEntity> findByIdAndBuyerId(UUID id, UUID buyerId);
  java.util.List<TaskEntity> findTop100ByOrderByCreatedAtDesc();
  java.util.List<TaskEntity> findTop100ByStatusOrderByCreatedAtDesc(TaskStatus status);
  java.util.List<TaskEntity> findTop50ByEscrowStatusAndEscrowReleaseAtBefore(TaskEscrowStatus status, Instant releaseAt);

  @Modifying
  @Query(
      "update TaskEntity t set t.assignedHelperId = :helperId, t.status = :newStatus "
          + "where t.id = :taskId and t.assignedHelperId is null and t.status = :expectedStatus")
  int assignIfUnassigned(
      @Param("taskId") UUID taskId,
      @Param("helperId") UUID helperId,
      @Param("expectedStatus") TaskStatus expectedStatus,
      @Param("newStatus") TaskStatus newStatus);
}
