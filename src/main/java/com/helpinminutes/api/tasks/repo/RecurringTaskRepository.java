package com.helpinminutes.api.tasks.repo;

import com.helpinminutes.api.tasks.model.RecurringTaskEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecurringTaskRepository extends JpaRepository<RecurringTaskEntity, UUID> {
    java.util.List<RecurringTaskEntity> findAllByBuyerIdOrderByCreatedAtDesc(UUID buyerId);
    java.util.List<RecurringTaskEntity> findAllByStatus(com.helpinminutes.api.tasks.model.RecurringTaskStatus status);

    /**
     * Aggregated in SQL so the subscription report no longer has to load every
     * recurring task into heap just to total the budgets.
     */
    @org.springframework.data.jpa.repository.Query(
        "select coalesce(sum(r.budgetPaise), 0) from RecurringTaskEntity r")
    long sumBudgetPaise();
}
