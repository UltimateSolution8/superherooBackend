package com.helpinminutes.api.reports.repo;

import com.helpinminutes.api.reports.model.AuditLogEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

  List<AuditLogEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(
      Instant start, Instant end, Pageable pageable);

  List<AuditLogEntity> findByActionTypeOrderByCreatedAtDesc(
      String actionType, Pageable pageable);

  long countByCreatedAtBetween(Instant start, Instant end);
}
