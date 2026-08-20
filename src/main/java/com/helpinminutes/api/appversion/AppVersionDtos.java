package com.helpinminutes.api.appversion;

public final class AppVersionDtos {
  private AppVersionDtos() {}

  /**
   * What a client needs to decide whether to show an update gate.
   *
   * @param minimumVersion below this the API refuses the client outright (426)
   * @param latestVersion newest published build, for a soft "update available" hint
   * @param storeUrl where to send the user; null falls back to the app's own link
   * @param updateMessage optional operator-supplied explanation
   */
  public record VersionPolicy(
      String minimumVersion, String latestVersion, String storeUrl, String updateMessage) {}
}
