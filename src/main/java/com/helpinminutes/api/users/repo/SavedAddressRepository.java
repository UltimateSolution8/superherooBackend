package com.helpinminutes.api.users.repo;

import com.helpinminutes.api.users.model.SavedAddressEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedAddressRepository extends JpaRepository<SavedAddressEntity, UUID> {

  /** Default first, then newest — the order the picker renders. */
  List<SavedAddressEntity> findByUserIdOrderByDefaultAddressDescCreatedAtDesc(UUID userId);

  /**
   * Scoped by owner on purpose.
   *
   * Every mutation looks a row up this way rather than by id alone, so a request
   * carrying somebody else's address id simply finds nothing. Ownership is a
   * query predicate here, not a check a future edit can forget to write.
   */
  Optional<SavedAddressEntity> findByIdAndUserId(UUID id, UUID userId);

  Optional<SavedAddressEntity> findByUserIdAndDefaultAddressIsTrue(UUID userId);

  long countByUserId(UUID userId);

  boolean existsByUserIdAndLabelIgnoreCase(UUID userId, String label);

  Optional<SavedAddressEntity> findByUserIdAndLabelIgnoreCase(UUID userId, String label);

  /**
   * Clears the current default before another is set.
   *
   * A single statement rather than read-modify-write: the partial unique index
   * rejects two defaults, so the window between reading and writing is a real
   * source of 500s under a double tap.
   */
  @Modifying
  @Query("update SavedAddressEntity a set a.defaultAddress = false, a.updatedAt = CURRENT_TIMESTAMP "
      + "where a.userId = :userId and a.defaultAddress = true and a.id <> :keepId")
  int clearDefaultExcept(@Param("userId") UUID userId, @Param("keepId") UUID keepId);
}
