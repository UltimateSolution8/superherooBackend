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
import org.springframework.data.domain.Pageable;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {
    Optional<TaskEntity> findByIdAndBuyerId(UUID id, UUID buyerId);

    java.util.List<TaskEntity> findTop100ByOrderByCreatedAtDesc();

    java.util.List<TaskEntity> findTop100ByStatusOrderByCreatedAtDesc(TaskStatus status);

    java.util.List<TaskEntity> findTop50ByStatusAndCreatedAtAfterOrderByCreatedAtDesc(TaskStatus status,
            Instant createdAt);

    java.util.List<TaskEntity> findTop50ByBuyerIdOrderByCreatedAtDesc(UUID buyerId);

    java.util.List<TaskEntity> findTop50ByAssignedHelperIdOrderByCreatedAtDesc(UUID helperId);

    java.util.List<TaskEntity> findTop50ByEscrowStatusAndEscrowReleaseAtBefore(TaskEscrowStatus status,
            Instant releaseAt);

    java.util.List<TaskEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(TaskStatus status);

    long countByAssignedHelperIdAndStatus(UUID helperId, TaskStatus status);

    long countByBuyerIdAndStatus(UUID buyerId, TaskStatus status);

    @Query("select avg(t.buyerRating) from TaskEntity t where t.assignedHelperId = :helperId and t.buyerRating is not null")
    Double avgBuyerRatingForHelper(@Param("helperId") UUID helperId);

    @Query("select avg(t.helperRating) from TaskEntity t where t.buyerId = :buyerId and t.helperRating is not null")
    Double avgHelperRatingForBuyer(@Param("buyerId") UUID buyerId);

    java.util.List<TaskEntity> findTop100ByStatusAndUpdatedAtBefore(TaskStatus status, Instant updatedAt);

    @Query("select coalesce(sum(t.budgetPaise), 0) from TaskEntity t where t.status = :status")
    long sumBudgetPaiseByStatus(@Param("status") TaskStatus status);

    java.util.List<TaskEntity> findTop50ByStatusAndScheduledAtBeforeAndAssignedHelperIdIsNullOrderByScheduledAtAsc(
            TaskStatus status,
            Instant scheduledAt);

    @Modifying
    @Query("update TaskEntity t set t.assignedHelperId = :helperId, t.status = :newStatus "
            + "where t.id = :taskId and t.assignedHelperId is null and t.status = :expectedStatus")
    int assignIfUnassigned(
            @Param("taskId") UUID taskId,
            @Param("helperId") UUID helperId,
            @Param("expectedStatus") TaskStatus expectedStatus,
            @Param("newStatus") TaskStatus newStatus);
}
