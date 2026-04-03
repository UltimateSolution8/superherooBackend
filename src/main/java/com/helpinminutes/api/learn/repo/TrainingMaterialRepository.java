package com.helpinminutes.api.learn.repo;

import com.helpinminutes.api.learn.model.TrainingMaterialEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainingMaterialRepository extends JpaRepository<TrainingMaterialEntity, UUID> {
  List<TrainingMaterialEntity> findAllByOrderByCreatedAtDesc();

  List<TrainingMaterialEntity> findByActiveTrueOrderByCreatedAtDesc();
}
