package com.helpinminutes.api.common;

public final class ServiceArea {
  private ServiceArea() {}

  public static final double HYDERABAD_LAT = 17.3850d;
  public static final double HYDERABAD_LNG = 78.4867d;
  public static final double HYDERABAD_RADIUS_METERS = 55_000d;

  public static final double BANGALORE_LAT = 12.9716d;
  public static final double BANGALORE_LNG = 77.5946d;
  public static final double BANGALORE_RADIUS_METERS = 55_000d;

  public static boolean isWithinIndia(double lat, double lng) {
    String enforced = System.getenv("SERVICE_AREA_ENFORCED");
    if ("false".equalsIgnoreCase(enforced)) {
      return true;
    }
    return lat >= 6.0 && lat <= 37.5 && lng >= 68.0 && lng <= 97.5;
  }

  public static boolean isWithinHyderabad(double lat, double lng) {
    if ("false".equalsIgnoreCase(System.getenv("SERVICE_AREA_ENFORCED"))) {
      return true;
    }
    if (!isWithinIndia(lat, lng)) {
      return false;
    }
    double distance = GeoUtils.distanceMeters(HYDERABAD_LAT, HYDERABAD_LNG, lat, lng);
    return distance <= HYDERABAD_RADIUS_METERS;
  }

  public static boolean isWithinBangalore(double lat, double lng) {
    if ("false".equalsIgnoreCase(System.getenv("SERVICE_AREA_ENFORCED"))) {
      return true;
    }
    if (!isWithinIndia(lat, lng)) {
      return false;
    }
    double distance = GeoUtils.distanceMeters(BANGALORE_LAT, BANGALORE_LNG, lat, lng);
    return distance <= BANGALORE_RADIUS_METERS;
  }

  public static boolean isWithinServiceArea(double lat, double lng) {
    if ("false".equalsIgnoreCase(System.getenv("SERVICE_AREA_ENFORCED"))) {
      return true;
    }
    return isWithinHyderabad(lat, lng) || isWithinBangalore(lat, lng);
  }

  public static String resolveCity(double lat, double lng) {
    if (isWithinHyderabad(lat, lng)) {
      return "HYDERABAD";
    }
    if (isWithinBangalore(lat, lng)) {
      return "BANGALORE";
    }
    return null;
  }
}
