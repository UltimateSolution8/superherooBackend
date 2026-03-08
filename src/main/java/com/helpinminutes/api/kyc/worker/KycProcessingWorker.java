package com.helpinminutes.api.kyc.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helpinminutes.api.config.RabbitConfig;
import com.helpinminutes.api.kyc.model.KycRequestEntity;
import com.helpinminutes.api.kyc.model.KycRequestStatus;
import com.helpinminutes.api.kyc.repository.KycRequestRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KycProcessingWorker {
  private static final Logger log = LoggerFactory.getLogger(KycProcessingWorker.class);

  private final KycRequestRepository kycRequests;
  private final ObjectMapper objectMapper;

  public KycProcessingWorker(KycRequestRepository kycRequests, ObjectMapper objectMapper) {
    this.kycRequests = kycRequests;
    this.objectMapper = objectMapper;
  }

  @RabbitListener(queues = RabbitConfig.QUEUE_KYC_PROCESSING)
  @Transactional
  public void handle(Map<String, Object> payload) {
    Object idRaw = payload.get("kycId");
    if (!(idRaw instanceof String idStr)) {
      log.warn("KYC job missing kycId: {}", payload);
      return;
    }
    UUID kycId;
    try {
      kycId = UUID.fromString(idStr);
    } catch (IllegalArgumentException e) {
      log.warn("Invalid kycId in job: {}", idStr);
      return;
    }

    KycRequestEntity entity = kycRequests.findById(kycId).orElse(null);
    if (entity == null) {
      log.warn("KYC request {} not found for job", kycId);
      return;
    }
    if (entity.getStatus() != KycRequestStatus.PENDING_PROCESSING) {
      log.info("KYC request {} is {}, skipping worker", kycId, entity.getStatus());
      return;
    }

    try {
      entity.setRawResult(objectMapper.writeValueAsString(payload));
    } catch (Exception e) {
      log.warn("Failed to serialize KYC payload for {}", kycId);
    }
    entity.setRecommendedAction("REVIEW");
    entity.setStatus(KycRequestStatus.REVIEW);
    kycRequests.save(entity);
    log.info("KYC request {} moved to REVIEW", kycId);
  }
}
