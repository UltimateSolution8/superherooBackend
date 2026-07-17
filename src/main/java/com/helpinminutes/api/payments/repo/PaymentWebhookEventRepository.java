package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.PaymentWebhookEventEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEventEntity, UUID> {
  Optional<PaymentWebhookEventEntity> findByProviderEventId(String providerEventId);
}
