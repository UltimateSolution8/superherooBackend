package com.helpinminutes.api.helpers.repo;

import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HelperProfileRepository extends JpaRepository<HelperProfileEntity, UUID> {
  List<HelperProfileEntity> findAllByKycStatusOrderByCreatedAtAsc(HelperKycStatus kycStatus);

  /**
   * Fetches only the profiles for a known set of helpers. Reporting used to call
   * {@code findAll()} and then index the result by user id, loading every profile
   * in the system to look up a page of them.
   */
  List<HelperProfileEntity> findAllByUserIdIn(java.util.Collection<UUID> userIds);
  long countByKycStatus(HelperKycStatus kycStatus);
}
