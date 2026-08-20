package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.PayoutAccountValidationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutAccountValidationRepository
    extends JpaRepository<PayoutAccountValidationEntity, UUID> {

  Optional<PayoutAccountValidationEntity> findByProviderValidationId(String providerValidationId);

  @Query("select v from PayoutAccountValidationEntity v "
      + "where v.payoutAccountId = :accountId and v.status = 'PENDING'")
  Optional<PayoutAccountValidationEntity> findInFlight(@Param("accountId") UUID accountId);

  Optional<PayoutAccountValidationEntity>
      findFirstByPayoutAccountIdOrderByCreatedAtDesc(UUID payoutAccountId);

  /** Counts attempts in a window, for the per-account daily cap. Each drop costs money. */
  @Query("select count(v) from PayoutAccountValidationEntity v "
      + "where v.payoutAccountId = :accountId and v.createdAt >= :since")
  long countSince(@Param("accountId") UUID accountId, @Param("since") Instant since);

  @Query("select v from PayoutAccountValidationEntity v "
      + "where v.status = 'PENDING' and v.providerValidationId is not null "
      + "order by v.createdAt")
  List<PayoutAccountValidationEntity> findPending();
}
