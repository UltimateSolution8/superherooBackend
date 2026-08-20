package com.helpinminutes.api.appversion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint an outdated client can still reach.
 *
 * <p>Unauthenticated on purpose. A blocked user cannot sign in — the version gate
 * refuses auth routes too — so if this required a token there would be no way for
 * a stale build to discover that it is stale.
 */
@RestController
@RequestMapping("/api/v1/app")
public class AppVersionController {

  private final AppVersionService versions;

  public AppVersionController(AppVersionService versions) {
    this.versions = versions;
  }

  @GetMapping("/version")
  public AppVersionDtos.VersionPolicy version(
      @RequestParam(required = false, defaultValue = "android") String platform,
      @RequestParam(required = false, defaultValue = "buyer") String variant) {
    return versions.policyFor(variant);
  }
}
