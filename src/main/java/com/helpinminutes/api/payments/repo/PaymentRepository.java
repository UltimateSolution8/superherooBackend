package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.PaymentEntity;
import com.helpinminutes.api.payments.model.PaymentStatus;
import com.helpinminutes.api.payments.model.PaymentFulfillmentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PaymentEntity p where p.id = :id")
  Optional<PaymentEntity> findByIdForUpdate(@Param("id") UUID id);
  Optional<PaymentEntity> findByBuyerIdAndIdempotencyKey(UUID buyerId, String idempotencyKey);
  Optional<PaymentEntity> findByProviderOrderId(String providerOrderId);
  Optional<PaymentEntity> findByProviderPaymentId(String providerPaymentId);
  Optional<PaymentEntity> findTopByTaskIdOrderByCreatedAtDesc(UUID taskId);
  Optional<PaymentEntity> findTopByBatchIdOrderByCreatedAtDesc(UUID batchId);
  Optional<PaymentEntity> findTopByTaskIdAndStatusInOrderByCreatedAtDesc(UUID taskId, Collection<PaymentStatus> statuses);
  Optional<PaymentEntity> findTopByBatchIdAndStatusInOrderByCreatedAtDesc(UUID batchId, Collection<PaymentStatus> statuses);
  boolean existsByTaskIdAndStatusIn(UUID taskId, Collection<PaymentStatus> statuses);
  boolean existsByBatchId(UUID batchId);
  boolean existsByTaskIdIn(Collection<UUID> taskIds);
  List<PaymentEntity> findByTaskIdIn(Collection<UUID> taskIds);
  List<PaymentEntity> findTop100ByBuyerIdOrderByCreatedAtDesc(UUID buyerId);
  List<PaymentEntity> findTop100ByHelperIdOrderByCreatedAtDesc(UUID helperId);
  List<PaymentEntity> findTop100ByHelperIdAndFulfillmentStatusOrderByEarningReleasedAtDesc(
      UUID helperId, PaymentFulfillmentStatus fulfillmentStatus);
  List<PaymentEntity> findTop100ByMediatorIdOrderByCreatedAtDesc(UUID mediatorId);
  List<PaymentEntity> findTop50ByFulfillmentStatusAndRefundAttemptsLessThanOrderByUpdatedAtAsc(
      PaymentFulfillmentStatus fulfillmentStatus, int maxAttempts);
}
