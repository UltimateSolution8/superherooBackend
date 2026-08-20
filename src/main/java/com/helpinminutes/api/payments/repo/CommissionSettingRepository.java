package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.CommissionSettingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommissionSettingRepository extends JpaRepository<CommissionSettingEntity, UUID> {

  @Query("""
      select c from CommissionSettingEntity c
      where c.scope = :scope
        and (:scopeRef is null and c.scopeRef is null or c.scopeRef = :scopeRef)
        and c.effectiveTo is null
      """)
  Optional<CommissionSettingEntity> findCurrent(
      @Param("scope") String scope, @Param("scopeRef") String scopeRef);

  @Query("select c from CommissionSettingEntity c where c.effectiveTo is null order by c.scope, c.scopeRef")
  List<CommissionSettingEntity> findAllCurrent();
}
