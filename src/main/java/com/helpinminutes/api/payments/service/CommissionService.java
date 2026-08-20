package com.helpinminutes.api.payments.service;

import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.payments.model.CommissionSettingEntity;
import com.helpinminutes.api.payments.repo.CommissionSettingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the platform's take for a given task.
 *
 * <p>Replaces a single boot-time environment variable. Resolution is
 * most-specific-first — HELPER, then CATEGORY, then GLOBAL, then the configured
 * default — so a rate can be varied for one partner or one category without
 * touching everyone else.
 *
 * <p>The GLOBAL rate is cached because it is read on every task mapping; the
 * cache is dropped on every write. Scoped lookups are rare enough to hit the
 * database.
 */
@Service
public class CommissionService {

  private static final Logger log = LoggerFactory.getLogger(CommissionService.class);
  private static final int MAX_BPS = 5000;

  private final CommissionSettingRepository settings;
  private final AppProperties props;

  /** Cached GLOBAL rate. Null means "not looked up since the last write". */
  private final AtomicReference<Integer> cachedGlobalBps = new AtomicReference<>(null);

  public CommissionService(CommissionSettingRepository settings, AppProperties props) {
    this.settings = settings;
    this.props = props;
  }

  /** The rate that applies right now to this partner and category, in basis points. */
  @Transactional(readOnly = true)
  public int resolveBps(UUID helperId, String category) {
    if (helperId != null) {
      Optional<CommissionSettingEntity> helperRate =
          settings.findCurrent(CommissionSettingEntity.SCOPE_HELPER, helperId.toString());
      if (helperRate.isPresent()) return helperRate.get().getCommissionBps();
    }
    if (category != null && !category.isBlank()) {
      Optional<CommissionSettingEntity> categoryRate =
          settings.findCurrent(CommissionSettingEntity.SCOPE_CATEGORY, category.trim());
      if (categoryRate.isPresent()) return categoryRate.get().getCommissionBps();
    }
    return globalBps();
  }

  /** The platform-wide rate, falling back to the configured default. */
  @Transactional(readOnly = true)
  public int globalBps() {
    Integer cached = cachedGlobalBps.get();
    if (cached != null) return cached;

    int resolved;
    try {
      resolved =
          settings
              .findCurrent(CommissionSettingEntity.SCOPE_GLOBAL, null)
              .map(CommissionSettingEntity::getCommissionBps)
              .orElseGet(() -> props.payments().commissionBps());
    } catch (RuntimeException e) {
      // A commission lookup must never be what takes task listings down. The
      // configured default is the same value the table would have been seeded to.
      log.warn("Commission lookup failed; using the configured default", e);
      return props.payments().commissionBps();
    }
    cachedGlobalBps.set(resolved);
    return resolved;
  }

  @Transactional(readOnly = true)
  public List<CommissionSettingEntity> currentSettings() {
    return settings.findAllCurrent();
  }

  /**
   * Sets a new rate for a scope.
   *
   * <p>Closes the row currently in force and inserts a new one rather than
   * updating in place: entries already booked must keep reconciling against the
   * rate they were booked at.
   */
  @Transactional
  public CommissionSettingEntity setRate(
      String scope, String scopeRef, int commissionBps, UUID actorId, String note) {
    String normalizedScope = normalizeScope(scope);
    String normalizedRef =
        CommissionSettingEntity.SCOPE_GLOBAL.equals(normalizedScope)
            ? null
            : requireRef(scopeRef);

    if (commissionBps < 0 || commissionBps > MAX_BPS) {
      throw new BadRequestException(
          "Commission must be between 0 and " + MAX_BPS + " basis points (0%–50%)");
    }

    Instant now = Instant.now();
    settings
        .findCurrent(normalizedScope, normalizedRef)
        .ifPresent(
            current -> {
              current.setEffectiveTo(now);
              settings.save(current);
            });

    CommissionSettingEntity next = new CommissionSettingEntity();
    next.setScope(normalizedScope);
    next.setScopeRef(normalizedRef);
    next.setCommissionBps(commissionBps);
    next.setEffectiveFrom(now);
    next.setCreatedBy(actorId);
    next.setNote(note);
    CommissionSettingEntity saved = settings.save(next);

    cachedGlobalBps.set(null);
    log.info(
        "Commission rate changed scope={} ref={} bps={} by={}",
        normalizedScope,
        normalizedRef,
        commissionBps,
        actorId);
    return saved;
  }

  private static String normalizeScope(String scope) {
    if (scope == null) return CommissionSettingEntity.SCOPE_GLOBAL;
    String s = scope.trim().toUpperCase();
    return switch (s) {
      case CommissionSettingEntity.SCOPE_CATEGORY -> CommissionSettingEntity.SCOPE_CATEGORY;
      case CommissionSettingEntity.SCOPE_HELPER -> CommissionSettingEntity.SCOPE_HELPER;
      case CommissionSettingEntity.SCOPE_GLOBAL -> CommissionSettingEntity.SCOPE_GLOBAL;
      default -> throw new BadRequestException("Unknown commission scope: " + scope);
    };
  }

  private static String requireRef(String scopeRef) {
    if (scopeRef == null || scopeRef.isBlank()) {
      throw new BadRequestException("A CATEGORY or HELPER rate needs a scope reference");
    }
    return scopeRef.trim();
  }
}
