package com.helpinminutes.api.learn.repo;

import com.helpinminutes.api.learn.model.LearningAssessmentAssignmentEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningAssessmentAssignmentRepository
    extends JpaRepository<LearningAssessmentAssignmentEntity, UUID> {

  List<LearningAssessmentAssignmentEntity> findByAssessmentId(UUID assessmentId);

  List<LearningAssessmentAssignmentEntity> findByAssessmentIdIn(Collection<UUID> assessmentIds);

  List<LearningAssessmentAssignmentEntity> findByHelperId(UUID helperId);

  void deleteByAssessmentId(UUID assessmentId);
}
