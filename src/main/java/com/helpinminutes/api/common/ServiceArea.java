package com.helpinminutes.api.common;

public final class ServiceArea {
  private ServiceArea() {}

  public static final double HYDERABAD_LAT = 17.3850d;
  public static final double HYDERABAD_LNG = 78.4867d;
  public static final double HYDERABAD_RADIUS_METERS = 55_000d;

  public static boolean isWithinHyderabad(double lat, double lng) {
    return GeoUtils.distanceMeters(lat, lng, HYDERABAD_LAT, HYDERABAD_LNG) <= HYDERABAD_RADIUS_METERS;
  }
}
