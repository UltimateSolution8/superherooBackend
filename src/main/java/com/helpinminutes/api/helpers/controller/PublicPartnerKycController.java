package com.helpinminutes.api.helpers.controller;

import com.helpinminutes.api.helpers.dto.PublicPartnerKycResponse;
import com.helpinminutes.api.helpers.service.PublicPartnerKycService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/public/partner-kyc")
public class PublicPartnerKycController {
  private final PublicPartnerKycService service;

  public PublicPartnerKycController(PublicPartnerKycService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PublicPartnerKycResponse submit(
      @RequestParam String fullName,
      @RequestParam String phone,
      @RequestParam String email,
      @RequestParam(required = false) String docType,
      @RequestParam String idNumber,
      @RequestParam MultipartFile idFront,
      @RequestParam(required = false) MultipartFile idBack,
      @RequestParam MultipartFile selfie,
      @RequestParam(required = false) String accountHolderName,
      @RequestParam(required = false) String bankName,
      @RequestParam(required = false) String bankAccountLast4,
      @RequestParam(required = false) String ifscCode,
      @RequestParam(required = false) String upiIdMasked) {
    return service.submit(
        fullName,
        phone,
        email,
        docType,
        idNumber,
        idFront,
        idBack,
        selfie,
        accountHolderName,
        bankName,
        bankAccountLast4,
        ifscCode,
        upiIdMasked);
  }
}
