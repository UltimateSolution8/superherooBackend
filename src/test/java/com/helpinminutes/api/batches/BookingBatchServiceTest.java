package com.helpinminutes.api.batches;

import com.helpinminutes.api.batches.dto.BatchDtos;
import com.helpinminutes.api.batches.service.BookingBatchService;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BookingBatchService validation and budget logic.
 * Uses reflection to test private methods (validateLine, recommendBudget).
 */
class BookingBatchServiceTest {

    // ─── validateLine edge cases ─────────────────────────────────────────────

    @Test
    void validateLine_nullTitle_returnsError() {
        var errors = validate(null, "valid description for testing", "NORMAL", 30, 10000L,
                17.3850, 78.4867, null, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("title")),
                "Should flag null title");
    }

    @Test
    void validateLine_shortTitle_returnsError() {
        var errors = validate("ab", "valid description for testing", "NORMAL", 30, 10000L,
                17.3850, 78.4867, null, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("title")),
                "Title < 3 chars should fail");
    }

    @Test
    void validateLine_shortDescription_returnsError() {
        var errors = validate("Valid title here", "short", "NORMAL", 30, 10000L,
                17.3850, 78.4867, null, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("description")),
                "Description < 10 chars should fail");
    }

    @Test
    void validateLine_outsideHyderabad_returnsError() {
        // New York coordinates
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 10000L, 40.7128, -74.0060, null, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("service area")),
                "Location outside the service area should fail");
    }

    @Test
    void validateLine_insideHyderabad_noLocationError() {
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 10000L, 17.3850, 78.4867, null, null);
        assertFalse(errors.stream().anyMatch(e -> e.contains("service area")),
                "Hyderabad location should pass");
    }

    @Test
    void validateLine_mumbaiCoordinates_shouldBeRejected() {
        // Bug reproduction: Mumbai coordinates are within India boundaries but should be rejected
        // as they are outside Hyderabad service area
        // Mumbai: Latitude 19.0760, Longitude 72.8777
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 10000L, 19.0760, 72.8777, null, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("service area")),
                "Mumbai location outside service area should fail - THIS IS THE BUG");
    }

    @Test
    void validateLine_hyderabadCenterCoordinates_shouldBeAccepted() {
        // Positive test: Hyderabad center coordinates should be accepted
        // Hyderabad center: Latitude 17.3850, Longitude 78.4867
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 10000L, 17.3850, 78.4867, null, null);
        assertFalse(errors.stream().anyMatch(e -> e.contains("service area")),
                "Hyderabad center location should be accepted");
    }

    @Test
    void validateLine_hyderabadNearbyCoordinates_shouldBeAccepted() {
        // Positive test: Coordinates within Hyderabad service area (55km radius) should pass
        // Location ~10km south of Hyderabad center
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 10000L, 17.2850, 78.4867, null, null);
        assertFalse(errors.stream().anyMatch(e -> e.contains("service area")),
                "Location within Hyderabad service area should be accepted");
    }

    @Test
    void validateLine_scheduledAtInPast_returnsError() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 10000L, 17.3850, 78.4867, null, past);
        assertTrue(errors.stream().anyMatch(e -> e.contains("scheduledAt")),
                "Past scheduledAt should fail");
    }

    @Test
    void validateLine_scheduledAtThirtyMinutesAhead_returnsError() {
        Instant soon = Instant.now().plus(30, ChronoUnit.MINUTES);
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 10000L, 17.3850, 78.4867, null, soon);
        assertTrue(errors.stream().anyMatch(e -> e.contains("1 hour")),
                "scheduledAt < 1 hour ahead should fail");
    }

    @Test
    void validateLine_scheduledAtTwoHoursAhead_noScheduleError() {
        Instant future = Instant.now().plus(2, ChronoUnit.HOURS);
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 10000L, 17.3850, 78.4867, null, future);
        assertFalse(errors.stream().anyMatch(e -> e.contains("scheduledAt")),
                "2-hour future scheduledAt should pass");
    }

    @Test
    void validateLine_timeMinutesTooHigh_returnsError() {
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 500, 10000L, 17.3850, 78.4867, null, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("timeMinutes")),
                "timeMinutes > 480 should fail");
    }

    @Test
    void validateLine_negativeBudget_returnsError() {
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, -1L, 17.3850, 78.4867, null, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("budgetPaise")),
                "Negative budget should fail");
    }

    @Test
    void validateLine_zeroBudget_returnsError() {
        // A task must carry a real budget: partners are paid from it. The minimum
        // is 100 paise (₹1). The previous expectation here ("zero is allowed")
        // came from the test's own duplicate of the rules, not from the service.
        var errors = validate("Valid title here", "Valid description long enough",
                "NORMAL", 30, 0L, 17.3850, 78.4867, null, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("budgetPaise")),
                "Zero budget must be rejected");
    }

    // ─── Helper: invoke validateLine via reflection ───────────────────────────
    /**
     * Invokes the real {@link BookingBatchService#validateLine} by reflection.
     *
     * This previously reimplemented the validation rules inline. That made the
     * suite green while testing nothing: the copy checked isWithinHyderabad
     * while production checked isWithinIndia, so a Mumbai booking was accepted
     * in production and rejected only in the test's private duplicate.
     */
    @SuppressWarnings("unchecked")
    private List<String> validate(String title, String description, String urgency,
                                   Integer timeMinutes, Long budgetPaise,
                                   Double lat, Double lng, String address, Instant scheduledAt) {
        var item = new BatchDtos.PreviewItem(
                title, description,
                urgency == null ? null : TaskUrgency.valueOf(urgency),
                timeMinutes, budgetPaise, lat, lng, address, scheduledAt);
        try {
            BookingBatchService service = newServiceWithMockedDependencies();
            Method m = BookingBatchService.class
                    .getDeclaredMethod("validateLine", BatchDtos.PreviewItem.class);
            m.setAccessible(true);
            return (List<String>) m.invoke(service, item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "validateLine signature changed — update this test rather than duplicating its logic", e);
        }
    }

    /**
     * Builds a BookingBatchService with every collaborator mocked. validateLine
     * only touches taskModerationService, which is lenient by default.
     */
    private BookingBatchService newServiceWithMockedDependencies() {
        Constructor<?> ctor = BookingBatchService.class.getDeclaredConstructors()[0];
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(Mockito::mock)
                .toArray();
        ctor.setAccessible(true);
        try {
            return (BookingBatchService) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not construct BookingBatchService for test", e);
        }
    }
}
