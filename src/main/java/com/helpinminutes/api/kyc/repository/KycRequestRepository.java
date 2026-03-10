package com.helpinminutes.api.kyc.repository;

import com.helpinminutes.api.kyc.model.KycRequestEntity;
import com.helpinminutes.api.kyc.model.KycRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KycRequestRepository extends JpaRepository<KycRequestEntity, UUID> {
    Optional<KycRequestEntity> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<KycRequestEntity> findTop1ByLiveRecordTaskId(String liveRecordTaskId);
    Optional<KycRequestEntity> findTop1ByLiveRoomId(String liveRoomId);

    Page<KycRequestEntity> findByStatus(KycRequestStatus status, Pageable pageable);

    List<KycRequestEntity> findByRetentionExpiresAtBeforeAndRawResultIsNotNull(Instant before);

    @Query("select k from KycRequestEntity k where k.user.id = :userId and k.liveRoomId is not null and k.liveEndedAt is null and k.status in :statuses order by k.createdAt desc")
    List<KycRequestEntity> findActiveLiveSessions(
        @Param("userId") UUID userId,
        @Param("statuses") List<KycRequestStatus> statuses);
}
