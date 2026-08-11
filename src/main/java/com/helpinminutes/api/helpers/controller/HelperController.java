package com.helpinminutes.api.helpers.controller;

import com.helpinminutes.api.helpers.dto.HelperBankDetailsResponse;
import com.helpinminutes.api.helpers.dto.SetOnlineRequest;
import com.helpinminutes.api.helpers.dto.HelperIdCardResponse;
import com.helpinminutes.api.helpers.dto.HelperProfileResponse;
import com.helpinminutes.api.helpers.dto.IfscLookupResponse;
import com.helpinminutes.api.helpers.dto.BankChangeChallengeResponse;
import com.helpinminutes.api.helpers.dto.BankChangeOtpVerifyRequest;
import com.helpinminutes.api.helpers.dto.BankChangeTokenResponse;
import com.helpinminutes.api.helpers.dto.PayoutAccountUpdateRequest;
import com.helpinminutes.api.helpers.service.BankChangeChallengeService;
import com.helpinminutes.api.helpers.service.HelperService;
import com.helpinminutes.api.helpers.service.IfscLookupService;
import com.helpinminutes.api.security.UserPrincipal;
import com.helpinminutes.api.security.ClientIpResolver;
import com.helpinminutes.api.users.model.UserRole;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/helper")
public class HelperController {
  private final HelperService helpers;
  private final IfscLookupService ifscLookup;
  private final BankChangeChallengeService bankChallenges;

  public HelperController(HelperService helpers, IfscLookupService ifscLookup, BankChangeChallengeService bankChallenges) {
    this.helpers = helpers;
    this.ifscLookup = ifscLookup;
    this.bankChallenges = bankChallenges;
  }

  @PutMapping("/online")
  public void setOnline(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody SetOnlineRequest req) {
    setOnlineInternal(principal, req);
  }

  // Backward compatibility: older mobile builds still call POST /online.
  @PostMapping("/online")
  public void setOnlineLegacy(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody SetOnlineRequest req) {
    setOnlineInternal(principal, req);
  }

  private void setOnlineInternal(UserPrincipal principal, SetOnlineRequest req) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    if (Boolean.TRUE.equals(req.online())) {
      if (req.lat() == null || req.lng() == null) {
        throw new com.helpinminutes.api.errors.BadRequestException("lat/lng required to go online");
      }
      helpers.setOnline(principal.userId(), req.lat(), req.lng());
    } else {
      helpers.setOffline(principal.userId());
    }
  }

  @GetMapping("/profile")
  public HelperProfileResponse profile(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    return helpers.getProfile(principal.userId());
  }

  @GetMapping("/id-card")
  public HelperIdCardResponse idCard(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    return helpers.getIdCard(principal.userId());
  }

  @PostMapping(value = "/kyc/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public HelperProfileResponse submitKyc(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam String fullName,
      @RequestParam(required = false) String docType,
      @RequestParam String idNumber,
      @RequestParam("idFront") MultipartFile idFront,
      @RequestParam(value = "idBack", required = false) MultipartFile idBack,
      @RequestParam("selfie") MultipartFile selfie,
      @RequestParam(required = false) String accountHolderName,
      @RequestParam(required = false) String bankAccountNumber,
      @RequestParam(required = false) String ifscCode) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    return helpers.submitKyc(principal.userId(), fullName, docType, idNumber, idFront, idBack, selfie,
        accountHolderName, bankAccountNumber, ifscCode);
  }

  @PostMapping("/payout-account/change-challenge")
  public BankChangeChallengeResponse startBankChange(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal.role() != UserRole.HELPER) throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    return bankChallenges.start(principal.userId(), UserRole.HELPER);
  }

  @PostMapping("/payout-account/change-challenge/verify")
  public BankChangeTokenResponse verifyBankChange(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody BankChangeOtpVerifyRequest req) {
    if (principal.role() != UserRole.HELPER) throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    return bankChallenges.verify(principal.userId(), UserRole.HELPER, req.challengeId(), req.otp());
  }

  @GetMapping("/ifsc/{code}")
  public IfscLookupResponse lookupIfsc(
      @AuthenticationPrincipal UserPrincipal principal,
      @org.springframework.web.bind.annotation.PathVariable String code) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    return ifscLookup.lookup(code);
  }

  @GetMapping("/payout-account")
  public HelperBankDetailsResponse payoutAccount(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    return helpers.getPayoutAccount(principal.userId());
  }

  @PutMapping("/payout-account")
  public HelperBankDetailsResponse savePayoutAccount(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody PayoutAccountUpdateRequest req,
      HttpServletRequest httpRequest) {
    if (principal.role() != UserRole.HELPER) {
      throw new com.helpinminutes.api.errors.ForbiddenException("Not a helper");
    }
    return helpers.savePayoutAccount(principal.userId(), req, ClientIpResolver.resolve(httpRequest));
  }
}
