package com.helpinminutes.api.kyc.repository;

import com.helpinminutes.api.kyc.model.KycAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface KycAuditLogRepository extends JpaRepository<KycAuditLogEntity, Long> {
    List<KycAuditLogEntity> findByKycRequestIdOrderByCreatedAtDesc(UUID kycRequestId);
}
