package com.helpinminutes.api.support.repo;

import com.helpinminutes.api.support.model.SupportMessageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportMessageRepository extends JpaRepository<SupportMessageEntity, UUID> {
  List<SupportMessageEntity> findTop200ByTicketIdOrderByCreatedAtAsc(UUID ticketId);
}

