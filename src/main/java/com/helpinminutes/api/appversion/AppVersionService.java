package com.helpinminutes.api.appversion;

import com.helpinminutes.api.config.AppProperties;
import org.springframework.stereotype.Service;

/**
 * Decides whether a given client build is still allowed to talk to this API.
 *
 * <p>Comparison is on the <em>version name</em> (a semver string like "1.1.0"),
 * never on {@code versionCode}. The apps compute their version code as
 * {@code APP_BUILD_NUMBER * 10 + variantOffset} — buyer 0, partner 1, mediator 2 —
 * so codes from different variants are not comparable, and a naive numeric gate
 * would silently apply the wrong threshold to two of the three apps.
 */
@Service
public class AppVersionService {

  private final AppProperties props;

  public AppVersionService(AppProperties props) {
    this.props = props;
  }

  public AppVersionDtos.VersionPolicy policyFor(String variantRaw) {
    String variant = normalizeVariant(variantRaw);
    AppProperties.AppVersions versions = props.appVersions();

    String minimum = switch (variant) {
      case "helper" -> versions.minHelper();
      case "mediator" -> versions.minMediator();
      default -> versions.minBuyer();
    };
    String latest = switch (variant) {
      case "helper" -> versions.latestHelper();
      case "mediator" -> versions.latestMediator();
      default -> versions.latestBuyer();
    };
    String storeUrl = switch (variant) {
      case "helper" -> versions.storeUrlHelper();
      case "mediator" -> versions.storeUrlMediator();
      default -> versions.storeUrlBuyer();
    };

    return new AppVersionDtos.VersionPolicy(
        blankToNull(minimum), blankToNull(latest), blankToNull(storeUrl), versions.updateMessage());
  }

  /**
   * True when this build must be blocked.
   *
   * <p>Unknown or unparseable versions are allowed through. A gate that fails
   * closed would lock out every client whose header we failed to read — including
   * the web clients, which send no version at all.
   */
  public boolean isBelowMinimum(String variant, String clientVersion) {
    String minimum = policyFor(variant).minimumVersion();
    if (minimum == null || clientVersion == null || clientVersion.isBlank()) return false;
    Integer comparison = compareVersions(clientVersion, minimum);
    return comparison != null && comparison < 0;
  }

  /**
   * Compares two dotted numeric versions. Returns null when either side is not
   * something we can compare, so the caller can decide (we let it through).
   */
  static Integer compareVersions(String left, String right) {
    int[] a = parse(left);
    int[] b = parse(right);
    if (a == null || b == null) return null;
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length ? a[i] : 0;
      int y = i < b.length ? b[i] : 0;
      if (x != y) return x < y ? -1 : 1;
    }
    return 0;
  }

  private static int[] parse(String version) {
    if (version == null) return null;
    // Tolerate a build suffix such as "1.2.0-rc1"; only the numeric core matters.
    String core = version.trim().split("[-+ ]")[0];
    if (core.isEmpty()) return null;
    String[] parts = core.split("\\.");
    int[] out = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        out[i] = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        return null;
      }
      if (out[i] < 0) return null;
    }
    return out;
  }

  static String normalizeVariant(String variant) {
    if (variant == null) return "buyer";
    String v = variant.trim().toLowerCase();
    return switch (v) {
      case "helper", "partner" -> "helper";
      case "mediator" -> "mediator";
      default -> "buyer";
    };
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
