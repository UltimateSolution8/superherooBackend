package com.helpinminutes.api.learn.repo;

import com.helpinminutes.api.learn.model.LearningAssessmentEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningAssessmentRepository extends JpaRepository<LearningAssessmentEntity, UUID> {
  List<LearningAssessmentEntity> findAllByOrderByCreatedAtDesc();

  List<LearningAssessmentEntity> findByActiveTrueOrderByCreatedAtDesc();
}
