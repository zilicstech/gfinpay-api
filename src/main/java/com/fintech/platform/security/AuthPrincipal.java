package com.fintech.platform.security;

import java.util.UUID;

/** Authenticated caller extracted from the JWT. */
public record AuthPrincipal(UUID userId, String name, String userType) {
    public boolean superAdmin() {
        return "SUPER_ADMIN".equals(userType);
    }

    public boolean hubAdmin() {
        return "ADMIN".equals(userType);
    }

    public boolean platformStaff() {
        return superAdmin() || hubAdmin();
    }
}
