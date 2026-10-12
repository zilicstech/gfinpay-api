package com.fintech.platform.security;

import java.util.UUID;

/** Authenticated caller extracted from the JWT, bound to the session row that issued it. */
public record AuthPrincipal(UUID userId, String name, String userType, UUID sessionId) {
    public boolean superAdmin() {
        return "SUPER_ADMIN".equals(userType);
    }

    public boolean platformAdmin() {
        return "ADMIN".equals(userType);
    }

    public boolean hubAdmin() {
        return "HUBADMIN".equals(userType);
    }

    public boolean superAdminOrPlatformAdmin() {
        return superAdmin() || platformAdmin();
    }

    public boolean platformStaff() {
        return superAdmin() || platformAdmin() || hubAdmin();
    }

    public boolean auditor() {
        return "AUDITOR".equals(userType);
    }
}
