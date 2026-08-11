package com.helpinminutes.api.helpers.repo;

import com.helpinminutes.api.helpers.model.PayoutBeneficiaryLinkEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutBeneficiaryLinkRepository extends JpaRepository<PayoutBeneficiaryLinkEntity, UUID> {
  List<PayoutBeneficiaryLinkEntity> findByPayoutAccountId(UUID payoutAccountId);
}
