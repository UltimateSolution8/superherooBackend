package com.helpinminutes.api.common;

import com.helpinminutes.api.kyc.model.KycRequestEntity;
import com.helpinminutes.api.kyc.repository.KycRequestRepository;
import com.helpinminutes.api.notifications.service.PushTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enforces the data-retention policies the codebase already declared but never
 * applied.
 *
 * <p>{@code KycService} has always stamped a 90-day {@code retentionExpiresAt} on
 * every KYC request, and the repository has always had a finder for expired rows
 * that still hold a raw provider payload — but nothing called it. The same was
 * true of {@code PushTokenService.purgeStaleTokens}. The result was that KYC
 * provider responses, which contain document images and other identity data, were
 * kept forever despite a stated 90-day limit.
 *
 * <p>Both jobs run nightly rather than on a short fixed delay: they delete, so
 * they belong outside peak hours, and they are staggered so they never hold four
 * database connections between them (each running job under {@link SchedulerLock}
 * takes two).
 */
@Component
public class RetentionJob {
  private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

  /** Rows processed per tick. The backlog drains over subsequent nights. */
  private static final int KYC_PURGE_BATCH = 500;

  private final KycRequestRepository kycRequests;
  private final PushTokenService pushTokens;
  private final SchedulerLock schedulerLock;

  @Value("${RETENTION_PUSH_TOKEN_DAYS:90}")
  private int pushTokenRetentionDays = 90;

  public RetentionJob(
      KycRequestRepository kycRequests,
      PushTokenService pushTokens,
      SchedulerLock schedulerLock) {
    this.kycRequests = kycRequests;
    this.pushTokens = pushTokens;
    this.schedulerLock = schedulerLock;
  }

  /**
   * Clears the raw provider payload from KYC requests whose retention window has
   * passed. The decision record itself is kept: the audit trail needs to show
   * that a verification happened and what it concluded, not the identity
   * documents behind it.
   */
  @Scheduled(cron = "${RETENTION_KYC_CRON:0 30 3 * * *}", zone = "UTC")
  public void purgeExpiredKycRawResults() {
    schedulerLock.runExclusively("retention:kyc-raw-result", () -> {
      List<KycRequestEntity> expired = kycRequests
          .findTop500ByRetentionExpiresAtBeforeAndRawResultIsNotNullOrderByRetentionExpiresAtAsc(
              Instant.now());
      if (expired.isEmpty()) {
        return;
      }
      expired.forEach(request -> request.setRawResult(null));
      kycRequests.saveAll(expired);
      log.info("Retention: cleared raw KYC payloads for {} request(s)", expired.size());
      if (expired.size() == KYC_PURGE_BATCH) {
        log.info("Retention: KYC purge hit the per-run cap; remainder drains on the next run");
      }
    });
  }

  /**
   * Removes device tokens that have not been seen for the retention window. These
   * are dead FCM registrations: every push to one costs a wasted call and a
   * logged failure.
   */
  @Scheduled(cron = "${RETENTION_PUSH_TOKEN_CRON:0 45 3 * * *}", zone = "UTC")
  public void purgeStalePushTokens() {
    schedulerLock.runExclusively("retention:push-tokens", () -> {
      Instant cutoff = Instant.now().minus(Duration.ofDays(pushTokenRetentionDays));
      long removed = pushTokens.purgeStaleTokens(cutoff);
      if (removed > 0) {
        log.info("Retention: removed {} push token(s) not seen since {}", removed, cutoff);
      }
    });
  }
}
