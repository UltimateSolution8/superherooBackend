package com.helpinminutes.api.tasks.repo;

import com.helpinminutes.api.tasks.model.TaskAuditLogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskAuditLogRepository extends JpaRepository<TaskAuditLogEntity, UUID> {
  List<TaskAuditLogEntity> findByTaskIdOrderByTimestampDesc(UUID taskId);
}
