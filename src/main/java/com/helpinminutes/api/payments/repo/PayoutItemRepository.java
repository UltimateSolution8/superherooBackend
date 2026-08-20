package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.PayoutItemEntity;
import com.helpinminutes.api.payments.model.PayoutStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutItemRepository extends JpaRepository<PayoutItemEntity, UUID> {

  List<PayoutItemEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  boolean existsByUserIdAndStatusIn(UUID userId, List<PayoutStatus> statuses);

  Optional<PayoutItemEntity> findByIdempotencyKey(String idempotencyKey);

  Optional<PayoutItemEntity> findByProviderPayoutId(String providerPayoutId);

  /** The reconciliation job's queue: anything the provider has not resolved yet. */
  List<PayoutItemEntity> findByStatusInAndRequestedAtBefore(
      List<PayoutStatus> statuses, Instant before, Pageable pageable);
}
