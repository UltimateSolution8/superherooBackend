package com.helpinminutes.api.kyc.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.kyc.dto.KycActionRequest;
import com.helpinminutes.api.kyc.dto.AdminKycResponse;
import com.helpinminutes.api.kyc.dto.LiveKycStartRequest;
import com.helpinminutes.api.kyc.dto.LiveKycSessionResponse;
import com.helpinminutes.api.kyc.dto.LiveKycSnapshotRequest;
import com.helpinminutes.api.kyc.dto.LiveKycSnapshotUrlResponse;
import com.helpinminutes.api.kyc.model.KycRequestStatus;
import com.helpinminutes.api.kyc.service.KycService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/video-kyc")
public class AdminVideoKycController {
    private final KycService kycService;

    public AdminVideoKycController(KycService kycService) {
        this.kycService = kycService;
    }

    private static void requireAdminConsole(UserPrincipal principal) {
        if (principal.role() != UserRole.ADMIN
                && principal.role() != UserRole.ADMIN_READONLY
                && principal.role() != UserRole.KYC
                && principal.role() != UserRole.SUPPORT) {
            throw new ForbiddenException("Not an admin");
        }
    }

    private static void requireKycReviewer(UserPrincipal principal) {
        if (principal.role() != UserRole.ADMIN && principal.role() != UserRole.KYC) {
            throw new ForbiddenException("KYC reviewer access required");
        }
    }

    @GetMapping
    public Page<AdminKycResponse> listRequests(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) KycRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireAdminConsole(principal);
        return kycService.listRequestsForAdmin(status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PostMapping("/{id}/action")
    public void performAction(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody KycActionRequest req) {
        requireKycReviewer(principal);
        kycService.adminAction(id, principal.userId(), req);
    }

    @PostMapping("/live/start")
    public LiveKycSessionResponse startLive(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody LiveKycStartRequest req) {
        requireKycReviewer(principal);
        return kycService.startLiveKyc(req.helperId(), principal.userId());
    }

    @PostMapping("/live/{id}/snapshot-url")
    public LiveKycSnapshotUrlResponse snapshotUrl(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID id,
            @RequestParam("kind") String kind) {
        requireKycReviewer(principal);
        return kycService.createLiveSnapshotUrl(id, principal.userId(), kind);
    }

    @PostMapping("/live/{id}/snapshot")
    public void snapshotConfirm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody LiveKycSnapshotRequest req) {
        requireKycReviewer(principal);
        kycService.confirmLiveSnapshot(id, principal.userId(), req);
    }

    @PostMapping("/live/{id}/end")
    public void endLive(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID id) {
        requireKycReviewer(principal);
        kycService.endLiveSession(id, principal.userId());
    }
}
