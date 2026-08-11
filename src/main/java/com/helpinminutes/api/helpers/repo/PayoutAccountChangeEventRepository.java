package com.helpinminutes.api.helpers.repo;

import com.helpinminutes.api.helpers.model.PayoutAccountChangeEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutAccountChangeEventRepository extends JpaRepository<PayoutAccountChangeEventEntity, UUID> {
  List<PayoutAccountChangeEventEntity> findByBeneficiaryUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}

