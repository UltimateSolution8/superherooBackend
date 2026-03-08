package com.helpinminutes.api.kyc.controller;

import com.helpinminutes.api.errors.ForbiddenException;
import com.helpinminutes.api.kyc.dto.KycStartRequest;
import com.helpinminutes.api.kyc.dto.KycStartResponse;
import com.helpinminutes.api.kyc.dto.KycStatusResponse;
import com.helpinminutes.api.kyc.dto.KycUploadedRequest;
import com.helpinminutes.api.kyc.service.KycService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/helper/video-kyc")
public class HelperVideoKycController {
    private final KycService kycService;

    public HelperVideoKycController(KycService kycService) {
        this.kycService = kycService;
    }

    @PostMapping("/start")
    public KycStartResponse startKyc(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody KycStartRequest req) {
        if (principal.role() != UserRole.HELPER) {
            throw new ForbiddenException("Not a helper");
        }
        return kycService.startKyc(principal.userId(), req);
    }

    @PostMapping("/{id}/uploaded")
    public void markUploaded(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody KycUploadedRequest req) {
        if (principal.role() != UserRole.HELPER) {
            throw new ForbiddenException("Not a helper");
        }
        kycService.markUploaded(id, principal.userId(), req);
    }

    @GetMapping("/{id}/status")
    public KycStatusResponse getStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID id) {
        if (principal.role() != UserRole.HELPER) {
            throw new ForbiddenException("Not a helper");
        }
        return kycService.getStatus(id, principal.userId());
    }
}
