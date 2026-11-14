package com.fintech.identity;

import com.fintech.ledger.WalletService;
import com.fintech.onboarding.CredentialRules;
import com.fintech.onboarding.JourneyTracker;
import com.fintech.onboarding.OtpService;
import com.fintech.persistence.identity.UserRepository;
import com.fintech.platform.security.JwtService;
import com.fintech.platform.security.SessionService;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PageWindow;
import io.jsonwebtoken.Claims;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    public static final int MAX_LOGIN_ATTEMPTS = 5;
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(30);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final WalletService walletService;
    private final SessionService sessions;
    private final UserRepository users;
    private final OtpService otpService;
    private final JourneyTracker journeyTracker;

    public UserService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                       JwtService jwtService, WalletService walletService, SessionService sessions,
                       UserRepository users, OtpService otpService, JourneyTracker journeyTracker) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.walletService = walletService;
        this.sessions = sessions;
        this.users = users;
        this.otpService = otpService;
        this.journeyTracker = journeyTracker;
    }

    /** Access token plus the refresh cookie the controller has to attach to the response. */
    public record LoginResult(Map<String, Object> body, String refreshCookie, Instant refreshExpiresAt) {}

    public LoginResult login(String identifier, String password, String ip, String userAgent) {
        return login(identifier, password, null, null, ip, userAgent);
    }

    public LoginResult login(String identifier, String password, String pin, String ip, String userAgent) {
        return login(identifier, password, pin, null, ip, userAgent);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String identifier, String password, String pin, String newPin, String ip, String userAgent) {
        long startMs = System.currentTimeMillis();
        String key = identifier == null ? "" : identifier.trim();
        boolean byCode = key.toUpperCase(Locale.ROOT).startsWith("GFIN");
        log.info("LOGIN started byCode={}", byCode);
        try {
            Map<String, Object> user;
            try {
                String lookup = byCode ? "upper(u.code) = ?" : "u.mobile = ?";
                user = jdbc.queryForMap("""
                        SELECT u.id, u.user_type::text AS user_type, u.full_name, u.code, u.mobile,
                               u.password_hash, u.pin_hash, u.failed_login_attempts, u.locked_until,
                               u.status::text AS status,
                               COALESCE(k.identity_status, 'NOT_VERIFIED') AS identity_status
                          FROM users u
                          LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                         """ + " WHERE " + lookup,
                        byCode ? key.toUpperCase(Locale.ROOT) : key);
            } catch (EmptyResultDataAccessException e) {
                throw ApiException.of(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid credentials");
            }

            UUID userId = (UUID) user.get("id");
            Instant lockedUntil = toInstant(user.get("locked_until"));
            if (lockedUntil != null && Instant.now().isBefore(lockedUntil)) {
                throw ApiException.of(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                        "Account is locked after multiple failed attempts. Try again in 30 minutes or reset your password/PIN.");
            }

            int failedAttempts = user.get("failed_login_attempts") instanceof Number n ? n.intValue() : 0;
            String passwordHash = (String) user.get("password_hash");
            if (passwordHash == null || !passwordEncoder.matches(password == null ? "" : password, passwordHash)) {
                recordLoginFailure(userId, failedAttempts);
                throw ApiException.of(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid credentials");
            }

            String userType = String.valueOf(user.get("user_type"));
            String status = String.valueOf(user.get("status"));
            if (!canAuthenticate(userType, status)) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE", "Account is not active");
            }

            String pinHash = (String) user.get("pin_hash");
            boolean hasPin = pinHash != null && !pinHash.isBlank();
            if (hasPin) {
                if (pin == null || pin.isBlank()) {
                    Map<String, Object> stepBody = new LinkedHashMap<>();
                    stepBody.put("step", "VERIFY_PIN");
                    stepBody.put("hasPin", true);
                    stepBody.put("role", userType);
                    stepBody.put("fullName", user.get("full_name"));
                    stepBody.put("maskedMobile", OtpService.maskMobile(String.valueOf(user.get("mobile"))));
                    return new LoginResult(stepBody, null, null);
                }
                if (!passwordEncoder.matches(pin.trim(), pinHash)) {
                    recordLoginFailure(userId, failedAttempts);
                    throw ApiException.of(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid 6-digit MPIN");
                }
            } else {
                String candidatePin = (newPin != null && !newPin.isBlank()) ? newPin : pin;
                if (candidatePin == null || candidatePin.isBlank()) {
                    Map<String, Object> stepBody = new LinkedHashMap<>();
                    stepBody.put("step", "SET_PIN");
                    stepBody.put("hasPin", false);
                    stepBody.put("role", userType);
                    stepBody.put("fullName", user.get("full_name"));
                    stepBody.put("maskedMobile", OtpService.maskMobile(String.valueOf(user.get("mobile"))));
                    return new LoginResult(stepBody, null, null);
                }
                String validPin = CredentialRules.validatePin(candidatePin);
                pinHash = passwordEncoder.encode(validPin);
                jdbc.update("UPDATE users SET pin_hash = ?, updated_at = now() WHERE id = ?", pinHash, userId);
            }

            if (failedAttempts > 0 || lockedUntil != null) {
                jdbc.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?", userId);
            }

            return completeLoginSession(userId, (String) user.get("full_name"), userType, status,
                    String.valueOf(user.get("identity_status")), ip, userAgent);
        } catch (ApiException e) {
            log.warn("LOGIN failed byCode={} reason={}", byCode, e.getCode());
            throw e;
        } finally {
            log.info("LOGIN completed in {} ms", System.currentTimeMillis() - startMs);
        }
    }

    public Map<String, Object> requestLoginOtp(String identifier) {
        Map<String, Object> user = findByIdentifierForLogin(identifier);
        UUID userId = (UUID) user.get("id");
        String userType = String.valueOf(user.get("user_type"));
        String status = String.valueOf(user.get("status"));
        if (!canAuthenticate(userType, status)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE", "Account is not active");
        }
        String mobile = String.valueOf(user.get("mobile"));
        OtpService.IssuedChallenge ch = otpService.sendOtp(mobile, "RESET", userId, false);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("sent", true);
        res.put("fullName", user.get("full_name"));
        res.put("maskedMobile", ch.maskedMobile());
        res.put("role", userType);
        res.put("expiresAt", ch.expiresAt().toString());
        res.put("ttlSeconds", (int) OtpService.TTL.toSeconds());
        return res;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult verifyLoginOtp(String identifier, String otp, String ip, String userAgent) {
        Map<String, Object> user = findByIdentifierForLogin(identifier);
        UUID userId = (UUID) user.get("id");
        String userType = String.valueOf(user.get("user_type"));
        String status = String.valueOf(user.get("status"));
        if (!canAuthenticate(userType, status)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE", "Account is not active");
        }
        String mobile = String.valueOf(user.get("mobile"));
        otpService.verifyOtp(mobile, "RESET", userId, otp);

        jdbc.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?", userId);

        return completeLoginSession(userId, (String) user.get("full_name"), userType, status,
                String.valueOf(user.get("identity_status")), ip, userAgent);
    }

    private LoginResult completeLoginSession(UUID userId, String fullName, String userType, String status,
                                             String identityStatus, String ip, String userAgent) {
        boolean needsReverify = isPartner(userType) && !"VERIFIED".equals(identityStatus);
        String scope = needsReverify ? JwtService.SCOPE_REVERIFY : JwtService.SCOPE_FULL;
        String nextStep = computeNextStep(userType, status, needsReverify);

        // Displaces any other live session for this user before the new token is minted.
        SessionService.Issued session = sessions.start(userId, ip, userAgent);
        List<String> permissions = permissionCodes(userType);
        JwtService.IssuedToken issued =
                jwtService.issue(userId, session.sessionId(), fullName, userType, permissions, scope);

        if (isPartner(userType)) {
            journeyTracker.recordUserMilestone(
                    userId, JourneyTracker.FIRST_LOGIN, "SUCCESS", Map.of("nextStep", nextStep));
        }

        log.info("LOGIN success userId={} type={} scope={} nextStep={} session={}",
                userId, userType, scope, nextStep, session.sessionId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", issued.token());
        body.put("role", userType);
        body.put("expiresAt", issued.expiresAt().toString());
        body.put("scope", scope);
        body.put("nextStep", nextStep);
        return new LoginResult(body, session.cookieValue(), session.expiresAt());
    }

    private Map<String, Object> findByIdentifierForLogin(String identifier) {
        String key = identifier == null ? "" : identifier.trim();
        if (key.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTIFIER_REQUIRED",
                    "Enter your mobile number or user code");
        }
        boolean byCode = key.toUpperCase(Locale.ROOT).startsWith("GFIN");
        try {
            String lookup = byCode ? "upper(u.code) = ?" : "u.mobile = ?";
            return jdbc.queryForMap("""
                    SELECT u.id, u.user_type::text AS user_type, u.full_name, u.code, u.mobile,
                           u.password_hash, u.pin_hash, u.failed_login_attempts, u.locked_until,
                           u.status::text AS status,
                           COALESCE(k.identity_status, 'NOT_VERIFIED') AS identity_status
                      FROM users u
                      LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                     """ + " WHERE " + lookup,
                    byCode ? key.toUpperCase(Locale.ROOT) : key.replaceAll("\\D", ""));
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                    "No account found with this mobile number or user code");
        }
    }

    private void recordLoginFailure(UUID userId, int currentFailures) {
        int next = currentFailures + 1;
        if (next >= MAX_LOGIN_ATTEMPTS) {
            Instant lockUntil = Instant.now().plus(LOCKOUT_DURATION);
            jdbc.update("""
                    UPDATE users
                       SET failed_login_attempts = ?, locked_until = ?, updated_at = now()
                     WHERE id = ?
                    """, next, Timestamp.from(lockUntil), userId);
        } else {
            jdbc.update("""
                    UPDATE users
                       SET failed_login_attempts = ?, updated_at = now()
                     WHERE id = ?
                    """, next, userId);
        }
    }

    /**
     * Mints a fresh access token for an already-rotated session. Re-reads account state and
     * permissions so a suspension, role change, or forced re-verification takes effect at the next refresh.
     */
    public Map<String, Object> issueForSession(UUID userId, UUID sessionId) {
        Map<String, Object> user;
        try {
            user = jdbc.queryForMap("""
                    SELECT u.full_name, u.user_type::text AS user_type, u.status::text AS status,
                           u.pin_hash, COALESCE(k.identity_status, 'NOT_VERIFIED') AS identity_status
                      FROM users u
                      LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                     WHERE u.id = ?
                    """, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Your session has ended. Please sign in again.");
        }
        String userType = String.valueOf(user.get("user_type"));
        String status = String.valueOf(user.get("status"));
        if (!canAuthenticate(userType, status)) {
            sessions.revoke(sessionId, "STATUS_CHANGE");
            throw ApiException.of(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE", "Account is not active");
        }
        String identityStatus = String.valueOf(user.get("identity_status"));
        boolean needsReverify = isPartner(userType) && !"VERIFIED".equals(identityStatus);
        String scope = needsReverify ? JwtService.SCOPE_REVERIFY : JwtService.SCOPE_FULL;
        String nextStep = computeNextStep(userType, status, needsReverify);
        JwtService.IssuedToken issued =
                jwtService.issue(userId, sessionId, (String) user.get("full_name"), userType, permissionCodes(userType), scope);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", issued.token());
        body.put("role", userType);
        body.put("expiresAt", issued.expiresAt().toString());
        body.put("scope", scope);
        body.put("nextStep", nextStep);
        return body;
    }

    public Map<String, Object> requestResetOtp(String identifier) {
        Map<String, Object> user = findByIdentifier(identifier);
        UUID userId = (UUID) user.get("id");
        String mobile = String.valueOf(user.get("mobile"));
        OtpService.IssuedChallenge ch = otpService.sendOtp(mobile, "RESET", userId);
        return Map.of(
                "maskedMobile", ch.maskedMobile(),
                "expiresAt", ch.expiresAt().toString(),
                "cooldownSeconds", ch.cooldownSeconds());
    }

    public Map<String, Object> verifyResetOtp(String identifier, String otp) {
        Map<String, Object> user = findByIdentifier(identifier);
        UUID userId = (UUID) user.get("id");
        String mobile = String.valueOf(user.get("mobile"));
        UUID challengeId = otpService.verifyOtp(mobile, "RESET", userId, otp);
        String resetToken = jwtService.issueResetToken(userId, challengeId);
        return Map.of(
                "resetToken", resetToken,
                "userType", String.valueOf(user.get("user_type")));
    }

    @Transactional
    public Map<String, Object> confirmReset(String resetToken, String newPassword, String newPin) {
        if (resetToken == null || resetToken.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESET_TOKEN", "Reset session is missing or expired");
        }
        Claims claims;
        try {
            claims = jwtService.parse(resetToken.trim());
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESET_TOKEN", "Reset session has expired. Request a new OTP.");
        }
        if (!"CREDENTIAL_RESET".equals(claims.get("purpose", String.class))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESET_TOKEN", "Invalid reset token");
        }
        UUID userId = UUID.fromString(claims.getSubject());
        boolean hasPassword = newPassword != null && !newPassword.isBlank();
        boolean hasPin = newPin != null && !newPin.isBlank();
        if (!hasPassword && !hasPin) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CREDENTIALS_REQUIRED",
                    "Provide a new password and/or a new 6-digit PIN");
        }
        if (hasPassword) {
            String validPwd = CredentialRules.validatePassword(newPassword);
            jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?",
                    passwordEncoder.encode(validPwd), userId);
        }
        if (hasPin) {
            String validPin = CredentialRules.validatePin(newPin);
            jdbc.update("UPDATE users SET pin_hash = ?, updated_at = now() WHERE id = ?",
                    passwordEncoder.encode(validPin), userId);
        }
        jdbc.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL, updated_at = now() WHERE id = ?", userId);
        sessions.revokeAllForUser(userId, "CREDENTIAL_RESET");
        log.info("CREDENTIAL_RESET userId={} pwd={} pin={}", userId, hasPassword, hasPin);
        return Map.of("reset", true);
    }

    private Map<String, Object> findByIdentifier(String identifier) {
        String key = identifier == null ? "" : identifier.trim();
        if (key.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTIFIER_REQUIRED", "Enter your mobile number or GFIN code");
        }
        boolean byCode = key.toUpperCase(Locale.ROOT).startsWith("GFIN");
        try {
            String lookup = byCode ? "upper(code) = ?" : "mobile = ?";
            return jdbc.queryForMap(
                    "SELECT id, mobile, user_type::text AS user_type, status::text AS status FROM users WHERE " + lookup,
                    byCode ? key.toUpperCase(Locale.ROOT) : key.replaceAll("\\D", ""));
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No account found with this mobile or code");
        }
    }

    @Deprecated
    @Transactional
    public Map<String, Object> createDistributor(String fullName, String mobile, String email,
                                                 String password, UUID adminId) {
        return createUser("MASTER_DISTRIBUTOR", fullName, mobile, email, password, adminId, adminId,
                null, null, null);
    }

    @Deprecated
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
        return createUser("RETAILER", fullName, mobile, email, password, distributorId, adminId,
                city, state, pincode);
    }

    private Map<String, Object> createUser(String userType, String fullName, String mobile,
                                           String email, String password, UUID parentId, UUID createdBy,
                                           String city, String state, String pincode) {
        long startMs = System.currentTimeMillis();
        log.info("ONBOARD started type={} mobile={}", userType, mask(mobile));
        try {
            UUID userId = UUID.randomUUID();
            String status = "RETAILER".equals(userType) ? "PENDING" : "ACTIVE";
            String mail = email == null || email.isBlank() ? null : email.trim().toLowerCase();
            if ("RETAILER".equals(userType) && (mail == null || !mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_REQUIRED",
                        "A valid email is required to onboard a retailer");
            }
            jdbc.update("""
                    INSERT INTO users (id, user_type, parent_id, created_by, full_name, mobile, email, password_hash, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, userId, userType, parentId, createdBy, fullName, mobile, mail,
                    passwordEncoder.encode(password), status);
            UUID walletId = walletService.createForUser(userId);
            if ("RETAILER".equals(userType) || "MASTER_DISTRIBUTOR".equals(userType)) {
                walletService.ensureRewards(userId);
            }
            if ("RETAILER".equals(userType)) {
                String addressJson = toAddressJson(city, state, pincode);
                jdbc.update("""
                        INSERT INTO agent_kyc_profiles (user_id, shop_address, kyc_status)
                        VALUES (?, ?::jsonb, 'PENDING')
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
                SELECT u.id, u.full_name, u.mobile, u.email, u.code, u.user_type::text AS user_type,
                       u.status::text AS status, (u.pin_hash IS NOT NULL) AS has_pin,
                       k.shop_name, k.kyc_status::text AS kyc_status,
                       COALESCE(k.identity_status, 'NOT_VERIFIED') AS identity_status,
                       COALESCE(k.bank_verification_status, 'NOT_VERIFIED') AS bank_verification_status,
                       COALESCE(k.business_status, 'NOT_VERIFIED') AS business_status,
                       COALESCE(k.agreement_status, 'NOT_SIGNED') AS agreement_status
                  FROM users u
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                 WHERE u.id = ?
                """, userId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Account not found");
        }
        Map<String, Object> row = rows.get(0);
        String userType = String.valueOf(row.get("user_type"));
        String status = String.valueOf(row.get("status"));
        boolean hasPin = Boolean.TRUE.equals(row.get("has_pin"));
        String identityStatus = String.valueOf(row.get("identity_status"));
        boolean needsReverify = isPartner(userType) && !"VERIFIED".equals(identityStatus);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", row.get("id"));
        profile.put("fullName", row.get("full_name"));
        profile.put("mobile", row.get("mobile"));
        profile.put("email", row.get("email"));
        profile.put("code", row.get("code") == null ? "" : row.get("code"));
        profile.put("userType", userType);
        profile.put("status", status);
        profile.put("shopName", row.get("shop_name"));
        profile.put("kycStatus", row.get("kyc_status"));
        profile.put("identityStatus", identityStatus);
        profile.put("bankVerificationStatus", row.get("bank_verification_status"));
        profile.put("businessStatus", row.get("business_status"));
        profile.put("agreementStatus", row.get("agreement_status"));
        profile.put("hasPin", hasPin);
        profile.put("nextStep", computeNextStep(userType, status, needsReverify));
        profile.put("permissions", permissionCodes(userType));
        return profile;
    }

    public Map<String, Object> ownKyc(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.full_name, u.mobile, u.status::text AS user_status,
                       k.shop_name, k.gstin, k.kyc_status::text AS kyc_status, k.rejection_reason,
                       k.identity_status, k.bank_verification_status, k.business_status,
                       k.aadhaar_last4, k.aadhaar_verified_at, k.pan_last4, k.pan_verified_at,
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

    /**
     * Everything {@link com.fintech.platform.security.JwtAuthFilter} needs in a single round trip:
     * account state, whether the bearer token's session is still live, and current RBAC codes.
     */
    public AuthContext authContext(UUID userId, UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.user_type::text AS user_type, u.status::text AS status,
                       (u.pin_hash IS NOT NULL) AS has_pin,
                       COALESCE(k.identity_status, 'NOT_VERIFIED') AS identity_status,
                       s.id IS NOT NULL AS session_active
                  FROM users u
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                  LEFT JOIN user_sessions s
                         ON s.id = ? AND s.user_id = u.id AND s.revoked_at IS NULL
                 WHERE u.id = ?
                """, sessionId, userId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> first = rows.get(0);
        String userType = String.valueOf(first.get("user_type"));
        String identityStatus = String.valueOf(first.get("identity_status"));
        boolean reverifyRequired = isPartner(userType) && !"VERIFIED".equals(identityStatus);
        return new AuthContext(
                userType,
                String.valueOf(first.get("status")),
                Boolean.TRUE.equals(first.get("session_active")),
                reverifyRequired,
                AccessCatalog.forUserType(userType));
    }

    public record AuthContext(String userType, String status, boolean sessionActive,
                              boolean reverifyRequired, List<String> permissions) {

        public AuthContext(String userType, String status, boolean sessionActive, List<String> permissions) {
            this(userType, status, sessionActive, false, permissions);
        }

        public boolean canAuthenticate() {
            return UserService.canAuthenticate(userType, status);
        }

        public boolean isPendingActivation() {
            return UserService.isPartner(userType) && UserService.awaitingActivation(status);
        }
    }

    /** Authorities for this account type. */
    public List<String> permissionCodes(String userType) {
        return AccessCatalog.forUserType(userType);
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
            int n = users.updateProfile(userId, name, mail);
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
        String next = CredentialRules.validatePassword(newPassword);
        users.updatePassword(userId, passwordEncoder.encode(next));
        sessions.revokeAllForUser(userId, "PASSWORD_CHANGE");
        log.info("PASSWORD_CHANGED userId={}", userId);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void changePin(UUID userId, String currentPin, String currentPassword, String newPin) {
        Map<String, Object> user;
        try {
            user = jdbc.queryForMap("""
                    SELECT password_hash, pin_hash, failed_login_attempts, locked_until
                      FROM users
                     WHERE id = ?
                    """, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Account not found");
        }

        Instant lockedUntil = toInstant(user.get("locked_until"));
        if (lockedUntil != null && Instant.now().isBefore(lockedUntil)) {
            throw ApiException.of(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "Account is temporarily locked after multiple failed attempts. Try again in 30 minutes.");
        }

        int failedAttempts = user.get("failed_login_attempts") instanceof Number n ? n.intValue() : 0;
        String passwordHash = (String) user.get("password_hash");
        String pinHash = (String) user.get("pin_hash");
        boolean hasExistingPin = pinHash != null && !pinHash.isBlank();
        boolean providedCurrentPin = currentPin != null && !currentPin.isBlank();
        boolean providedCurrentPassword = currentPassword != null && !currentPassword.isBlank();

        if (providedCurrentPin) {
            if (!hasExistingPin) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PIN_NOT_SET",
                        "No existing PIN on this account — verify with your current password instead");
            }
            if (!passwordEncoder.matches(currentPin.trim(), pinHash)) {
                recordLoginFailure(userId, failedAttempts);
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BAD_PIN", "Current 6-digit PIN is incorrect");
            }
        } else if (providedCurrentPassword) {
            if (passwordHash == null || !passwordEncoder.matches(currentPassword, passwordHash)) {
                recordLoginFailure(userId, failedAttempts);
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BAD_PASSWORD", "Current password is incorrect");
            }
        } else {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "VERIFICATION_REQUIRED",
                    hasExistingPin
                            ? "Enter your current 6-digit PIN or current account password"
                            : "Enter your current account password to set a 6-digit PIN");
        }

        String validPin = CredentialRules.validatePin(newPin);
        if (hasExistingPin && passwordEncoder.matches(validPin, pinHash)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SAME_PIN",
                    "New PIN must be different from your current PIN");
        }

        jdbc.update("""
                UPDATE users
                   SET pin_hash = ?, failed_login_attempts = 0, locked_until = NULL, updated_at = now()
                 WHERE id = ?
                """, passwordEncoder.encode(validPin), userId);
        log.info("PIN_CHANGED userId={}", userId);
    }

    public static boolean isPartner(String userType) {
        return "RETAILER".equals(userType) || "MASTER_DISTRIBUTOR".equals(userType);
    }

    public static boolean canAuthenticate(String userType, String status) {
        if ("ACTIVE".equals(status)) {
            return true;
        }
        return isPartner(userType) && awaitingActivation(status);
    }

    /** Partner can sign in, but only to submit or track activation until the account is live. */
    public static boolean awaitingActivation(String status) {
        return "PENDING".equals(status)
                || "SUBMITTED".equals(status)
                || "APPROVED".equals(status)
                || "REJECTED".equals(status)
                || "PENDING_ACTIVATION".equals(status)
                || "PENDING_KYC".equals(status);
    }

    private static String computeNextStep(String userType, String status, boolean needsReverify) {
        if (needsReverify) {
            return "REVERIFY";
        }
        if (isPartner(userType) && !"ACTIVE".equals(status)) {
            return "ACTIVATION";
        }
        return "DASHBOARD";
    }

    public List<Map<String, Object>> listUsers() {
        return listUsers(null, null);
    }

    public List<Map<String, Object>> listUsers(Integer limit, Integer offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.user_type::text AS user_type, u.full_name, u.mobile, u.email, u.status::text AS status, u.created_at,
                       p.full_name AS parent_name, u.parent_id
                  FROM users u
                  LEFT JOIN users p ON p.id = u.parent_id
                 ORDER BY u.created_at DESC
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ?");
            args.add(limit);
        }
        if (offset != null && offset > 0) {
            sql.append(" OFFSET ?");
            args.add(offset);
        }
        return jdbc.queryForList(sql.toString(), args.toArray());
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

    private static Instant toInstant(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (val instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (val instanceof Instant inst) {
            return inst;
        }
        return null;
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 4 ? "****" : "******" + mobile.substring(mobile.length() - 4);
    }
}
