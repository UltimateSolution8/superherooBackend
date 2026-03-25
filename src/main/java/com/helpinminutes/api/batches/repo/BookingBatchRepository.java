package com.helpinminutes.api.batches.repo;

import com.helpinminutes.api.batches.model.BookingBatchEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingBatchRepository extends JpaRepository<BookingBatchEntity, UUID> {
  Optional<BookingBatchEntity> findByCreatedByUserIdAndIdempotencyKey(UUID createdByUserId, String idempotencyKey);
}

