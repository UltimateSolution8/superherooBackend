package com.helpinminutes.api.notifications.repo;

import com.helpinminutes.api.notifications.model.PushTokenEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushTokenRepository extends JpaRepository<PushTokenEntity, UUID> {
  Optional<PushTokenEntity> findByToken(String token);

  List<PushTokenEntity> findAllByUserIdIn(List<UUID> userIds);

  long deleteByTokenIn(List<String> tokens);

  /**
   * Bulk delete rather than the derived {@code deleteByLastSeenAtBefore}, which
   * loads every matching row into the persistence context and removes them one at
   * a time. The first retention run after this ships may match a large backlog.
   * Backed by {@code idx_push_tokens_last_seen} (V58).
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "delete from PushTokenEntity p where p.lastSeenAt < :cutoff")
  int deleteStaleBefore(@org.springframework.data.repository.query.Param("cutoff") Instant cutoff);
}
