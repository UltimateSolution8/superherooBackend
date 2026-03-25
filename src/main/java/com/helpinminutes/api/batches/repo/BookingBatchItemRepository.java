package com.helpinminutes.api.batches.repo;

import com.helpinminutes.api.batches.model.BookingBatchItemEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingBatchItemRepository extends JpaRepository<BookingBatchItemEntity, UUID> {
  List<BookingBatchItemEntity> findByBatchIdOrderByLineNoAsc(UUID batchId);
  Optional<BookingBatchItemEntity> findByIdAndBatchId(UUID id, UUID batchId);
}
