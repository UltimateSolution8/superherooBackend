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

  List<HelperTrainingProgressEntity> findAllByOrderByUpdatedAtDesc();
}
