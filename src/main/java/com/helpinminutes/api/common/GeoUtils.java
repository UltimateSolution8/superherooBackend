package com.helpinminutes.api.common;

public final class GeoUtils {
  private GeoUtils() {}

  // Returns meters using Haversine.
  public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double r = 6371000.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
            * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2)
            * Math.sin(dLon / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return r * c;
  }

  /**
   * A latitude/longitude box that fully contains the circle of {@code radiusMeters}
   * around the given point.
   *
   * <p>The box is a cheap pre-filter for the indexed range scan; callers still
   * have to reject the corners with {@link #distanceMeters}, because a box always
   * covers more area than the circle inscribed in it.
   *
   * <p>The same arithmetic was duplicated in TaskService and HelperService with
   * different page sizes. Keeping one copy stops the two drifting apart.
   */
  public static BoundingBox boundingBox(double lat, double lng, double radiusMeters) {
    double latDelta = radiusMeters / 111_320d;
    // Longitude degrees shrink towards the poles. Floored so a point near a pole
    // cannot produce an unbounded (or infinite) longitude span.
    double cosLat = Math.max(0.1d, Math.abs(Math.cos(Math.toRadians(lat))));
    double lngDelta = radiusMeters / (111_320d * cosLat);
    return new BoundingBox(lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta);
  }

  public record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {}
}
