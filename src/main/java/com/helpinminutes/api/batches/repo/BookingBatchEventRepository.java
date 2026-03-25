package com.helpinminutes.api.batches.repo;

import com.helpinminutes.api.batches.model.BookingBatchEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingBatchEventRepository extends JpaRepository<BookingBatchEventEntity, UUID> {
  List<BookingBatchEventEntity> findTop100ByBatchIdOrderByCreatedAtDesc(UUID batchId);
}

