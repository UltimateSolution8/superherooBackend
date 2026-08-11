package com.helpinminutes.api.tasks.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.helpinminutes.api.tasks.service.TaskModerationService.Verdict;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The accuracy gate for task screening.
 *
 * <p>{@code golden-tasks.psv} holds Hyderabad-realistic task text with the verdict
 * local screening must reach. It exists because moderation had drifted into flagging
 * ordinary work: the blocklist contained {@code doctor}, {@code lawyer},
 * {@code leak}, {@code strip}, {@code interest} and {@code loan}, and the obfuscation
 * regexes matched substrings, so <i>"weeding the garden"</i> tripped the cannabis
 * pattern and <i>"adulterated"</i> tripped the adult-content one.
 *
 * <p>Three properties are enforced, and a failure in any of them fails the build:
 *
 * <ol>
 *   <li><b>No false positives.</b> Nothing legal may be blocked.
 *   <li><b>No false negatives.</b> Everything on the hard-illegal list must be
 *       blocked, including obfuscated and transliterated spellings.
 *   <li><b>Cost.</b> Ordinary errands must resolve locally, with no model call.
 *       Every row that drifts from CLEAN to ESCALATE is real money per task.
 * </ol>
 *
 * <p>Extend the file when a new false positive is reported; do not loosen a rule
 * without a row proving the loosening was needed.
 */
class ModerationGoldenSetTest {

  private static final String GOLDEN_FILE = "/moderation/golden-tasks.psv";

  private final TaskModerationService moderation = new TaskModerationService();

  record GoldenCase(Verdict expected, String title, String description) {
    @Override
    public String toString() {
      return expected + ": " + title;
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldenCases")
  void screensEachGoldenCaseAsExpected(GoldenCase testCase) {
    var result = moderation.screen(testCase.title(), testCase.description());

    assertEquals(testCase.expected(), result.verdict(),
        () -> "\"" + testCase.description() + "\" expected " + testCase.expected()
            + " but got " + result.verdict()
            + (result.contextTerms().isEmpty() ? "" : " (terms: " + result.contextTerms() + ")"));
  }

  /**
   * The property that matters most: nothing lawful is ever rejected.
   *
   * <p>Stated separately from the per-case assertion so a regression reports as "a
   * legal task was blocked" rather than as one row among a hundred.
   */
  @Test
  @DisplayName("no lawful task is ever blocked")
  void neverBlocksALawfulTask() {
    List<String> wronglyBlocked = new ArrayList<>();
    for (GoldenCase testCase : loadGoldenCases()) {
      if (testCase.expected() == Verdict.BLOCK) continue;
      if (moderation.screen(testCase.title(), testCase.description()).isBlocked()) {
        wronglyBlocked.add(testCase.title() + " — " + testCase.description());
      }
    }
    assertTrue(wronglyBlocked.isEmpty(),
        "these lawful tasks were blocked:\n  " + String.join("\n  ", wronglyBlocked));
  }

  @Test
  @DisplayName("every hard-illegal task is blocked")
  void neverLetsAnIllegalTaskThrough() {
    List<String> missed = new ArrayList<>();
    for (GoldenCase testCase : loadGoldenCases()) {
      if (testCase.expected() != Verdict.BLOCK) continue;
      var result = moderation.screen(testCase.title(), testCase.description());
      if (!result.isBlocked()) {
        missed.add(testCase.description() + " → " + result.verdict());
      }
    }
    assertTrue(missed.isEmpty(), "these illegal tasks were not blocked:\n  " + String.join("\n  ", missed));
  }

  /**
   * Cost guard. Model calls are the only per-task expense in the pipeline, so the
   * share of tasks that need one is the cost. Ordinary errands must not need one.
   */
  @Test
  @DisplayName("most ordinary errands resolve without a model call")
  void keepsTheEscalationRateLow() {
    List<GoldenCase> lawful = loadGoldenCases().stream()
        .filter(testCase -> testCase.expected() != Verdict.BLOCK)
        .toList();
    long escalated = lawful.stream()
        .filter(testCase -> moderation.screen(testCase.title(), testCase.description()).needsModel())
        .count();

    double rate = (double) escalated / lawful.size();
    assertTrue(rate < 0.35d,
        "escalation rate " + Math.round(rate * 100) + "% is too high; every escalation is a paid "
            + "model call. Check whether a term was added to NEEDS_CONTEXT that did not need to be.");
  }

  /** A blocked task must carry wording that is safe to show the citizen. */
  @Test
  void blockedTasksExplainThemselvesWithoutLeakingTheMatchedTerm() {
    var result = moderation.screen("Party", "Get me some ganja for tonight");

    assertTrue(result.isBlocked());
    assertNotNull(result.citizenMessage());
    // The old message appended the matched token — "...restricted words: leak" —
    // which told anyone probing the filter exactly which word tripped it.
    assertFalse(result.citizenMessage().toLowerCase().contains("ganja"),
        "the citizen-facing message must not echo the matched term back as a probe oracle");
    assertFalse(result.reasons().isEmpty(), "the audit trail still needs a reason code");
  }

  @Test
  void emptyTextIsClean() {
    assertEquals(Verdict.CLEAN, moderation.screen(null, null).verdict());
    assertEquals(Verdict.CLEAN, moderation.screen("", "   ").verdict());
  }

  // ─── loading ──────────────────────────────────────────────────────────────

  static Stream<GoldenCase> goldenCases() {
    return loadGoldenCases().stream();
  }

  private static List<GoldenCase> loadGoldenCases() {
    List<GoldenCase> cases = new ArrayList<>();
    try (var stream = ModerationGoldenSetTest.class.getResourceAsStream(GOLDEN_FILE)) {
      assertNotNull(stream, GOLDEN_FILE + " is missing from the test resources");
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String trimmed = line.trim();
          if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
          String[] parts = trimmed.split("\\|", 3);
          if (parts.length != 3) {
            throw new IllegalStateException("malformed golden row: " + trimmed);
          }
          cases.add(new GoldenCase(
              Verdict.valueOf(parts[0].trim()), parts[1].trim(), parts[2].trim()));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not read " + GOLDEN_FILE, e);
    }
    if (cases.size() < 80) {
      throw new IllegalStateException(
          "the golden set has shrunk to " + cases.size() + " rows — it is the accuracy gate, "
              + "so rows should only ever be added");
    }
    return cases;
  }
}
