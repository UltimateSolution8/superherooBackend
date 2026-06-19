package com.helpinminutes.api.tasks.repo;

import com.helpinminutes.api.tasks.model.RecurringTaskEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecurringTaskRepository extends JpaRepository<RecurringTaskEntity, UUID> {}
