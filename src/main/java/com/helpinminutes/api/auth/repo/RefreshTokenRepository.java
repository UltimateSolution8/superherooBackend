package com.helpinminutes.api.auth.repo;

import com.helpinminutes.api.auth.model.RefreshTokenEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {
  @Query(
      "select rt from RefreshTokenEntity rt where rt.tokenHash = :hash and rt.revokedAt is null and rt.expiresAt > :now")
  Optional<RefreshTokenEntity> findActiveByHash(@Param("hash") String hash, @Param("now") Instant now);

  @Modifying
  @Query("update RefreshTokenEntity rt set rt.revokedAt = :revokedAt where rt.id = :id")
  int revoke(@Param("id") UUID id, @Param("revokedAt") Instant revokedAt);
}
