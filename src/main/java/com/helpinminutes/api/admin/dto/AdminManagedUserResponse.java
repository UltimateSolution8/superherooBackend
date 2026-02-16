package com.helpinminutes.api.admin.dto;

import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminManagedUserResponse(
    UUID id,
    UserRole role,
    UserStatus status,
    String phone,
    String email,
    String displayName,
    Instant createdAt,
    HelperKycStatus helperKycStatus,
    String helperKycFullName,
    String helperKycIdNumber,
    String helperKycDocFrontUrl,
    String helperKycDocBackUrl,
    String helperKycSelfieUrl,
    Instant helperKycSubmittedAt
) {}
