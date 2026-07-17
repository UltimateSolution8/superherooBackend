package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.errors.BadRequestException;
import java.time.Duration;
import java.time.Instant;

/** Authoritative lead-time rules for scheduled single and crew bookings. */
public final class CrewSchedulingPolicy {
  public static final int LARGE_CREW_MIN_SIZE = 10;
  public static final Duration STANDARD_MIN_LEAD_TIME = Duration.ofHours(1);
  public static final Duration LARGE_CREW_MIN_LEAD_TIME = Duration.ofHours(1);
  public static final String LARGE_CREW_SCHEDULE_MESSAGE =
      "Requests for 10 or more Superheroos must be scheduled at least 1 hour in advance";
  public static final String SCHEDULE_MESSAGE =
      "Scheduled tasks must be booked at least 1 hour in advance";

  private CrewSchedulingPolicy() {}

  public static boolean isLargeCrew(Integer helperCount) {
    return helperCount != null && helperCount >= LARGE_CREW_MIN_SIZE;
  }

  public static void validate(Integer helperCount, Instant scheduledAt, Instant now) {
    if (isLargeCrew(helperCount)) {
      if (scheduledAt == null || scheduledAt.isBefore(now.plus(LARGE_CREW_MIN_LEAD_TIME))) {
        throw new BadRequestException(LARGE_CREW_SCHEDULE_MESSAGE);
      }
      return;
    }

    if (scheduledAt != null && scheduledAt.isBefore(now.plus(STANDARD_MIN_LEAD_TIME))) {
      throw new BadRequestException(SCHEDULE_MESSAGE);
    }
  }
}
