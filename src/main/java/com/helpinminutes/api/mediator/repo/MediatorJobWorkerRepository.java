package com.helpinminutes.api.mediator.repo;

import com.helpinminutes.api.mediator.model.MediatorJobWorkerEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MediatorJobWorkerRepository extends JpaRepository<MediatorJobWorkerEntity, UUID> {
  List<MediatorJobWorkerEntity> findByBatchId(UUID batchId);
  Optional<MediatorJobWorkerEntity> findByBatchIdAndHelperId(UUID batchId, UUID helperId);
  void deleteByBatchIdAndHelperId(UUID batchId, UUID helperId);
}
