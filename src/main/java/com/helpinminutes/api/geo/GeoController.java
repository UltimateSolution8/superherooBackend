package com.helpinminutes.api.geo;

import com.helpinminutes.api.common.ServiceArea;
import com.helpinminutes.api.errors.BadRequestException;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side proxy for place search, geocoding and routing.
 *
 * <p>Why proxy at all, when the app could call these directly:
 *
 * <ul>
 *   <li><b>Cost.</b> One shared cache serves every user. Per-device caches re-billed
 *       the same "hitech city" lookup for each citizen who typed it.
 *   <li><b>Keys.</b> A Places-enabled key inside an APK is extractable. Only the
 *       render key ships now, restricted by package name and signing certificate.
 *   <li><b>Switchability.</b> Provider order is config. Swapping or reordering
 *       providers no longer needs an app release — and the app previously had five
 *       separate autocomplete implementations with URLs inlined in the screens.
 * </ul>
 *
 * <p>These endpoints never return an error for an upstream failure. Each response
 * carries {@code degraded}, and the app renders regardless.
 */
@RestController
@RequestMapping({"/api/v1/geo", "/api/geo"})
public class GeoController {

  /** Below two characters, predictions are noise and the call is pure cost. */
  private static final int MIN_QUERY_LENGTH = 2;

  private final GeoProviderChain geo;
  private final GeoProperties props;
  private final GeoSpendGuard spendGuard;

  public GeoController(GeoProviderChain geo, GeoProperties props, GeoSpendGuard spendGuard) {
    this.geo = geo;
    this.props = props;
    this.spendGuard = spendGuard;
  }

  /**
   * @param context what the caller is doing, e.g. {@code task_create}. Only the
   *     contexts on the server's allowlist reach the paid provider; everything else,
   *     including an unrecognised value, gets the free order. The app states its
   *     intent and the server prices it — the app never names a provider.
   * @param sessionToken groups one address entry's keystrokes and its final details
   *     lookup into a single billing session upstream
   */
  @GetMapping("/autocomplete")
  public GeoDtos.SuggestionsResponse autocomplete(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam("q") String query,
      @RequestParam(value = "lat", required = false) Double lat,
      @RequestParam(value = "lng", required = false) Double lng,
      @RequestParam(value = "context", required = false) String context,
      @RequestParam(value = "sessionToken", required = false) String sessionToken) {
    if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
      return new GeoDtos.SuggestionsResponse(java.util.List.of(), "none", false);
    }
    // The daily budget is only spent by requests that ask for the paid tier, and
    // exhausting it downgrades the request rather than failing it: suggestions still
    // arrive, from the free provider.
    // Only the citizen role that can create a task may spend the premium budget.
    // A context string is intent metadata, not authority.
    boolean premium = principal != null
        && principal.role() == UserRole.BUYER
        && spendGuard.validSessionToken(sessionToken)
        && props.isPremiumContext(context)
        && spendGuard.allowNewPremiumSession()
        && spendGuard.allowPremiumForUser(principal.userId());
    GeoDtos.SuggestionsResponse response = geo.autocomplete(query, lat, lng, premium, sessionToken);
    if (premium && "google".equalsIgnoreCase(response.provider())) {
      if (!spendGuard.authorizePremiumDetails(principal.userId(), sessionToken)) {
        // Do not display a Google suggestion that this server cannot later resolve.
        // Retry through the Ola/local chain immediately; pin-drop remains available.
        return geo.autocomplete(query, lat, lng, false, sessionToken);
      }
    }
    return response;
  }

  @GetMapping("/place")
  public GeoDtos.GeoEnvelope<GeoDtos.PlaceDetail> placeDetails(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam("placeId") String placeId,
      @RequestParam(value = "sessionToken", required = false) String sessionToken) {
    if (placeId == null || placeId.isBlank()) {
      throw new BadRequestException("placeId is required");
    }
    if (placeId.regionMatches(true, 0, "google:", 0, "google:".length())
        && (principal == null || principal.role() != UserRole.BUYER
            || !spendGuard.consumePremiumDetailsAuthorization(principal.userId(), sessionToken))) {
      // This is either not a Google suggestion issued to this user/session or the
      // authorization store is unhealthy. Preserve the hard cost boundary and let
      // the app's pin-drop flow recover instead of making an unpriced paid call.
      return GeoDtos.GeoEnvelope.degraded(null);
    }
    return geo.placeDetails(placeId, sessionToken);
  }

  @GetMapping("/reverse")
  public GeoDtos.GeoEnvelope<GeoDtos.ReverseGeocode> reverseGeocode(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam("lat") double lat,
      @RequestParam("lng") double lng) {
    requireSaneCoordinate(lat, lng);
    return geo.reverseGeocode(lat, lng);
  }

  @GetMapping("/route")
  public GeoDtos.GeoEnvelope<GeoDtos.Route> route(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam("fromLat") double fromLat,
      @RequestParam("fromLng") double fromLng,
      @RequestParam("toLat") double toLat,
      @RequestParam("toLng") double toLng) {
    requireSaneCoordinate(fromLat, fromLng);
    requireSaneCoordinate(toLat, toLng);
    return geo.route(fromLat, fromLng, toLat, toLng);
  }

  /**
   * Rejects coordinates that cannot be a real request.
   *
   * <p>Bounded to India rather than to Hyderabad: a partner may legitimately be
   * just outside the service area, and the service-area rule belongs on task
   * creation, not on a lookup.
   */
  private static void requireSaneCoordinate(double lat, double lng) {
    if (!Double.isFinite(lat) || !Double.isFinite(lng) || !ServiceArea.isWithinIndia(lat, lng)) {
      throw new BadRequestException("Coordinates are outside the supported region");
    }
  }
}
