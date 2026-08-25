package com.helpinminutes.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    @NotBlank String env,
    @NotNull Jwt jwt,
    @NotNull Otp otp,
    @NotNull Matching matching,
    @NotNull Realtime realtime,
    Payments payments,
    AppVersions appVersions
) {
  @ConstructorBinding
  public AppProperties(
      String env,
      Jwt jwt,
      Otp otp,
      Matching matching,
      Realtime realtime,
      Payments payments,
      AppVersions appVersions
  ) {
    if (matching == null) {
      matching = new Matching(9, 6, 8, 120, 45);
    }
    if (realtime == null) {
      realtime = new Realtime("him:rt:events", null, null, 1500);
    }
    if (payments == null) {
      payments = new Payments(false, true, false, 1500, 10_000L);
    }
    if (appVersions == null) {
      appVersions = AppVersions.unconfigured();
    }
    this.env = env;
    this.jwt = jwt;
    this.otp = otp;
    this.matching = matching;
    this.realtime = realtime;
    this.payments = payments;
    this.appVersions = appVersions;
  }

  /**
   * Convenience for callers predating the force-update gate. Binding still uses the
   * canonical constructor; this only keeps hand-built instances compiling.
   */
  public AppProperties(
      String env, Jwt jwt, Otp otp, Matching matching, Realtime realtime, Payments payments) {
    this(env, jwt, otp, matching, realtime, payments, null);
  }

  /**
   * Minimum and latest published client versions, per app.
   *
   * <p>All fields default to blank, and a blank minimum means "no gate". That is
   * deliberate: an unconfigured deployment must never lock every user out of every
   * app. Turning the gate on is an explicit act — set APP_MIN_VERSION_BUYER and so
   * on, to the version that is already live on the store.
   *
   * <p>These are version <em>names</em> ("1.1.0"), not version codes. Version codes
   * carry a per-variant offset and are not comparable across the three apps.
   */
  public record AppVersions(
      String minBuyer,
      String minHelper,
      String minMediator,
      String latestBuyer,
      String latestHelper,
      String latestMediator,
      String storeUrlBuyer,
      String storeUrlHelper,
      String storeUrlMediator,
      String updateMessage
  ) {
    public static AppVersions unconfigured() {
      return new AppVersions(null, null, null, null, null, null, null, null, null, null);
    }
  }

  /**
   * The three money switches, all independent.
   *
   * <p>They are separate because they carry different risk and are turned on at
   * different times. The ledger is the one that should be on from the start: it
   * only records what happened, and if it is off during the cash-only period then
   * every balance starts from zero on the day payouts go live, with no history to
   * reconcile against.
   *
   * @param onlineEnabled whether the online gateway (Razorpay) may be used for
   *     collection. Off at launch: jobs are settled in cash or UPI between citizen
   *     and partner, and the partner confirms collection in-app. Turning this on
   *     with live keys re-enables the prepaid flow — no code changes needed.
   * @param ledgerEnabled whether completed work books earning and commission
   *     entries. Recording is safe with payouts off, and is what makes balances
   *     correct and auditable the day they are.
   * @param payoutsEnabled whether partner payouts may actually be executed against
   *     RazorpayX. Off until the account exists and has been reconciled once.
   * @param commissionBps the platform's take, in basis points. 1500 = 15%, which is
   *     what the revenue reports have always assumed.
   * @param minPayoutPaise the smallest payout worth making. Below this the transfer
   *     fee is a meaningful share of the transfer.
   */
  public record Payments(
      boolean onlineEnabled,
      boolean ledgerEnabled,
      boolean payoutsEnabled,
      @Min(0) @Max(5000) int commissionBps,
      @Min(0) long minPayoutPaise
  ) {
    /** Older configurations set only onlineEnabled; keep them bootable. */
    public Payments(boolean onlineEnabled) {
      this(onlineEnabled, true, false, 1500, 10_000L);
    }
  }
  public record Jwt(
      @NotBlank String accessSecret,
      @NotBlank String refreshSecret,
      @Min(60) long accessTtlSeconds,
      @Min(300) long refreshTtlSeconds
  ) {
    /** Secrets that shipped as defaults at some point and are therefore public knowledge. */
    private static final java.util.Set<String> KNOWN_WEAK_SECRETS = java.util.Set.of(
        "dev_access_secret_change_me", "dev_refresh_secret_change_me", "changeme", "secret");

    private static final int MIN_SECRET_LENGTH = 32;

    public Jwt {
      requireStrongSecret("app.jwt.accessSecret", "JWT_ACCESS_SECRET", accessSecret);
      requireStrongSecret("app.jwt.refreshSecret", "JWT_REFRESH_SECRET", refreshSecret);
      if (accessSecret != null && accessSecret.equals(refreshSecret)) {
        throw new IllegalStateException(
            "JWT_ACCESS_SECRET and JWT_REFRESH_SECRET must differ, otherwise a refresh token "
                + "can be replayed as an access token.");
      }
    }

    private static void requireStrongSecret(String property, String envVar, String value) {
      // @NotBlank already rejects null/empty, but bean validation runs after the
      // canonical constructor, so re-check here to keep the message actionable.
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(
            envVar + " is not set. Refusing to start: there is no safe default for a signing key.");
      }
      if (KNOWN_WEAK_SECRETS.contains(value)) {
        throw new IllegalStateException(
            envVar + " is set to a publicly-known placeholder value. Generate a new secret, e.g. "
                + "`openssl rand -hex 32`.");
      }
      if (value.length() < MIN_SECRET_LENGTH) {
        throw new IllegalStateException(
            envVar + " must be at least " + MIN_SECRET_LENGTH + " characters (got " + value.length()
                + "). Generate one with `openssl rand -hex 32`.");
      }
    }
  }

  /**
   * The {@code returnOtpInResponse} flag is gone, not defaulted off. It echoed the
   * code back to the caller, which is a complete authentication bypass for anyone
   * who can reach the endpoint. There is no environment in which that is the right
   * trade, so there is no longer a switch for it.
   */
  public record Otp(
      @Min(60) @Max(3600) long ttlSeconds
  ) {}

  /**
   * Dispatch tuning.
   *
   * <p>Offers escalate in waves: wave 0 covers {@code waveRadiiMeters[0]} with a
   * fanout of {@code waveFanouts[0]}, and each unanswered window widens both. The
   * last entry in either list is reused for every wave beyond it, so a task in a
   * thin-supply area ends up offered city-wide rather than cancelled after one
   * 3km pass.
   *
   * <p>{@code offerFanout} remains as the fallback fanout for callers that do not
   * carry a wave (and as the floor used by candidate-widening heuristics).
   */
  public record Matching(
      @Min(0) @Max(15) int h3Resolution,
      @Min(0) @Max(10) int maxKRing,
      @Min(1) @Max(50) int offerFanout,
      @Min(5) @Max(300) int helperStaleAfterSeconds,
      @Min(10) @Max(600) int offerTtlSeconds,
      @NotEmpty List<@Min(200) @Max(60000) Integer> waveRadiiMeters,
      @NotEmpty List<@Min(1) @Max(200) Integer> waveFanouts,
      @Min(500) @Max(60000) int pullFeedRadiusMeters,
      @Min(1) @Max(10) int maxLiveOffersPerHelper
  ) {
    /** Wave tiers when unconfigured — kept here so the defaults live in one place. */
    public static final List<Integer> DEFAULT_WAVE_RADII_METERS = List.of(3000, 6000, 10000);
    public static final List<Integer> DEFAULT_WAVE_FANOUTS = List.of(8, 15, 25);
    public static final int DEFAULT_PULL_FEED_RADIUS_METERS = 15_000;
    public static final int DEFAULT_MAX_LIVE_OFFERS_PER_HELPER = 2;

    public Matching {
      waveRadiiMeters = waveRadiiMeters == null || waveRadiiMeters.isEmpty()
          ? DEFAULT_WAVE_RADII_METERS
          : List.copyOf(waveRadiiMeters);
      waveFanouts = waveFanouts == null || waveFanouts.isEmpty()
          ? DEFAULT_WAVE_FANOUTS
          : List.copyOf(waveFanouts);
    }

    /**
     * The core five knobs, with wave and feed settings left at their defaults.
     *
     * <p>Convenience for callers and fixtures that only care about the basics.
     */
    public Matching(
        int h3Resolution,
        int maxKRing,
        int offerFanout,
        int helperStaleAfterSeconds,
        int offerTtlSeconds) {
      this(h3Resolution, maxKRing, offerFanout, helperStaleAfterSeconds, offerTtlSeconds,
          DEFAULT_WAVE_RADII_METERS, DEFAULT_WAVE_FANOUTS,
          DEFAULT_PULL_FEED_RADIUS_METERS, DEFAULT_MAX_LIVE_OFFERS_PER_HELPER);
    }

    /** Search radius for {@code wave}, clamped to the last configured tier. */
    public double radiusForWave(int wave) {
      int index = Math.min(Math.max(wave, 0), waveRadiiMeters.size() - 1);
      return waveRadiiMeters.get(index);
    }

    /** Number of partners to offer at {@code wave}, clamped to the last tier. */
    public int fanoutForWave(int wave) {
      int index = Math.min(Math.max(wave, 0), waveFanouts.size() - 1);
      return waveFanouts.get(index);
    }

    /** Widest radius any wave can reach — the ceiling for a single offer. */
    public double maxOfferRadiusMeters() {
      return waveRadiiMeters.stream().mapToInt(Integer::intValue).max().orElse(3000);
    }
  }

  public record Realtime(
      // the channel used for redis pub/sub.  if the property is absent we fall back
      // to the same default used by the Node realtime gateway so the app can still
      // start (otherwise Spring validation would reject the configuration and the
      // service would refuse to boot, which manifests as a 502 from the load
      // balancer).
      String redisPubSubChannel,
      String publishHttpUrl,
      String publishHttpSecret,
      @Min(200) @Max(10000) int publishHttpTimeoutMs
  ) {
      public Realtime {
          if (redisPubSubChannel == null || redisPubSubChannel.isBlank()) {
              redisPubSubChannel = "him:rt:events";
          }
          if (publishHttpTimeoutMs <= 0) {
              publishHttpTimeoutMs = 1500;
          }
      }
  }
}
