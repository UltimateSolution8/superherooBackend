package com.helpinminutes.api.batches.repo;

import com.helpinminutes.api.batches.model.BookingBatchEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingBatchRepository extends JpaRepository<BookingBatchEntity, UUID> {
  Optional<BookingBatchEntity> findByCreatedByUserIdAndIdempotencyKey(UUID createdByUserId, String idempotencyKey);
  List<BookingBatchEntity> findByStatus(com.helpinminutes.api.batches.model.BookingBatchStatus status);
  List<BookingBatchEntity> findByMediatorId(UUID mediatorId);
  List<BookingBatchEntity> findByMediatorIdAndStatus(UUID mediatorId, com.helpinminutes.api.batches.model.BookingBatchStatus status);
  List<BookingBatchEntity> findBySourceRecurringTaskId(UUID sourceRecurringTaskId);
  List<BookingBatchEntity> findBySourceRecurringTaskIdAndStatusIn(UUID sourceRecurringTaskId, Collection<com.helpinminutes.api.batches.model.BookingBatchStatus> statuses);
  boolean existsBySourceRecurringTaskIdAndScheduledWindowStartAndStatusNot(UUID sourceRecurringTaskId, Instant scheduledWindowStart, com.helpinminutes.api.batches.model.BookingBatchStatus status);
  long countByStatus(com.helpinminutes.api.batches.model.BookingBatchStatus status);
  long countByMediatorIdAndStatus(UUID mediatorId, com.helpinminutes.api.batches.model.BookingBatchStatus status);

  List<BookingBatchEntity> findByCreatedByUserIdOrderByCreatedAtDesc(UUID createdByUserId);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select b from BookingBatchEntity b where b.id = :id")
  Optional<BookingBatchEntity> findAndLockById(@org.springframework.data.repository.query.Param("id") UUID id);
}

