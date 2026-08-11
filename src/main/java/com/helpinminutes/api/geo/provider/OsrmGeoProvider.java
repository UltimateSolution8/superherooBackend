package com.helpinminutes.api.geo.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.helpinminutes.api.geo.GeoDtos;
import com.helpinminutes.api.geo.GeoHttp;
import com.helpinminutes.api.geo.GeoProperties;
import com.helpinminutes.api.geo.GeoProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Self-hosted OSRM — routing and ETA matrices only.
 *
 * <p>This is where OpenStreetMap earns its place. Routing is by far our
 * highest-frequency geo call (a route refresh per active task, every 30s, plus one
 * ETA matrix per dispatch), and on a local OSRM instance it costs nothing, answers
 * in single-digit milliseconds, and has no quota to blow through.
 *
 * <p>Deliberately points at a private instance. The app previously called
 * {@code router.project-osrm.org} directly; that demo server's policy states
 * access "shall be withdrawn at any time and without giving a reason", which is
 * not something a paying flow can depend on. Blank {@code baseUrl} disables this
 * provider entirely and the chain falls through to Ola.
 *
 * <p>OSRM has no geocoding, so it declines the text capabilities rather than
 * making the chain spend a timeout finding out.
 */
@Component
public class OsrmGeoProvider implements GeoProvider {

  private static final String NAME = "osrm";
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(OsrmGeoProvider.class);

  private final GeoProperties props;
  private final GeoHttp http;

  public OsrmGeoProvider(GeoProperties props, GeoHttp http) {
    this.props = props;
    this.http = http;
  }

  /**
   * The extract's coverage area, parsed once per call from config.
   *
   * <p>Cheap enough to reparse (four doubles) and it keeps the provider stateless, so a
   * config refresh takes effect without a restart.
   */
  private record Bbox(double minLat, double minLng, double maxLat, double maxLng) {
    boolean contains(double lat, double lng) {
      return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
    }
  }

  /** Empty when no bbox is configured, meaning "assume global coverage". */
  private Optional<Bbox> coverage() {
    String raw = props.getOsrm().getCoverageBbox();
    if (raw == null || raw.isBlank()) return Optional.empty();
    String[] parts = raw.split(",");
    if (parts.length != 4) {
      log.warn("OSRM coverage bbox '{}' is malformed; expected minLat,minLng,maxLat,maxLng", raw);
      return Optional.empty();
    }
    try {
      return Optional.of(new Bbox(
          Double.parseDouble(parts[0].trim()),
          Double.parseDouble(parts[1].trim()),
          Double.parseDouble(parts[2].trim()),
          Double.parseDouble(parts[3].trim())));
    } catch (NumberFormatException e) {
      log.warn("OSRM coverage bbox '{}' is not numeric; ignoring", raw);
      return Optional.empty();
    }
  }

  /**
   * True when every supplied point lies inside the extract.
   *
   * <p>Every point, not just one: a route from inside the extract to outside it is just
   * as unroutable as one entirely outside.
   */
  private boolean covers(double... latLngPairs) {
    Optional<Bbox> bbox = coverage();
    if (bbox.isEmpty()) return true;
    for (int i = 0; i + 1 < latLngPairs.length; i += 2) {
      if (!bbox.get().contains(latLngPairs[i], latLngPairs[i + 1])) return false;
    }
    return true;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled() {
    return props.getOsrm().getBaseUrl() != null && !props.getOsrm().getBaseUrl().isBlank();
  }

  @Override
  public boolean supportsRouting() {
    return true;
  }

  @Override
  public boolean supportsEtaMatrix() {
    return true;
  }

  @Override
  public Optional<GeoDtos.Route> route(double fromLat, double fromLng, double toLat, double toLng) {
    if (!isEnabled()) return Optional.empty();
    // Declining here rather than letting OSRM answer NoSegment saves the chain a full
    // timeout before it reaches a provider with national coverage.
    if (!covers(fromLat, fromLng, toLat, toLng)) {
      log.debug("Route falls outside the OSRM extract; deferring to the next provider");
      return Optional.empty();
    }
    String url = baseUrl() + "/route/v1/driving/"
        + fromLng + "," + fromLat + ";" + toLng + "," + toLat
        + "?overview=full&geometries=polyline&alternatives=false&steps=false";

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME).flatMap(root -> {
      JsonNode route = root.path("routes").path(0);
      // OSRM's `polyline` geometry is Google-encoded at precision 5, which is
      // exactly what the app's decodePolyline expects — no conversion needed.
      String polyline = GeoHttp.asText(route.path("geometry"));
      Double duration = GeoHttp.asDouble(route.path("duration"));
      Double distance = GeoHttp.asDouble(route.path("distance"));
      if (polyline == null && duration == null) return Optional.empty();
      return Optional.of(new GeoDtos.Route(
          polyline,
          duration == null ? null : (int) Math.round(duration),
          distance == null ? null : (int) Math.round(distance)));
    });
  }

  /**
   * One {@code /table} call for every candidate partner at once.
   *
   * <p>Ranking N candidates by real travel time would otherwise be N route calls.
   * Here it is a single request: origins are {@code sources}, the task location is
   * the sole {@code destination}.
   */
  @Override
  public Optional<List<Integer>> etaMatrixToDestination(
      List<double[]> origins, double destLat, double destLng) {
    if (!isEnabled() || origins == null || origins.isEmpty()) return Optional.empty();

    // origins + the one destination is the table size OSRM will see. Exceeding the
    // server's --max-table-size fails the whole request, so decline instead and let the
    // caller fall back to straight-line estimates for the full set.
    int tableSize = origins.size() + 1;
    if (tableSize > props.getOsrm().getMaxTableSize()) {
      log.warn("Matrix of {} exceeds OSRM max-table-size {}; deferring to the next provider",
          tableSize, props.getOsrm().getMaxTableSize());
      return Optional.empty();
    }

    if (!covers(destLat, destLng)) {
      return Optional.empty();
    }
    for (double[] origin : origins) {
      // One out-of-area partner would not fail the request, but it would come back as a
      // null row and silently rank that partner on a fallback estimate while everyone
      // else was ranked on real travel time — an unfair comparison. Better to have the
      // whole set scored the same way.
      if (!covers(origin[0], origin[1])) {
        log.debug("Candidate outside the OSRM extract; scoring the whole set elsewhere");
        return Optional.empty();
      }
    }

    StringJoiner coordinates = new StringJoiner(";");
    for (double[] origin : origins) {
      coordinates.add(origin[1] + "," + origin[0]);
    }
    coordinates.add(destLng + "," + destLat);

    StringJoiner sourceIndexes = new StringJoiner(";");
    for (int i = 0; i < origins.size(); i++) {
      sourceIndexes.add(Integer.toString(i));
    }

    String url = baseUrl() + "/table/v1/driving/" + coordinates
        + "?sources=" + sourceIndexes
        + "&destinations=" + origins.size()
        + "&annotations=duration";

    return http.getJson(url, props.getPerProviderTimeoutMs(), NAME).flatMap(root -> {
      JsonNode durations = root.path("durations");
      if (!durations.isArray() || durations.size() != origins.size()) return Optional.empty();
      List<Integer> etas = new ArrayList<>(origins.size());
      for (JsonNode row : durations) {
        Double seconds = GeoHttp.asDouble(row.path(0));
        etas.add(seconds == null ? null : (int) Math.round(seconds));
      }
      return Optional.of(etas);
    });
  }

  private String baseUrl() {
    String base = props.getOsrm().getBaseUrl().trim();
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }
}
