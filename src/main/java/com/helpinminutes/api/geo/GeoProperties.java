package com.helpinminutes.api.geo;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Maps provider configuration.
 *
 * <p>Provider order is per capability and env-tunable, because the right provider
 * differs by capability and by cost model:
 *
 * <ul>
 *   <li><b>Routing</b> → self-hosted OSRM. Free, unlimited, ~5ms, and it carries
 *       the highest-frequency traffic (a route refresh per active task plus the
 *       matching ETA matrix). This is the one place OSM data is unambiguously the
 *       best answer.
 *   <li><b>Text APIs</b> (autocomplete, details, reverse) → Ola Maps. 500k
 *       calls/month free, India-tuned, and biasable to Hyderabad.
 *   <li><b>Google</b> → fallback only. Its per-SKU free tier is 10k/month, which
 *       comfortably absorbs residual volume. Map <i>rendering</i> stays on the
 *       Google mobile SDK, which is a separate, unlimited-free SKU.
 * </ul>
 *
 * <p>The public OSM demo endpoints the app used to call directly
 * (nominatim.openstreetmap.org, photon.komoot.io, router.project-osrm.org) are
 * deliberately absent: all three forbid this use, and Nominatim's policy bans
 * autocomplete outright.
 */
@Component
@ConfigurationProperties(prefix = "geo")
public class GeoProperties {

  /** Per-capability provider order. First enabled provider that answers wins. */
  private List<String> autocompleteOrder = List.of("ola", "google", "local");
  private List<String> placeDetailsOrder = List.of("ola", "google", "local");
  private List<String> reverseGeocodeOrder = List.of("ola", "google");
  private List<String> routingOrder = List.of("osrm", "ola", "google");

  /** Per-request budget for a single provider call. */
  private int perProviderTimeoutMs = 2500;

  /**
   * Consecutive failures before a provider is skipped, and how long for.
   *
   * <p>Without this, a provider that is down costs every request its full timeout
   * before the chain reaches a working one — which turns one outage into
   * across-the-board latency.
   */
  private int circuitBreakerThreshold = 5;
  private int circuitBreakerCooldownMs = 30_000;

  /** Hyderabad-only launch: bias and bound text searches to the service area. */
  private double defaultBiasLat = 17.3850;
  private double defaultBiasLng = 78.4867;

  private Ola ola = new Ola();
  private Osrm osrm = new Osrm();
  private Google google = new Google();
  private Cache cache = new Cache();

  public static class Ola {
    private String baseUrl = "https://api.olamaps.io";
    /** Blank disables the provider; the chain then falls through to the next one. */
    private String apiKey = "";

    /**
     * Sent as the {@code Origin} header on every Ola request.
     *
     * <p>Ola restricts a credential to an allowlist of domains and enforces it against
     * this header, not against the calling IP. A server sends no {@code Origin} of its
     * own, so without this every call comes back
     * {@code 403 {"message":"Domain  is not allowed."}} — note the empty domain in the
     * message, which is the tell.
     *
     * <p>The value must match an entry in the credential's allowed-domains list in the
     * Ola console. The list accepts wildcards, so {@code *.mysuperhero.xyz} covers this
     * default.
     */
    private String origin = "https://api.mysuperhero.xyz";

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getOrigin() {
      return origin;
    }

    public void setOrigin(String origin) {
      this.origin = origin;
    }
  }

  public static class Osrm {
    /** Blank disables it. Point at a self-hosted instance, never the demo server. */
    private String baseUrl = "";

    /**
     * The area the deployed OSM extract actually covers, as
     * {@code minLat,minLng,maxLat,maxLng}.
     *
     * <p>We run a greater-Hyderabad extract, so OSRM cannot route anything outside it —
     * it returns {@code NoSegment} and we have burned a timeout finding that out. The
     * bbox lets the chain skip straight to Ola for out-of-area requests instead.
     *
     * <p>The default mirrors exactly what {@code build-osrm-hyderabad.sh} clips: the 55km
     * Hyderabad service radius plus a 15km routing buffer. That script expresses it in
     * osmium's {@code minLng,minLat,maxLng,maxLat} order
     * ({@code 77.8267,16.7550,79.1467,18.0150}); this property uses lat-first, so the two
     * are the same box written differently. <b>If you rebuild the extract with a
     * different clip, change both.</b>
     *
     * <p>Blank disables the check, which is what you want the day the extract goes
     * national.
     */
    private String coverageBbox = "16.7550,77.8267,18.0150,79.1467";

    /**
     * Must match the server's {@code --max-table-size}.
     *
     * <p>OSRM rejects a whole {@code /table} request that exceeds this, so a caller
     * asking for more origins than the server allows gets no ETAs at all — and the
     * failure is quiet, degrading ranking to straight-line distance rather than
     * erroring. Declining locally keeps the two from silently disagreeing.
     */
    private int maxTableSize = 64;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getCoverageBbox() {
      return coverageBbox;
    }

    public void setCoverageBbox(String coverageBbox) {
      this.coverageBbox = coverageBbox;
    }

    public int getMaxTableSize() {
      return maxTableSize;
    }

    public void setMaxTableSize(int maxTableSize) {
      this.maxTableSize = maxTableSize;
    }
  }

  public static class Google {
    private String baseUrl = "https://maps.googleapis.com";
    /**
     * Server-side Places/Geocoding/Directions key. Must be a different, IP-restricted
     * key from the Android render key, which is restricted by package + SHA-1 and
     * ships inside the APK.
     */
    private String apiKey = "";

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }
  }

  /**
   * Cache TTLs.
   *
   * <p>A shared server-side cache is what keeps us inside Ola's free tier: one
   * lookup of "hitech city" serves every citizen who types it, where the old
   * per-device caches re-billed each one. Place coordinates and street addresses
   * barely change, so their TTLs are long.
   */
  public static class Cache {
    private int autocompleteTtlSeconds = 86_400;
    private int placeDetailsTtlSeconds = 2_592_000;
    private int reverseGeocodeTtlSeconds = 2_592_000;
    private int routeTtlSeconds = 60;

    public int getAutocompleteTtlSeconds() {
      return autocompleteTtlSeconds;
    }

    public void setAutocompleteTtlSeconds(int autocompleteTtlSeconds) {
      this.autocompleteTtlSeconds = autocompleteTtlSeconds;
    }

    public int getPlaceDetailsTtlSeconds() {
      return placeDetailsTtlSeconds;
    }

    public void setPlaceDetailsTtlSeconds(int placeDetailsTtlSeconds) {
      this.placeDetailsTtlSeconds = placeDetailsTtlSeconds;
    }

    public int getReverseGeocodeTtlSeconds() {
      return reverseGeocodeTtlSeconds;
    }

    public void setReverseGeocodeTtlSeconds(int reverseGeocodeTtlSeconds) {
      this.reverseGeocodeTtlSeconds = reverseGeocodeTtlSeconds;
    }

    public int getRouteTtlSeconds() {
      return routeTtlSeconds;
    }

    public void setRouteTtlSeconds(int routeTtlSeconds) {
      this.routeTtlSeconds = routeTtlSeconds;
    }
  }

  public List<String> getAutocompleteOrder() {
    return autocompleteOrder;
  }

  public void setAutocompleteOrder(List<String> autocompleteOrder) {
    this.autocompleteOrder = autocompleteOrder;
  }

  public List<String> getPlaceDetailsOrder() {
    return placeDetailsOrder;
  }

  public void setPlaceDetailsOrder(List<String> placeDetailsOrder) {
    this.placeDetailsOrder = placeDetailsOrder;
  }

  public List<String> getReverseGeocodeOrder() {
    return reverseGeocodeOrder;
  }

  public void setReverseGeocodeOrder(List<String> reverseGeocodeOrder) {
    this.reverseGeocodeOrder = reverseGeocodeOrder;
  }

  public List<String> getRoutingOrder() {
    return routingOrder;
  }

  public void setRoutingOrder(List<String> routingOrder) {
    this.routingOrder = routingOrder;
  }

  public int getPerProviderTimeoutMs() {
    return perProviderTimeoutMs;
  }

  public void setPerProviderTimeoutMs(int perProviderTimeoutMs) {
    this.perProviderTimeoutMs = perProviderTimeoutMs;
  }

  public int getCircuitBreakerThreshold() {
    return circuitBreakerThreshold;
  }

  public void setCircuitBreakerThreshold(int circuitBreakerThreshold) {
    this.circuitBreakerThreshold = circuitBreakerThreshold;
  }

  public int getCircuitBreakerCooldownMs() {
    return circuitBreakerCooldownMs;
  }

  public void setCircuitBreakerCooldownMs(int circuitBreakerCooldownMs) {
    this.circuitBreakerCooldownMs = circuitBreakerCooldownMs;
  }

  public double getDefaultBiasLat() {
    return defaultBiasLat;
  }

  public void setDefaultBiasLat(double defaultBiasLat) {
    this.defaultBiasLat = defaultBiasLat;
  }

  public double getDefaultBiasLng() {
    return defaultBiasLng;
  }

  public void setDefaultBiasLng(double defaultBiasLng) {
    this.defaultBiasLng = defaultBiasLng;
  }

  public Ola getOla() {
    return ola;
  }

  public void setOla(Ola ola) {
    this.ola = ola;
  }

  public Osrm getOsrm() {
    return osrm;
  }

  public void setOsrm(Osrm osrm) {
    this.osrm = osrm;
  }

  public Google getGoogle() {
    return google;
  }

  public void setGoogle(Google google) {
    this.google = google;
  }

  public Cache getCache() {
    return cache;
  }

  public void setCache(Cache cache) {
    this.cache = cache;
  }
}
