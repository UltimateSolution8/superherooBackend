package com.helpinminutes.api.learn.repo;

import com.helpinminutes.api.learn.model.HelperTrainingProgressEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HelperTrainingProgressRepository extends JpaRepository<HelperTrainingProgressEntity, UUID> {
  Optional<HelperTrainingProgressEntity> findByMaterialIdAndHelperId(UUID materialId, UUID helperId);

  List<HelperTrainingProgressEntity> findByHelperIdOrderByUpdatedAtDesc(UUID helperId);

  List<HelperTrainingProgressEntity> findByMaterialIdOrderByUpdatedAtDesc(UUID materialId);

  /**
   * Bounded deliberately. This is an admin overview, and the unbounded variant
   * was a full scan plus an in-memory sort of the whole progress table
   * ({@code updated_at} is not indexed) that grew with every helper-material pair.
   */
  List<HelperTrainingProgressEntity> findTop500ByOrderByUpdatedAtDesc();
}
