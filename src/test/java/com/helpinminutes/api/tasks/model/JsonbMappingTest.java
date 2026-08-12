package com.helpinminutes.api.tasks.model;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

/**
 * Guards a latent production bug.
 *
 * A jsonb column mapped to a plain String field is bound by Hibernate as
 * varchar, and Postgres refuses the implicit varchar -> jsonb cast in extended
 * query mode. Writes to task_ai_reviews only ever succeeded because the
 * production JDBC URL sets preferQueryMode=simple, which inlines the value as a
 * literal so Postgres casts it. Removing that parameter — a reasonable tidy-up —
 * would have broken every booking that runs AI moderation.
 *
 * Any jsonb column therefore needs an explicit @JdbcTypeCode(SqlTypes.JSON).
 */
class JsonbMappingTest {

  private static final List<Class<?>> ENTITIES_WITH_JSONB = List.of(
      TaskAiReviewEntity.class,
      com.helpinminutes.api.realtime.RealtimeOutboxEntity.class,
      com.helpinminutes.api.notifications.outbox.NotificationOutboxEntity.class,
      com.helpinminutes.api.learn.model.HelperAssessmentAttemptEntity.class,
      com.helpinminutes.api.learn.model.LearningAssessmentEntity.class);

  @Test
  void everyJsonbColumnDeclaresTheJsonJdbcType() {
    List<String> unmapped = new ArrayList<>();

    for (Class<?> entity : ENTITIES_WITH_JSONB) {
      for (Field field : entity.getDeclaredFields()) {
        Column column = field.getAnnotation(Column.class);
        if (column == null) continue;
        if (!"jsonb".equalsIgnoreCase(column.columnDefinition())) continue;

        JdbcTypeCode jdbcType = field.getAnnotation(JdbcTypeCode.class);
        if (jdbcType == null || jdbcType.value() != SqlTypes.JSON) {
          unmapped.add(entity.getSimpleName() + "." + field.getName());
        }
      }
    }

    assertTrue(unmapped.isEmpty(),
        "jsonb columns missing @JdbcTypeCode(SqlTypes.JSON) — these will fail on any "
            + "connection that does not set preferQueryMode=simple: " + unmapped);
  }

  @Test
  void theRegressionSiteIsStillCovered() {
    // Explicit belt-and-braces on the field that actually broke, so a rename
    // cannot quietly drop it from the sweep above.
    for (String name : List.of("flags", "reasons", "rawResponse")) {
      Field field = null;
      for (Field candidate : TaskAiReviewEntity.class.getDeclaredFields()) {
        if (candidate.getName().equals(name)) {
          field = candidate;
          break;
        }
      }
      assertNotNull(field, "TaskAiReviewEntity." + name + " no longer exists");
      JdbcTypeCode jdbcType = field.getAnnotation(JdbcTypeCode.class);
      assertNotNull(jdbcType, "TaskAiReviewEntity." + name + " lost its @JdbcTypeCode");
      assertTrue(jdbcType.value() == SqlTypes.JSON,
          "TaskAiReviewEntity." + name + " must bind as JSON");
    }
  }
}
