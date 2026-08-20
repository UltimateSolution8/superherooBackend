package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.LedgerEntryEntity;
import com.helpinminutes.api.payments.model.LedgerEntryType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

  /**
   * The balance, derived rather than stored.
   *
   * A running balance column drifts the first time two writes interleave, and then
   * every later value is wrong with nothing to compare against. Summing an
   * append-only table cannot drift; the index on (user_id, created_at) keeps it cheap
   * at the volumes this will ever see.
   */
  @Query("select coalesce(sum(e.amountPaise), 0) from LedgerEntryEntity e where e.userId = :userId")
  long balancePaise(@Param("userId") UUID userId);

  @Query("select coalesce(sum(e.amountPaise), 0) from LedgerEntryEntity e "
      + "where e.userId = :userId and e.entryType = :type")
  long totalByType(@Param("userId") UUID userId, @Param("type") LedgerEntryType type);

  List<LedgerEntryEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  boolean existsByTaskIdAndUserIdAndEntryType(UUID taskId, UUID userId, LedgerEntryType entryType);

  /**
   * Platform commission actually booked in a window, as a positive number.
   *
   * <p>COMMISSION entries are stored negative because they leave the partner's
   * balance; revenue reporting wants the magnitude. Reporting reads this rather
   * than re-applying a rate to GMV — the reports used to multiply by a hardcoded
   * 0.15, so any rate change made them disagree with the ledger.
   */
  @Query("select coalesce(-sum(e.amountPaise), 0) from LedgerEntryEntity e "
      + "where e.entryType = com.helpinminutes.api.payments.model.LedgerEntryType.COMMISSION "
      + "and e.createdAt >= :start and e.createdAt < :end")
  long commissionBetween(
      @Param("start") java.time.Instant start, @Param("end") java.time.Instant end);
}
