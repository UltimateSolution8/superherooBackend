package com.helpinminutes.api.payments.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.payments.model.CommissionSettingEntity;
import com.helpinminutes.api.payments.service.CommissionService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the commission rate be changed without a deploy.
 *
 * <p>Full admin only. This directly determines what every partner is paid, so it
 * is not something the KYC or support roles get.
 */
@RestController
@RequestMapping("/api/v1/admin/commission")
public class CommissionAdminController {

  private final CommissionService commissions;

  public CommissionAdminController(CommissionService commissions) {
    this.commissions = commissions;
  }

  @GetMapping
  public CommissionView current(@AuthenticationPrincipal UserPrincipal principal) {
    requireFullAdmin(principal);
    return new CommissionView(
        commissions.globalBps(), commissions.currentSettings().stream().map(RateView::of).toList());
  }

  @PutMapping
  public RateView setRate(
      @AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody SetRateRequest request) {
    requireFullAdmin(principal);
    return RateView.of(
        commissions.setRate(
            request.scope(),
            request.scopeRef(),
            request.commissionBps(),
            principal.userId(),
            request.note()));
  }

  private static void requireFullAdmin(UserPrincipal principal) {
    if (principal == null || principal.role() != UserRole.ADMIN) {
      throw new ForbiddenException("Super admin only");
    }
  }

  /**
   * @param scope GLOBAL, CATEGORY or HELPER. Defaults to GLOBAL.
   * @param scopeRef the category name or helper id; required unless GLOBAL
   * @param commissionBps basis points — 1500 is 15%. Capped at 5000 (50%).
   */
  public record SetRateRequest(
      String scope,
      String scopeRef,
      @NotNull @Min(0) @Max(5000) Integer commissionBps,
      String note) {}

  public record CommissionView(int globalBps, List<RateView> rates) {}

  public record RateView(
      String scope, String scopeRef, int commissionBps, Instant effectiveFrom, String note) {
    static RateView of(CommissionSettingEntity e) {
      return new RateView(
          e.getScope(), e.getScopeRef(), e.getCommissionBps(), e.getEffectiveFrom(), e.getNote());
    }
  }
}
