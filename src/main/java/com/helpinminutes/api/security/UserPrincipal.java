package com.helpinminutes.api.security;

import com.helpinminutes.api.users.model.UserRole;
import java.util.UUID;

public record UserPrincipal(UUID userId, UserRole role) {}
