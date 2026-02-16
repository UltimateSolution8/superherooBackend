package com.helpinminutes.api.support.repo;

import com.helpinminutes.api.support.model.SupportTicketEntity;
import com.helpinminutes.api.support.model.SupportTicketStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<SupportTicketEntity, UUID> {
  List<SupportTicketEntity> findByCreatedByUserIdOrderByLastMessageAtDesc(UUID createdByUserId);

  List<SupportTicketEntity> findTop50ByStatusOrderByLastMessageAtDesc(SupportTicketStatus status);

  List<SupportTicketEntity> findTop50ByOrderByLastMessageAtDesc();
}

