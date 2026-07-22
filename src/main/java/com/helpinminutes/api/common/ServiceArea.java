package com.helpinminutes.api.common;

public final class ServiceArea {
  private ServiceArea() {}

  public static final double HYDERABAD_LAT = 17.3850d;
  public static final double HYDERABAD_LNG = 78.4867d;
  public static final double HYDERABAD_RADIUS_METERS = 55_000d;

  public static boolean isWithinHyderabad(double lat, double lng) {
    String enforced = System.getenv("SERVICE_AREA_ENFORCED");
    if ("false".equalsIgnoreCase(enforced)) {
      return true;
    }
    double dist = GeoUtils.distanceMeters(HYDERABAD_LAT, HYDERABAD_LNG, lat, lng);
    return dist <= HYDERABAD_RADIUS_METERS;
  }
}
