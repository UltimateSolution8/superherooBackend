package com.helpinminutes.api.helpers.repo;

import com.helpinminutes.api.helpers.model.HelperPayoutAccountEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HelperPayoutAccountRepository extends JpaRepository<HelperPayoutAccountEntity, UUID> {
  Optional<HelperPayoutAccountEntity> findByHelperIdAndProvider(UUID helperId, String provider);
  List<HelperPayoutAccountEntity> findByHelperIdInAndProvider(Collection<UUID> helperIds, String provider);
}
