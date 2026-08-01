package com.helpinminutes.api.helpers.repo;

import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.PublicPartnerKycSubmissionEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicPartnerKycSubmissionRepository extends JpaRepository<PublicPartnerKycSubmissionEntity, UUID> {
  List<PublicPartnerKycSubmissionEntity> findAllByStatusOrderByCreatedAtAsc(HelperKycStatus status);
  long countByPhoneAndCreatedAtAfter(String phone, Instant createdAfter);
  long countByEmailAndCreatedAtAfter(String email, Instant createdAfter);
  long countByStatus(HelperKycStatus status);
}
