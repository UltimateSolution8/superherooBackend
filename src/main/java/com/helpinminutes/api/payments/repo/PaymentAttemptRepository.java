package com.helpinminutes.api.payments.repo;

import com.helpinminutes.api.payments.model.PaymentAttemptEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, UUID> {
  Optional<PaymentAttemptEntity> findByProviderPaymentId(String providerPaymentId);
}
