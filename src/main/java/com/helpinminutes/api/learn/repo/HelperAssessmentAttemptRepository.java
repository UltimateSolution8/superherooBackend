package com.helpinminutes.api.learn.repo;

import com.helpinminutes.api.learn.model.HelperAssessmentAttemptEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HelperAssessmentAttemptRepository extends JpaRepository<HelperAssessmentAttemptEntity, UUID> {
  List<HelperAssessmentAttemptEntity> findByHelperIdAndAssessmentIdOrderByAttemptNoDesc(UUID helperId, UUID assessmentId);

  List<HelperAssessmentAttemptEntity> findByAssessmentIdOrderByCreatedAtDesc(UUID assessmentId);

  List<HelperAssessmentAttemptEntity> findByHelperIdOrderByCreatedAtDesc(UUID helperId);

  Optional<HelperAssessmentAttemptEntity> findByIdAndHelperId(UUID id, UUID helperId);
}
