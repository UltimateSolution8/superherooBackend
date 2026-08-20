package com.helpinminutes.api.appversion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses API calls from client builds below the configured minimum, with
 * 426 Upgrade Required.
 *
 * <p>The app also shows a blocking screen, but that is presentation. Rule 5: the
 * endpoint has to refuse too, or a modified client — or simply one whose gate
 * screen failed to render — keeps transacting on a build we have withdrawn.
 *
 * <p>Two carve-outs, both necessary:
 * <ul>
 *   <li>{@code /api/v1/app/version} — otherwise a blocked client cannot find out
 *       why it is blocked, or where to get the update.
 *   <li>health endpoints — monitoring sends no version header, and an outage
 *       alarm must not depend on this.
 * </ul>
 *
 * <p>Auth routes are deliberately <em>not</em> exempt. "You must update" means the
 * old build stops working, and a build that can still sign in has not stopped.
 *
 * <p>A request with no version header passes. Web clients send none, and failing
 * closed on a missing header would take them all down the moment a minimum is set.
 */
public class AppVersionGateFilter extends OncePerRequestFilter {

  public static final String HEADER_VERSION = "X-App-Version";
  public static final String HEADER_VARIANT = "X-App-Variant";
  public static final String HEADER_PLATFORM = "X-App-Platform";

  private final AppVersionService versions;

  public AppVersionGateFilter(AppVersionService versions) {
    this.versions = versions;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/api/v1/app/version")
        || path.startsWith("/health")
        || path.startsWith("/api/v1/health")
        || path.startsWith("/actuator");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String clientVersion = request.getHeader(HEADER_VERSION);
    String variant = request.getHeader(HEADER_VARIANT);

    if (clientVersion != null
        && !clientVersion.isBlank()
        && versions.isBelowMinimum(variant, clientVersion)) {
      AppVersionDtos.VersionPolicy policy = versions.policyFor(variant);
      response.setStatus(426);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"code\":\"UPGRADE_REQUIRED\",\"message\":\"A newer version of the app is required.\""
                  + ",\"minimumVersion\":"
                  + json(policy.minimumVersion())
                  + ",\"storeUrl\":"
                  + json(policy.storeUrl())
                  + "}");
      return;
    }

    chain.doFilter(request, response);
  }

  private static String json(String value) {
    if (value == null) return "null";
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
