package com.helpinminutes.api.tasks.repo;

import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskEntity t where t.id = :taskId")
    Optional<TaskEntity> findByIdForUpdate(@Param("taskId") UUID taskId);
    Optional<TaskEntity> findByIdAndBuyerId(UUID id, UUID buyerId);

    java.util.List<TaskEntity> findTop100ByOrderByCreatedAtDesc();

    java.util.List<TaskEntity> findTop100ByStatusOrderByCreatedAtDesc(TaskStatus status);

    java.util.List<TaskEntity> findTop50ByStatusAndCreatedAtAfterOrderByCreatedAtDesc(TaskStatus status,
            Instant createdAt);

    java.util.List<TaskEntity> findTop50ByBuyerIdOrderByCreatedAtDesc(UUID buyerId);

    java.util.List<TaskEntity> findTop50ByAssignedHelperIdOrderByCreatedAtDesc(UUID helperId);

    java.util.List<TaskEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(TaskStatus status);

    long countByAssignedHelperIdAndStatus(UUID helperId, TaskStatus status);

    long countByBuyerIdAndStatus(UUID buyerId, TaskStatus status);

    boolean existsByAssignedHelperIdAndStatusIn(UUID helperId, Collection<TaskStatus> statuses);

    @Query("""
            select distinct t.assignedHelperId from TaskEntity t
            where t.assignedHelperId in :helperIds
              and t.status in :statuses
            """)
    java.util.List<UUID> findAssignedHelperIdsWithStatuses(
            @Param("helperIds") Collection<UUID> helperIds,
            @Param("statuses") Collection<TaskStatus> statuses);

    @Query("""
            select t from TaskEntity t
            where t.status = :status
              and t.assignedHelperId is null
              and (
                    (t.scheduledAt is null and t.createdAt <= :cutoff)
                 or (t.scheduledAt is not null and t.scheduledAt <= :cutoff)
              )
            order by t.createdAt asc
            """)
    java.util.List<TaskEntity> findTimedOutSearchingTasks(
            @Param("status") TaskStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

    @Query("select avg(t.buyerRating) from TaskEntity t where t.assignedHelperId = :helperId and t.buyerRating is not null")
    Double avgBuyerRatingForHelper(@Param("helperId") UUID helperId);

    @Query("select avg(t.helperRating) from TaskEntity t where t.buyerId = :buyerId and t.helperRating is not null")
    Double avgHelperRatingForBuyer(@Param("buyerId") UUID buyerId);

    @Query("""
            select t.assignedHelperId, count(t), avg(t.buyerRating)
            from TaskEntity t
            where t.assignedHelperId in :helperIds
              and t.status = :status
            group by t.assignedHelperId
            """)
    java.util.List<Object[]> findHelperStats(
            @Param("helperIds") Collection<UUID> helperIds,
            @Param("status") TaskStatus status);

    @Query("""
            select t.buyerId, count(t), avg(t.helperRating)
            from TaskEntity t
            where t.buyerId in :buyerIds
              and t.status = :status
            group by t.buyerId
            """)
    java.util.List<Object[]> findBuyerStats(
            @Param("buyerIds") Collection<UUID> buyerIds,
            @Param("status") TaskStatus status);

    java.util.List<TaskEntity> findTop100ByStatusAndUpdatedAtBefore(TaskStatus status, Instant updatedAt);

    java.util.List<TaskEntity> findTop100ByStatusAndCreatedAtBefore(TaskStatus status, Instant createdAt);

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

    java.util.List<TaskEntity> findByRecurringTaskIdAndStatus(UUID recurringTaskId, TaskStatus status);

    java.util.List<TaskEntity> findByRecurringTaskId(UUID recurringTaskId);
}
