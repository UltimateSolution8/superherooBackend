package com.helpinminutes.api.helpers.repo;

import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HelperProfileRepository extends JpaRepository<HelperProfileEntity, UUID> {
  List<HelperProfileEntity> findAllByKycStatusOrderByCreatedAtAsc(HelperKycStatus kycStatus);
}
