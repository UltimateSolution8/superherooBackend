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
    // Allow India: Lat between 8.0 and 38.0, Lng between 68.0 and 98.0
    return lat >= 8.0 && lat <= 38.0 && lng >= 68.0 && lng <= 98.0;
  }
}
