package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.*;

import com.helpinminutes.api.tasks.model.RecurringTaskEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class RecurrenceCalculatorTest {

  @Test
  public void testDailyRecurrence() {
    RecurringTaskEntity rec = new RecurringTaskEntity();
    rec.setFrequency("DAILY");
    rec.setStartDate(LocalDate.of(2026, 7, 1));
    rec.setEndDate(LocalDate.of(2026, 7, 10));
    rec.setTimeSlot("09:00");
    rec.setRecurrenceInterval(1);
    rec.setTimezone("Asia/Kolkata");

    // 1. Next occurrence after start date morning before slot time
    Instant after1 = ZonedDateTime.of(2026, 7, 1, 8, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
    Optional<ZonedDateTime> next1 = RecurrenceCalculator.nextOccurrence(rec, after1);
    assertTrue(next1.isPresent());
    assertEquals(ZonedDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata")), next1.get());

    // 2. Next occurrence after start date morning after slot time
    Instant after2 = ZonedDateTime.of(2026, 7, 1, 9, 30, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
    Optional<ZonedDateTime> next2 = RecurrenceCalculator.nextOccurrence(rec, after2);
    assertTrue(next2.isPresent());
    assertEquals(ZonedDateTime.of(2026, 7, 2, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata")), next2.get());

    // 3. Interval of 3 days
    rec.setRecurrenceInterval(3);
    Optional<ZonedDateTime> next3 = RecurrenceCalculator.nextOccurrence(rec, after2);
    assertTrue(next3.isPresent());
    assertEquals(ZonedDateTime.of(2026, 7, 4, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata")), next3.get());
  }

  @Test
  public void testWeeklyRecurrence() {
    RecurringTaskEntity rec = new RecurringTaskEntity();
    rec.setFrequency("WEEKLY");
    rec.setStartDate(LocalDate.of(2026, 7, 1)); // Wednesday
    rec.setEndDate(LocalDate.of(2026, 7, 31));
    rec.setTimeSlot("10:00");
    rec.setRecurrenceInterval(1);
    rec.setByDay(new int[]{1, 3, 5}); // Mon, Wed, Fri
    rec.setTimezone("Asia/Kolkata");

    // After Wednesday July 1 at 11:00 AM, next should be Friday July 3 at 10:00 AM
    Instant after = ZonedDateTime.of(2026, 7, 1, 11, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
    Optional<ZonedDateTime> next = RecurrenceCalculator.nextOccurrence(rec, after);
    assertTrue(next.isPresent());
    assertEquals(ZonedDateTime.of(2026, 7, 3, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")), next.get());

    // Weekly interval of 2
    rec.setRecurrenceInterval(2);
    // After Friday July 3 at 11:00 AM, since interval is 2:
    // Week 1 (Jul 1 - Jul 5) has Mon 29 (before start), Wed 1, Fri 3.
    // Week 2 (Jul 6 - Jul 12) is skipped.
    // Week 3 (Jul 13 - Jul 19) is the next active week. Next should be Monday July 13.
    Instant afterFri = ZonedDateTime.of(2026, 7, 3, 11, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
    Optional<ZonedDateTime> nextBiweekly = RecurrenceCalculator.nextOccurrence(rec, afterFri);
    assertTrue(nextBiweekly.isPresent());
    assertEquals(ZonedDateTime.of(2026, 7, 13, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")), nextBiweekly.get());
  }

  @Test
  public void testMonthlyClamping() {
    RecurringTaskEntity rec = new RecurringTaskEntity();
    rec.setFrequency("MONTHLY");
    rec.setStartDate(LocalDate.of(2026, 1, 31));
    rec.setEndDate(LocalDate.of(2026, 3, 31));
    rec.setTimeSlot("12:00");
    rec.setRecurrenceInterval(1);
    rec.setByMonthDay(31);
    rec.setTimezone("Asia/Kolkata");

    // After Jan 31 12:00 PM, next monthly occurrence is Feb. February 2026 has 28 days.
    // So 31st clamps to 28th.
    Instant after = ZonedDateTime.of(2026, 1, 31, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
    Optional<ZonedDateTime> next = RecurrenceCalculator.nextOccurrence(rec, after);
    assertTrue(next.isPresent());
    assertEquals(ZonedDateTime.of(2026, 2, 28, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")), next.get());
  }

  @Test
  public void testDSTHandling() {
    // Europe/London spring-forward is usually last Sunday of March (e.g. 2026-03-29 01:00:00 UTC/BST shift)
    RecurringTaskEntity rec = new RecurringTaskEntity();
    rec.setFrequency("DAILY");
    rec.setStartDate(LocalDate.of(2026, 3, 28));
    rec.setEndDate(LocalDate.of(2026, 3, 31));
    rec.setTimeSlot("09:00");
    rec.setRecurrenceInterval(1);
    rec.setTimezone("Europe/London");

    Instant after = ZonedDateTime.of(2026, 3, 28, 10, 0, 0, 0, ZoneId.of("Europe/London")).toInstant();
    Optional<ZonedDateTime> next = RecurrenceCalculator.nextOccurrence(rec, after);
    assertTrue(next.isPresent());
    // Should fire at exactly 9:00 AM Europe/London time next day, even though offset changed
    assertEquals(ZonedDateTime.of(2026, 3, 29, 9, 0, 0, 0, ZoneId.of("Europe/London")), next.get());
  }

  @Test
  public void testNextNOccurrences() {
    RecurringTaskEntity rec = new RecurringTaskEntity();
    rec.setFrequency("DAILY");
    rec.setStartDate(LocalDate.of(2026, 7, 1));
    rec.setEndDate(LocalDate.of(2026, 7, 5));
    rec.setTimeSlot("09:00");
    rec.setRecurrenceInterval(1);
    rec.setTimezone("Asia/Kolkata");

    Instant after = ZonedDateTime.of(2026, 7, 1, 8, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
    List<ZonedDateTime> occs = RecurrenceCalculator.nextNOccurrences(rec, after, 10);
    // Should stop at end date, so max 5 occurrences (July 1, 2, 3, 4, 5)
    assertEquals(5, occs.size());
    assertEquals(LocalDate.of(2026, 7, 1), occs.get(0).toLocalDate());
    assertEquals(LocalDate.of(2026, 7, 5), occs.get(4).toLocalDate());
  }
}
