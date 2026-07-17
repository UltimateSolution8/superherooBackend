package com.helpinminutes.api.mediator.repo;

import com.helpinminutes.api.mediator.model.HelperMediatorLinkEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HelperMediatorLinkRepository extends JpaRepository<HelperMediatorLinkEntity, UUID> {
  List<HelperMediatorLinkEntity> findByHelperIdAndStatusOrderByCreatedAtDesc(UUID helperId, String status);
  List<HelperMediatorLinkEntity> findByMediatorIdAndStatusOrderByCreatedAtDesc(UUID mediatorId, String status);
  Optional<HelperMediatorLinkEntity> findByHelperIdAndMediatorId(UUID helperId, UUID mediatorId);
  boolean existsByHelperIdAndMediatorIdAndStatus(UUID helperId, UUID mediatorId, String status);
  void deleteByHelperIdAndMediatorId(UUID helperId, UUID mediatorId);
}
