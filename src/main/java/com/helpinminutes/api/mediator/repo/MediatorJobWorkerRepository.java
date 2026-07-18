package com.helpinminutes.api.mediator.repo;

import com.helpinminutes.api.mediator.model.MediatorJobWorkerEntity;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MediatorJobWorkerRepository extends JpaRepository<MediatorJobWorkerEntity, UUID> {
  List<MediatorJobWorkerEntity> findByBatchId(UUID batchId);
  Optional<MediatorJobWorkerEntity> findByBatchIdAndHelperId(UUID batchId, UUID helperId);
  Optional<MediatorJobWorkerEntity> findByTaskId(UUID taskId);
  List<MediatorJobWorkerEntity> findByTaskIdIn(Collection<UUID> taskIds);
  List<MediatorJobWorkerEntity> findTop100ByHelperIdAndPaymentStatusOrderByAddedAtDesc(UUID helperId, String paymentStatus);
  void deleteByBatchIdAndHelperId(UUID batchId, UUID helperId);
}
