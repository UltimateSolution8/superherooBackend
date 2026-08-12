package com.helpinminutes.api.realtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RealtimeOutboxRepository extends JpaRepository<RealtimeOutboxEntity, UUID> {
  List<RealtimeOutboxEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
      String status, Instant now, Pageable pageable);

  @Modifying
  @Transactional
  @Query("update RealtimeOutboxEntity e set e.status = 'PROCESSING', e.lockedAt = :now, "
      + "e.updatedAt = :now where e.id = :id and e.status = 'PENDING' "
      + "and e.nextAttemptAt <= :now")
  int claim(@Param("id") UUID id, @Param("now") Instant now);

  @Modifying
  @Transactional
  @Query("update RealtimeOutboxEntity e set e.status = 'PENDING', e.lockedAt = null, "
      + "e.nextAttemptAt = :now, e.updatedAt = :now where e.status = 'PROCESSING' "
      + "and e.lockedAt < :cutoff")
  int requeueStuck(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

  @Modifying
  @Transactional
  @Query("delete from RealtimeOutboxEntity e where e.status = 'PUBLISHED' "
      + "and e.publishedAt < :cutoff")
  int deletePublishedBefore(@Param("cutoff") Instant cutoff);

  @Modifying
  @Transactional
  @Query("delete from RealtimeOutboxEntity e where e.status = 'DEAD' "
      + "and e.updatedAt < :cutoff")
  int deleteDeadBefore(@Param("cutoff") Instant cutoff);
}
