package com.helpinminutes.api.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.payments.model.CommissionSettingEntity;
import com.helpinminutes.api.payments.repo.CommissionSettingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommissionServiceTest {

  private CommissionSettingRepository repo;
  private CommissionService commissions;
  private final List<CommissionSettingEntity> stored = new ArrayList<>();

  @BeforeEach
  void setUp() {
    stored.clear();
    repo = mock(CommissionSettingRepository.class);
    when(repo.save(any(CommissionSettingEntity.class)))
        .thenAnswer(
            invocation -> {
              CommissionSettingEntity e = invocation.getArgument(0);
              if (!stored.contains(e)) stored.add(e);
              return e;
            });
    when(repo.findCurrent(any(), any()))
        .thenAnswer(
            invocation -> {
              String scope = invocation.getArgument(0);
              String ref = invocation.getArgument(1);
              return stored.stream()
                  .filter(
                      e ->
                          e.getScope().equals(scope)
                              && java.util.Objects.equals(e.getScopeRef(), ref)
                              && e.getEffectiveTo() == null)
                  .findFirst();
            });

    AppProperties props = mock(AppProperties.class);
    when(props.payments())
        .thenReturn(new AppProperties.Payments(false, true, false, 1500, 10_000L));
    commissions = new CommissionService(repo, props);
  }

  @Test
  void fallsBackToTheConfiguredDefaultWhenNothingIsSet() {
    assertEquals(1500, commissions.globalBps());
    assertEquals(1500, commissions.resolveBps(UUID.randomUUID(), "cleaning"));
  }

  @Test
  void resolvesMostSpecificFirst() {
    UUID helper = UUID.randomUUID();
    commissions.setRate("GLOBAL", null, 1200, null, null);
    commissions.setRate("CATEGORY", "cleaning", 1000, null, null);
    commissions.setRate("HELPER", helper.toString(), 500, null, null);

    // Helper beats category beats global beats the configured default.
    assertEquals(500, commissions.resolveBps(helper, "cleaning"));
    assertEquals(1000, commissions.resolveBps(UUID.randomUUID(), "cleaning"));
    assertEquals(1200, commissions.resolveBps(UUID.randomUUID(), "moving"));
    assertEquals(1200, commissions.globalBps());
  }

  @Test
  void aRateChangeClosesTheOldRowRatherThanEditingIt() {
    CommissionSettingEntity first = commissions.setRate("GLOBAL", null, 1500, null, "launch");
    CommissionSettingEntity second = commissions.setRate("GLOBAL", null, 1000, null, "promo");

    // The old row survives with an end date, so entries already booked at 15%
    // still reconcile against 15% rather than silently becoming 10%.
    assertNotNull(first.getEffectiveTo());
    assertNull(second.getEffectiveTo());
    assertEquals(1500, first.getCommissionBps());
    assertEquals(1000, commissions.globalBps());
  }

  @Test
  void rejectsRatesOutsideTheAllowedBand() {
    assertThrows(BadRequestException.class, () -> commissions.setRate("GLOBAL", null, -1, null, null));
    // 50% is the ceiling; a typo that sets 95% must fail loudly, not quietly take
    // most of a partner's earnings.
    assertThrows(BadRequestException.class, () -> commissions.setRate("GLOBAL", null, 9500, null, null));
    assertThrows(BadRequestException.class, () -> commissions.setRate("HELPER", null, 1000, null, null));
    assertThrows(BadRequestException.class, () -> commissions.setRate("NONSENSE", "x", 1000, null, null));
  }

  @Test
  void survivesALookupFailureByUsingTheConfiguredDefault() {
    when(repo.findCurrent(eq("GLOBAL"), any())).thenThrow(new IllegalStateException("db down"));
    // A commission lookup must never be what takes task listings down.
    assertEquals(1500, commissions.globalBps());
  }
}
