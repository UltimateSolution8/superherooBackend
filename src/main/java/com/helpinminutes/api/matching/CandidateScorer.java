package com.helpinminutes.api.matching;

import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Ranks candidate partners for a task.
 *
 * <p>Replaces ranking by straight-line distance, which is the classic greedy taxi
 * dispatch and is wrong in a city: 400m across a flyover with no turn is a longer
 * trip than 900m down a through road. Uber's marketplace matching optimises on ETA
 * for exactly this reason, and DoorDash's dispatch work makes the same point about
 * scoring on the real cost of an assignment rather than its Euclidean proxy.
 *
 * <p>The score is a weighted sum of four normalised terms, each in [0,1]:
 *
 * <pre>
 *   0.60 · eta        — dominant. Real driving seconds, from the routing provider.
 *   0.15 · acceptance — a partner who lets offers lapse costs the citizen a whole
 *                       offer window, so reliability is worth real weight.
 *   0.15 · fairness   — least-recently-offered wins. Without this, distance order
 *                       is deterministic and the same nearest partners were
 *                       re-offered every wave while others were never contacted.
 *   0.10 · rating     — quality nudge only; never enough to override a much
 *                       closer partner.
 * </pre>
 *
 * <p>Weights are constants rather than config: they are only meaningful together,
 * and a partial override would silently unbalance the sum. Change them here, with
 * the reasoning, and the test suite will tell you what moved.
 */
@Component
public class CandidateScorer {

  private static final double WEIGHT_ETA = 0.60d;
  private static final double WEIGHT_ACCEPTANCE = 0.15d;
  private static final double WEIGHT_FAIRNESS = 0.15d;
  private static final double WEIGHT_RATING = 0.10d;

  /**
   * ETA at which the eta term bottoms out (30 min).
   *
   * <p>Beyond half an hour the differences stop mattering to a citizen waiting for
   * a 30-minute errand — everything that far away is equally bad — so the term
   * saturates instead of letting one very distant candidate dominate the scale.
   */
  private static final double ETA_SATURATION_SECONDS = 1800d;

  /** Time since last offer at which the fairness term maxes out (15 min). */
  private static final Duration FAIRNESS_SATURATION = Duration.ofMinutes(15);

  /** Neutral value for a partner with no acceptance history and no rating yet. */
  private static final double NEUTRAL = 0.5d;

  private static final double MAX_RATING = 5.0d;

  /**
   * A scored candidate.
   *
   * @param etaSeconds may be a straight-line estimate when routing was unavailable
   * @param distanceMeters kept alongside ETA because the offer payload and the
   *     partner's UI both show distance, and the walk-up accept check uses it
   */
  public record ScoredCandidate(
      UUID helperId,
      double distanceMeters,
      int etaSeconds,
      double score) {}

  /**
   * Scores and sorts candidates, best first.
   *
   * @param etaSecondsByHelper travel time per candidate; a missing or null entry
   *     falls back to a distance-derived estimate so a partial routing response
   *     never drops a candidate
   */
  public List<ScoredCandidate> rank(
      Map<UUID, Double> distanceByHelper,
      Map<UUID, Integer> etaSecondsByHelper,
      Map<UUID, HelperProfileEntity> profilesByHelper,
      Instant now) {

    return distanceByHelper.entrySet().stream()
        .map(entry -> {
          UUID helperId = entry.getKey();
          double distanceMeters = entry.getValue();
          Integer eta = etaSecondsByHelper.get(helperId);
          int etaSeconds = eta != null && eta > 0
              ? eta
              : straightLineEtaSeconds(distanceMeters);
          HelperProfileEntity profile = profilesByHelper.get(helperId);
          double score = WEIGHT_ETA * etaTerm(etaSeconds)
              + WEIGHT_ACCEPTANCE * acceptanceTerm(profile)
              + WEIGHT_FAIRNESS * fairnessTerm(profile, now)
              + WEIGHT_RATING * ratingTerm(profile);
          return new ScoredCandidate(helperId, distanceMeters, etaSeconds, score);
        })
        // Highest score first. Distance breaks ties so the ordering stays
        // deterministic — an arbitrary tie-break makes dispatch untestable.
        .sorted(Comparator
            .comparingDouble(ScoredCandidate::score).reversed()
            .thenComparingDouble(ScoredCandidate::distanceMeters)
            .thenComparing(candidate -> candidate.helperId().toString()))
        .toList();
  }

  /** Straight-line fallback at 18 km/h, the urban average used across the stack. */
  public static int straightLineEtaSeconds(double distanceMeters) {
    return (int) Math.round(Math.max(1d, distanceMeters / 300d) * 60d);
  }

  private static double etaTerm(int etaSeconds) {
    return 1d - Math.min(etaSeconds, ETA_SATURATION_SECONDS) / ETA_SATURATION_SECONDS;
  }

  private static double acceptanceTerm(HelperProfileEntity profile) {
    if (profile == null) return NEUTRAL;
    Double rate = profile.acceptanceRate();
    return rate == null ? NEUTRAL : rate;
  }

  private static double fairnessTerm(HelperProfileEntity profile, Instant now) {
    if (profile == null) return 1d;
    Instant lastOffered = profile.getLastOfferedAt();
    // Never offered anything yet: maximum priority. A new partner who has been
    // sitting online all morning should be first in line, not last.
    if (lastOffered == null) return 1d;
    long idleSeconds = Math.max(0L, Duration.between(lastOffered, now).getSeconds());
    return Math.min(idleSeconds, (double) FAIRNESS_SATURATION.getSeconds())
        / FAIRNESS_SATURATION.getSeconds();
  }

  private static double ratingTerm(HelperProfileEntity profile) {
    if (profile == null || profile.getRating() == null) return NEUTRAL;
    double rating = profile.getRating().doubleValue();
    if (rating <= 0d) return NEUTRAL;
    return Math.min(rating, MAX_RATING) / MAX_RATING;
  }
}
