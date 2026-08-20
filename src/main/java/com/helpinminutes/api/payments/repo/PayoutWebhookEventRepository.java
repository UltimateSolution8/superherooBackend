package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.PayoutWebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutWebhookEventRepository
    extends JpaRepository<PayoutWebhookEventEntity, String> {}
