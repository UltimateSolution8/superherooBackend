package com.helpinminutes.api.kyc.repository;

import com.helpinminutes.api.kyc.model.KycRequestEntity;
import com.helpinminutes.api.kyc.model.KycRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KycRequestRepository extends JpaRepository<KycRequestEntity, UUID> {
    Optional<KycRequestEntity> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<KycRequestEntity> findByStatus(KycRequestStatus status, Pageable pageable);

    List<KycRequestEntity> findByRetentionExpiresAtBeforeAndRawResultIsNotNull(Instant before);
}
