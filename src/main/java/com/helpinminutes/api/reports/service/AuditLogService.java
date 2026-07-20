package com.helpinminutes.api.reports.service;

import com.helpinminutes.api.reports.model.AuditLogEntity;
import com.helpinminutes.api.reports.repo.AuditLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {
  private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
  private final AuditLogRepository auditRepo;

  public AuditLogService(AuditLogRepository auditRepo) {
    this.auditRepo = auditRepo;
  }

  @Transactional
  public void logAction(
      UUID actorId,
      String actorEmail,
      String actorRole,
      String actionType,
      String targetResource,
      String targetId,
      String details,
      String ipAddress) {
    try {
      AuditLogEntity entry = new AuditLogEntity(
          actorId, actorEmail, actorRole, actionType, targetResource, targetId, details, ipAddress);
      auditRepo.save(entry);
      log.info("Audit logged: action={} actor={} role={}", actionType, actorEmail != null ? actorEmail : actorId, actorRole);
    } catch (Exception e) {
      log.error("Failed to record audit log: {}", e.getMessage());
    }
  }

  @Transactional(readOnly = true)
  public List<AuditLogEntity> getAuditLogs(Instant start, Instant end, int limit) {
    return auditRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(
        start, end, PageRequest.of(0, Math.min(500, Math.max(1, limit))));
  }

  @Transactional(readOnly = true)
  public long getCount(Instant start, Instant end) {
    return auditRepo.countByCreatedAtBetween(start, end);
  }
}
