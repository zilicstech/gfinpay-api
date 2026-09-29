package com.fintech.identity;

import com.fintech.ledger.WalletService;
import com.fintech.platform.security.JwtService;
import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final WalletService walletService;

    public UserService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                       JwtService jwtService, WalletService walletService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.walletService = walletService;
    }

    public Map<String, Object> login(String identifier, String password) {
        long startMs = System.currentTimeMillis();
        String key = identifier == null ? "" : identifier.trim();
        boolean byCode = key.toUpperCase(java.util.Locale.ROOT).startsWith("GFIN");
        log.info("LOGIN started byCode={}", byCode);
        try {
            Map<String, Object> user;
            try {
                String lookup = byCode ? "upper(code) = ?" : "mobile = ?";
                user = jdbc.queryForMap(
                        "SELECT id, user_type::text AS user_type, full_name, code, password_hash, status::text AS status FROM users WHERE " + lookup,
                        byCode ? key.toUpperCase(java.util.Locale.ROOT) : key);
            } catch (EmptyResultDataAccessException e) {
                throw ApiException.of(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid credentials");
            }
            if (!passwordEncoder.matches(password, (String) user.get("password_hash"))) {
                throw ApiException.of(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid credentials");
            }
            if (!"ACTIVE".equals(user.get("status"))) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE", "Account is not active");
            }
            UUID userId = (UUID) user.get("id");
            String userType = user.get("user_type").toString();
            List<String> permissions = permissionCodes(userId);
            JwtService.IssuedToken issued = jwtService.issue(userId, (String) user.get("full_name"), userType, permissions);
            log.info("LOGIN success userId={} type={}", userId, userType);
            return Map.of(
                    "token", issued.token(),
                    "role", userType,
                    "expiresAt", issued.expiresAt().toString());
        } catch (ApiException e) {
            log.warn("LOGIN failed byCode={} reason={}", byCode, e.getCode());
            throw e;
        } finally {
            log.info("LOGIN completed in {} ms", System.currentTimeMillis() - startMs);
        }
    }

    @Transactional
    public Map<String, Object> createDistributor(String fullName, String mobile, String email,
                                                 String password, UUID adminId) {
        return createUser("MASTER_DISTRIBUTOR", 2, fullName, mobile, email, password, adminId, adminId,
                null, null, null);
    }

    @Transactional
    public Map<String, Object> createRetailer(String fullName, String mobile, String email, String password,
                                              UUID distributorId, String city, String state, String pincode,
                                              UUID adminId) {
        String parentType;
        try {
            parentType = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?",
                    String.class, distributorId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "DISTRIBUTOR_NOT_FOUND", "Distributor does not exist");
        }
        if (!"MASTER_DISTRIBUTOR".equals(parentType)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARENT",
                    "Retailers must be onboarded under a distributor");
        }
        return createUser("RETAILER", 3, fullName, mobile, email, password, distributorId, adminId,
                city, state, pincode);
    }

    private Map<String, Object> createUser(String userType, int roleId, String fullName, String mobile,
                                           String email, String password, UUID parentId, UUID createdBy,
                                           String city, String state, String pincode) {
        long startMs = System.currentTimeMillis();
        log.info("ONBOARD started type={} mobile={}", userType, mask(mobile));
        try {
            UUID userId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO users (id, user_type, parent_id, created_by, full_name, mobile, email, password_hash, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, userId, userType, parentId, createdBy, fullName, mobile, email,
                    passwordEncoder.encode(password));
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);
            UUID walletId = walletService.createForUser(userId);
            if ("RETAILER".equals(userType)) {
                String addressJson = toAddressJson(city, state, pincode);
                jdbc.update("""
                        INSERT INTO agent_kyc_profiles (user_id, shop_address, kyc_status)
                        VALUES (?, ?::jsonb, 'NOT_STARTED')
                        """, userId, addressJson);
            }
            log.info("ONBOARD success userId={} type={} walletId={}", userId, userType, walletId);
            return Map.of("id", userId, "fullName", fullName, "mobile", mobile,
                    "userType", userType, "walletId", walletId);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MOBILE_ALREADY_REGISTERED",
                    "A user with this mobile or email already exists");
        } finally {
            log.info("ONBOARD completed type={} in {} ms", userType, System.currentTimeMillis() - startMs);
        }
    }

    public Map<String, Object> me(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.full_name, u.mobile, u.email, u.code, u.user_type::text AS user_type,
                       u.status::text AS status, k.shop_name, k.kyc_status::text AS kyc_status
                  FROM users u
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                 WHERE u.id = ?
                """, userId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Account not found");
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("fullName", row.get("full_name"));
        profile.put("mobile", row.get("mobile"));
        profile.put("email", row.get("email"));
        profile.put("code", row.get("code") == null ? "" : row.get("code"));
        profile.put("userType", row.get("user_type"));
        profile.put("status", row.get("status"));
        profile.put("shopName", row.get("shop_name"));
        profile.put("kycStatus", row.get("kyc_status"));
        profile.put("permissions", permissionCodes(userId));
        return profile;
    }

    public Map<String, Object> ownKyc(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.full_name, u.mobile, u.status::text AS user_status,
                       k.shop_name, k.gstin, k.kyc_status::text AS kyc_status, k.rejection_reason,
                       k.aadhaar_last4, k.aadhaar_verified_at, k.pan_verified_at,
                       k.liveness_score, k.created_at, k.updated_at
                  FROM users u
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                 WHERE u.id = ?
                """, userId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Account not found");
        }
        return new LinkedHashMap<>(rows.get(0));
    }

    /** Current RBAC codes for API authorization (always read from DB, not the JWT snapshot). */
    public List<String> permissionCodes(UUID userId) {
        return jdbc.queryForList("""
                SELECT p.code FROM permissions p
                  JOIN role_permissions rp ON rp.permission_id = p.id
                  JOIN user_roles ur ON ur.role_id = rp.role_id
                 WHERE ur.user_id = ?
                """, String.class, userId);
    }

    @Transactional
    public Map<String, Object> updateProfile(UUID userId, String fullName, String email, String shopName) {
        String name = fullName == null ? "" : fullName.trim();
        if (name.length() < 2) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NAME", "Enter your full name");
        }
        String mail = email == null || email.isBlank() ? null : email.trim().toLowerCase();
        if (mail != null && !mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL", "Enter a valid email");
        }
        try {
            int n = jdbc.update("UPDATE users SET full_name = ?, email = ?, updated_at = now() WHERE id = ?",
                    name, mail, userId);
            if (n == 0) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Account not found");
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_ALREADY_REGISTERED",
                    "This email is already used on another account");
        }
        if (shopName != null) {
            String shop = shopName.trim();
            jdbc.update("""
                    UPDATE agent_kyc_profiles SET shop_name = ?, updated_at = now()
                     WHERE user_id = ?
                    """, shop.isBlank() ? null : shop, userId);
        }
        log.info("PROFILE_UPDATED userId={}", userId);
        return me(userId);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        if (hash == null || !passwordEncoder.matches(currentPassword == null ? "" : currentPassword, hash)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BAD_PASSWORD", "Current password is incorrect");
        }
        String next = newPassword == null ? "" : newPassword;
        if (next.length() < 8) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD", "New password must be at least 8 characters");
        }
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?",
                passwordEncoder.encode(next), userId);
        log.info("PASSWORD_CHANGED userId={}", userId);
    }

    public boolean isActive(UUID userId) {
        try {
            String status = jdbc.queryForObject(
                    "SELECT status::text FROM users WHERE id = ?", String.class, userId);
            return "ACTIVE".equals(status);
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    public List<Map<String, Object>> listUsers() {
        return jdbc.queryForList("""
                SELECT u.id, u.user_type::text AS user_type, u.full_name, u.mobile, u.email, u.status::text AS status, u.created_at,
                       p.full_name AS parent_name, u.parent_id
                  FROM users u
                  LEFT JOIN users p ON p.id = u.parent_id
                 ORDER BY u.created_at DESC
                """);
    }

    private static String toAddressJson(String city, String state, String pincode) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "city", city.trim(),
                    "state", state.trim(),
                    "pincode", pincode.replaceAll("\\D", "")));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise retailer address", e);
        }
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 4 ? "****" : "******" + mobile.substring(mobile.length() - 4);
    }
}
