package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.PaymentEntity;
import com.helpinminutes.api.payments.model.PaymentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {
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
  List<PaymentEntity> findTop100ByMediatorIdOrderByCreatedAtDesc(UUID mediatorId);
}
