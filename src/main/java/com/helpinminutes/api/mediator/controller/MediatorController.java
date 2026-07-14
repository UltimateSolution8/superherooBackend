package com.helpinminutes.api.mediator.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.mediator.dto.MediatorDtos.*;
import com.helpinminutes.api.mediator.service.MediatorService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mediator")
public class MediatorController {
  private final MediatorService mediatorService;

  public MediatorController(MediatorService mediatorService) {
    this.mediatorService = mediatorService;
  }

  private void checkRole(UserPrincipal principal) {
    if (principal.role() != UserRole.MEDIATOR && principal.role() != UserRole.ADMIN) {
      throw new ForbiddenException("Only mediators or admins can access these endpoints");
    }
  }

  @GetMapping("/jobs")
  public List<MediatorJobResponse> listAvailableJobs(@AuthenticationPrincipal UserPrincipal principal) {
    checkRole(principal);
    return mediatorService.listAvailableJobs();
  }

  @GetMapping("/jobs/my")
  public List<MediatorJobResponse> listMyJobs(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false) String status) {
    checkRole(principal);
    return mediatorService.listMyJobs(principal.userId(), status);
  }

  @GetMapping("/jobs/{batchId}")
  public MediatorJobResponse getJob(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    checkRole(principal);
    return mediatorService.getJob(principal.userId(), principal.role(), batchId);
  }

  @PostMapping("/jobs/{batchId}/accept")
  public MediatorJobResponse acceptJob(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @RequestBody(required = false) AcceptJobRequest req) {
    checkRole(principal);
    return mediatorService.acceptJob(principal.userId(), batchId, req);
  }

  @PostMapping("/jobs/{batchId}/workers")
  public AddWorkersResponse addWorkers(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @Valid @RequestBody AddWorkersRequest req) {
    checkRole(principal);
    return mediatorService.addWorkers(principal.userId(), principal.role(), batchId, req);
  }

  @GetMapping("/jobs/{batchId}/workers/lookup")
  public WorkerLookupResponse lookupWorker(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @RequestParam String query) {
    checkRole(principal);
    return mediatorService.lookupWorker(principal.userId(), principal.role(), batchId, query);
  }

  @DeleteMapping("/jobs/{batchId}/workers/{helperId}")
  public void removeWorker(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @PathVariable UUID helperId) {
    checkRole(principal);
    mediatorService.removeWorker(principal.userId(), principal.role(), batchId, helperId);
  }

  @GetMapping("/jobs/{batchId}/workers")
  public List<MediatorWorkerDetail> getWorkers(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    checkRole(principal);
    return mediatorService.getWorkers(principal.userId(), principal.role(), batchId);
  }

  @PostMapping("/jobs/{batchId}/dispatch")
  public void dispatchJob(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    checkRole(principal);
    mediatorService.dispatchJob(principal.userId(), principal.role(), batchId);
  }

  @PostMapping("/jobs/{batchId}/start")
  public void startJob(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @Valid @RequestBody BatchOtpRequest req) {
    checkRole(principal);
    mediatorService.startJob(principal.userId(), principal.role(), batchId, req.otp());
  }

  @PostMapping("/jobs/{batchId}/attendance")
  public void submitAttendance(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @Valid @RequestBody AttendanceRequest req) {
    checkRole(principal);
    mediatorService.submitAttendance(principal.userId(), principal.role(), batchId, req);
  }

  @PostMapping("/jobs/{batchId}/complete")
  public void completeJob(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId,
      @Valid @RequestBody(required = false) BatchOtpRequest req) {
    checkRole(principal);
    mediatorService.completeJob(principal.userId(), principal.role(), batchId, req == null ? null : req.otp());
  }

  @GetMapping("/jobs/{batchId}/payments")
  public PaymentBreakdownResponse getPaymentBreakdown(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID batchId) {
    checkRole(principal);
    return mediatorService.getPaymentBreakdown(principal.userId(), principal.role(), batchId);
  }

  @GetMapping("/dashboard")
  public MediatorDashboardResponse getDashboard(@AuthenticationPrincipal UserPrincipal principal) {
    checkRole(principal);
    return mediatorService.getDashboard(principal.userId());
  }
}
