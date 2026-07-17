package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.helpinminutes.api.errors.BadRequestException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CrewSchedulingPolicyTest {
  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

  @Test
  void nineHelpersMayBeInstant() {
    assertDoesNotThrow(() -> CrewSchedulingPolicy.validate(1, null, NOW));
    assertDoesNotThrow(() -> CrewSchedulingPolicy.validate(9, null, NOW));
  }

  @Test
  void tenHelpersCannotBeInstant() {
    BadRequestException error = assertThrows(
        BadRequestException.class,
        () -> CrewSchedulingPolicy.validate(10, null, NOW));
    assertEquals(CrewSchedulingPolicy.LARGE_CREW_SCHEDULE_MESSAGE, error.getMessage());
  }

  @Test
  void tenHelpersRejectScheduleBelowOneHour() {
    assertThrows(
        BadRequestException.class,
        () -> CrewSchedulingPolicy.validate(10, NOW.plus(Duration.ofMinutes(59)).plusSeconds(59), NOW));
  }

  @Test
  void tenHelpersAllowExactlyOneHour() {
    assertDoesNotThrow(
        () -> CrewSchedulingPolicy.validate(10, NOW.plus(Duration.ofHours(1)), NOW));
  }

  @Test
  void tenHelpersAllowSameDayTwoAndFiveHoursAhead() {
    assertDoesNotThrow(
        () -> CrewSchedulingPolicy.validate(10, NOW.plus(Duration.ofHours(2)), NOW));
    assertDoesNotThrow(
        () -> CrewSchedulingPolicy.validate(10, NOW.plus(Duration.ofHours(5)), NOW));
  }

  @Test
  void standardScheduledTaskRequiresOneHourWhenScheduleLaterIsUsed() {
    assertThrows(
        BadRequestException.class,
        () -> CrewSchedulingPolicy.validate(2, NOW.plus(Duration.ofMinutes(59)), NOW));
    assertDoesNotThrow(
        () -> CrewSchedulingPolicy.validate(2, NOW.plus(Duration.ofHours(1)), NOW));
    assertDoesNotThrow(
        () -> CrewSchedulingPolicy.validate(9, NOW.plus(Duration.ofHours(2)), NOW));
    assertDoesNotThrow(
        () -> CrewSchedulingPolicy.validate(1, NOW.plus(Duration.ofHours(5)), NOW));
  }
}
