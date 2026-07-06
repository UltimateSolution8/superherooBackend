package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.tasks.model.RecurringTaskEntity;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class RecurrenceCalculator {

  private RecurrenceCalculator() {}

  /**
   * Computes the next occurrence strictly after `after`, in the schedule's own timezone,
   * honoring DST by resolving wall-clock time against the zone rules on every call.
   */
  public static Optional<ZonedDateTime> nextOccurrence(RecurringTaskEntity config, Instant after) {
    ZoneId zone = ZoneId.of(config.getTimezone() != null ? config.getTimezone() : "Asia/Kolkata");
    ZonedDateTime afterZdt = ZonedDateTime.ofInstant(after, zone);
    LocalTime time = LocalTime.parse(config.getTimeSlot());
    LocalDate start = config.getStartDate();
    LocalDate end = config.getEndDate();

    LocalDate nextDate = findNextOccurrenceDate(config, afterZdt.toLocalDate(), time, afterZdt.toLocalTime());
    if (nextDate == null || nextDate.isAfter(end) || nextDate.isBefore(start)) {
      return Optional.empty();
    }

    ZonedDateTime next = ZonedDateTime.of(nextDate, time, zone);
    return Optional.of(next);
  }

  /**
   * Returns up to `n` occurrences after `after` that fall within the start/end dates.
   */
  public static List<ZonedDateTime> nextNOccurrences(RecurringTaskEntity config, Instant after, int n) {
    List<ZonedDateTime> occurrences = new ArrayList<>();
    Instant currentAfter = after;
    for (int i = 0; i < n; i++) {
      Optional<ZonedDateTime> next = nextOccurrence(config, currentAfter);
      if (next.isEmpty()) {
        break;
      }
      ZonedDateTime zdt = next.get();
      occurrences.add(zdt);
      currentAfter = zdt.toInstant();
    }
    return occurrences;
  }

  private static LocalDate findNextOccurrenceDate(RecurringTaskEntity config, LocalDate afterDate, LocalTime slotTime, LocalTime afterTime) {
    String freq = config.getFrequency() != null ? config.getFrequency().trim().toUpperCase() : "DAILY";
    int interval = config.getRecurrenceInterval() != null ? config.getRecurrenceInterval() : 1;
    if (interval < 1) {
      interval = 1;
    }
    LocalDate start = config.getStartDate();

    // Determine target day of week or day of month if needed
    List<DayOfWeek> daysOfWeek = getDaysOfWeek(config, freq);
    int byMonthDay = config.getByMonthDay() != null ? config.getByMonthDay() : start.getDayOfMonth();

    // Start searching from the maximum of start date or afterDate
    LocalDate candidate = afterDate;
    if (candidate.isBefore(start)) {
      candidate = start;
    }

    // Loop to find next matching day
    int maxIterations = 366 * 2; // Safeguard against infinite loops
    for (int i = 0; i < maxIterations; i++) {
      boolean timeMatches = true;
      if (candidate.equals(afterDate)) {
        // If candidate is today, the slot time must be strictly after the afterTime
        if (!slotTime.isAfter(afterTime)) {
          candidate = candidate.plusDays(1);
          continue;
        }
      }

      boolean dateMatches = false;
      if ("DAILY".equals(freq) || "EVERYDAY".equals(freq)) {
        long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(start, candidate);
        if (daysDiff >= 0 && daysDiff % interval == 0) {
          dateMatches = true;
        }
      } else if ("WEEKLY".equals(freq) || isWeekdayName(freq)) {
        // Check if day of week matches
        if (daysOfWeek.contains(candidate.getDayOfWeek())) {
          // Check if correct week interval
          // For week interval calculation, align to standard ISO weeks since start date
          long weeksDiff = java.time.temporal.ChronoUnit.WEEKS.between(
              start.minusDays(start.getDayOfWeek().getValue() - 1),
              candidate.minusDays(candidate.getDayOfWeek().getValue() - 1)
          );
          if (weeksDiff >= 0 && weeksDiff % interval == 0) {
            dateMatches = true;
          }
        }
      } else if ("MONTHLY".equals(freq)) {
        // Must match day of month (or clamped last day of month)
        int lastDay = candidate.lengthOfMonth();
        int targetDay = Math.min(byMonthDay, lastDay);
        if (candidate.getDayOfMonth() == targetDay) {
          long monthsDiff = java.time.temporal.ChronoUnit.MONTHS.between(start.withDayOfMonth(1), candidate.withDayOfMonth(1));
          if (monthsDiff >= 0 && monthsDiff % interval == 0) {
            dateMatches = true;
          }
        }
      }

      if (dateMatches && timeMatches) {
        return candidate;
      }
      candidate = candidate.plusDays(1);
    }

    return null;
  }

  private static List<DayOfWeek> getDaysOfWeek(RecurringTaskEntity config, String freq) {
    List<DayOfWeek> days = new ArrayList<>();
    if (config.getByDay() != null && config.getByDay().length > 0) {
      for (int val : config.getByDay()) {
        // Map 1=Mon .. 7=Sun
        if (val >= 1 && val <= 7) {
          days.add(DayOfWeek.of(val));
        }
      }
    }
    if (days.isEmpty()) {
      if (isWeekdayName(freq)) {
        days.add(DayOfWeek.valueOf(freq));
      } else {
        days.add(config.getStartDate().getDayOfWeek());
      }
    }
    return days;
  }

  private static boolean isWeekdayName(String freq) {
    try {
      DayOfWeek.valueOf(freq);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
