package com.fintech.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.ledger.WalletService;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PublicAppUrl;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PublicOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(PublicOnboardingService.class);
    private static final String CODE_PREFIX = "GFIN";
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final OtpService otpService;
    private final JourneyTracker journeyTracker;
    private final OnboardingIdentityProcessor processor;
    private final PasswordEncoder passwordEncoder;
    private final WalletService walletService;
    private final ObjectMapper mapper;
    private final String publicAppUrl;
    private final int sessionTtlMinutes;

    public PublicOnboardingService(
            JdbcTemplate jdbc,
            OtpService otpService,
            JourneyTracker journeyTracker,
            OnboardingIdentityProcessor processor,
            PasswordEncoder passwordEncoder,
            WalletService walletService,
            ObjectMapper mapper,
            @Value("${app.public-app-url:http://localhost:3001}") String publicAppUrl,
            @Value("${app.onboarding.session-ttl-minutes:60}") int sessionTtlMinutes) {
        this.jdbc = jdbc;
        this.otpService = otpService;
        this.journeyTracker = journeyTracker;
        this.processor = processor;
        this.passwordEncoder = passwordEncoder;
        this.walletService = walletService;
        this.mapper = mapper;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
        this.sessionTtlMinutes = sessionTtlMinutes;
    }

    @Transactional
    public Map<String, Object> openLink(String rawToken, String ip, String userAgent) {
        Map<String, Object> row = loadByToken(rawToken);
        String status = String.valueOf(row.get("status"));
        UUID inviteId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");

        if ("REVOKED".equals(status)) {
            throw ApiException.of(HttpStatus.GONE, "INVITE_REVOKED",
                    "This onboarding link has been revoked. Contact your distributor or administrator.");
        }
        Instant expiresAt = toInstant(row.get("expires_at"));
        if (!"COMPLETED".equals(status) && ("EXPIRED".equals(status) || (expiresAt != null && Instant.now().isAfter(expiresAt)))) {
            if (!"EXPIRED".equals(status)) {
                jdbc.update("UPDATE onboarding_invites SET status = 'EXPIRED', updated_at = now() WHERE id = ?", inviteId);
                if (journeyId != null) {
                    jdbc.update("UPDATE onboarding_journeys SET status = 'EXPIRED' WHERE id = ?", journeyId);
                }
            }
            throw ApiException.of(HttpStatus.GONE, "INVITE_EXPIRED",
                    "This onboarding link has expired. Ask your distributor or administrator to resend it.");
        }

        String currentStep = String.valueOf(row.get("current_step"));
        if (!"COMPLETED".equals(status) && JourneyTracker.LINK_SENT.equals(currentStep) && journeyId != null) {
            journeyTracker.record(
                    journeyId, null, JourneyTracker.LINK_OPENED, "SUCCESS",
                    Map.of("inviteId", inviteId.toString()),
                    null, null, ip, userAgent, true);
            currentStep = JourneyTracker.LINK_OPENED;
        }

        Map<String, Object> identity = journeyId == null ? Map.of() : sanitizeIdentity(journeyTracker.getIdentity(journeyId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inviteId", inviteId);
        out.put("role", row.get("role"));
        out.put("fullName", row.get("full_name"));
        out.put("maskedMobile", OtpService.maskMobile(String.valueOf(row.get("mobile"))));
        out.put("status", status);
        out.put("journeyStatus", row.get("journey_status"));
        out.put("currentStep", currentStep);
        out.put("identity", identity);
        return out;
    }

    public Map<String, Object> sendOtp(String rawToken, String ip, String userAgent) {
        Map<String, Object> row = requireActiveInviteByToken(rawToken);
        UUID inviteId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");
        String mobile = String.valueOf(row.get("mobile"));

        OtpService.IssuedChallenge ch = otpService.sendOtp(mobile, "ONBOARD", inviteId);
        if (journeyId != null) {
            journeyTracker.record(
                    journeyId, null, JourneyTracker.PHONE_VERIFIED, "INFO",
                    Map.of("action", "OTP_SENT", "maskedMobile", ch.maskedMobile()),
                    null, null, ip, userAgent, false);
        }
        return Map.of(
                "maskedMobile", ch.maskedMobile(),
                "expiresAt", ch.expiresAt().toString(),
                "cooldownSeconds", ch.cooldownSeconds());
    }

    @Transactional
    public Map<String, Object> verifyOtp(String rawToken, String otp, String ip, String userAgent) {
        Map<String, Object> row = requireActiveInviteByToken(rawToken);
        UUID inviteId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");
        String mobile = String.valueOf(row.get("mobile"));

        try {
            otpService.verifyOtp(mobile, "ONBOARD", inviteId, otp);
        } catch (ApiException e) {
            if (journeyId != null) {
                journeyTracker.record(
                        journeyId, null, JourneyTracker.PHONE_VERIFIED, "FAILURE",
                        Map.of("reason", e.getCode()),
                        null, null, ip, userAgent, false);
            }
            throw e;
        }

        String sessionToken = InviteService.newOpaqueToken();
        String sessionHash = InviteService.sha256(sessionToken);
        Instant sessionExpiresAt = Instant.now().plus(Duration.ofMinutes(sessionTtlMinutes));

        jdbc.update("""
                UPDATE onboarding_invites
                   SET session_hash = ?,
                       session_expires_at = ?,
                       status = CASE WHEN status = 'SENT' THEN 'IN_PROGRESS' ELSE status END,
                       updated_at = now()
                 WHERE id = ?
                """, sessionHash, Timestamp.from(sessionExpiresAt), inviteId);

        if (journeyId != null) {
            journeyTracker.mergeIdentity(journeyId, Map.of("phoneVerified", true));
            journeyTracker.record(
                    journeyId, null, JourneyTracker.PHONE_VERIFIED, "SUCCESS",
                    Map.of("maskedMobile", OtpService.maskMobile(mobile)),
                    null, null, ip, userAgent, true);
        }

        String currentStep = journeyId == null ? JourneyTracker.PHONE_VERIFIED
                : jdbc.queryForObject("SELECT current_step FROM onboarding_journeys WHERE id = ?", String.class, journeyId);
        Map<String, Object> identity = journeyId == null ? Map.of() : sanitizeIdentity(journeyTracker.getIdentity(journeyId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionToken", sessionToken);
        out.put("expiresAt", sessionExpiresAt.toString());
        out.put("currentStep", currentStep);
        out.put("identity", identity);
        return out;
    }

    @Transactional
    public Map<String, Object> captureLocation(String sessionToken, Double latitude, Double longitude,
                                               Double accuracy, String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID journeyId = (UUID) row.get("journey_id");
        return processor.captureLocation(journeyId, null, latitude, longitude, accuracy, ip, userAgent);
    }

    public Map<String, Object> startDigilocker(String sessionToken, String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID journeyId = (UUID) row.get("journey_id");
        String fullName = String.valueOf(row.get("full_name"));
        return processor.startDigilocker(journeyId, null, fullName, ip, userAgent);
    }

    public Map<String, Object> completeDigilocker(String sessionToken, String state,
                                                  String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID inviteId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");
        return processor.completeDigilocker(journeyId, inviteId, null, state, null, ip, userAgent);
    }

    public Map<String, Object> verifyPan(String sessionToken, String pan, String ip, String userAgent) {
        return verifyPan(sessionToken, pan, null, null, false, ip, userAgent);
    }

    public Map<String, Object> verifyPan(String sessionToken, String pan, MultipartFile panFile,
                                         String ip, String userAgent) {
        return verifyPan(sessionToken, pan, null, panFile, false, ip, userAgent);
    }

    public Map<String, Object> verifyPan(String sessionToken, String pan, String aadhaar,
                                         MultipartFile panFile, boolean requireFullAadhaar,
                                         String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID inviteId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");
        return processor.verifyPan(journeyId, inviteId, null, pan, aadhaar, panFile, requireFullAadhaar, ip, userAgent);
    }

    public Map<String, Object> verifySelfie(String sessionToken, MultipartFile file, String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID inviteId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");
        return processor.verifySelfie(journeyId, inviteId, null, file, ip, userAgent);
    }

    @Transactional
    public Map<String, Object> setCredentials(String sessionToken, String passwordRaw, String pinRaw,
                                              String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID inviteId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");
        Map<String, Object> identity = journeyTracker.getIdentity(journeyId);

        if (!Boolean.TRUE.equals(identity.get("selfieVerified"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "STEP_ORDER_VIOLATION",
                    "Complete live selfie verification before setting your credentials");
        }

        String validPassword = CredentialRules.validatePassword(passwordRaw);
        String validPin = CredentialRules.validatePin(pinRaw);

        String role = String.valueOf(row.get("role"));
        String mobile = String.valueOf(row.get("mobile"));
        String email = (String) row.get("email");
        UUID parentId = (UUID) row.get("parent_id");
        UUID hubId = (UUID) row.get("hub_id");
        UUID createdBy = (UUID) row.get("created_by");

        String verifiedName = String.valueOf(identity.getOrDefault("aadhaarName", row.get("full_name")));
        UUID userId = UUID.randomUUID();
        String gfinCode = generateGfinCode();
        String pwdHash = passwordEncoder.encode(validPassword);
        String pinHash = passwordEncoder.encode(validPin);

        try {
            jdbc.update("""
                    INSERT INTO users
                        (id, user_type, parent_id, hub_id, created_by, full_name, mobile, email,
                         password_hash, pin_hash, code, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """,
                    userId, role, parentId, hubId, createdBy, verifiedName, mobile, email,
                    pwdHash, pinHash, gfinCode);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MOBILE_ALREADY_REGISTERED",
                    "An account with this mobile or email already exists");
        }

        walletService.createForUser(userId);
        walletService.ensureRewards(userId);

        String city = String.valueOf(identity.getOrDefault("city", ""));
        String state = String.valueOf(identity.getOrDefault("state", ""));
        String pincode = String.valueOf(identity.getOrDefault("pincode", ""));
        String addressLine = String.valueOf(identity.getOrDefault("address", ""));
        Object permanentAddress = identity.get("permanentAddress");

        boolean aadhaarPanLinked = Boolean.TRUE.equals(identity.get("aadhaarPanLinked"))
                || (identity.get("aadhaarPanLinked") == null && Boolean.TRUE.equals(identity.get("aadhaarLinked")));

        Map<String, Object> identityDetails = new LinkedHashMap<>();
        identityDetails.put("fullName", verifiedName);
        identityDetails.put("dob", identity.getOrDefault("dob", ""));
        identityDetails.put("gender", identity.getOrDefault("gender", ""));
        identityDetails.put("permanentAddress", permanentAddress);
        identityDetails.put("address", addressLine);
        identityDetails.put("city", city);
        identityDetails.put("state", state);
        identityDetails.put("pincode", pincode);
        identityDetails.put("maskedAadhaar", identity.getOrDefault("maskedAadhaar", ""));
        identityDetails.put("aadhaarLast4", identity.getOrDefault("aadhaarLast4", ""));
        identityDetails.put("digilockerRef", identity.getOrDefault("digilockerRef", ""));
        identityDetails.put("aadhaarLinked", identity.getOrDefault("aadhaarLinked", aadhaarPanLinked));
        identityDetails.put("aadhaarPanLinked", aadhaarPanLinked);
        identityDetails.put("panLast4", identity.getOrDefault("panLast4", ""));
        identityDetails.put("panName", identity.getOrDefault("panName", ""));
        identityDetails.put("panNameMatchScore", identity.getOrDefault("panNameMatchScore", 100));
        identityDetails.put("panSource", identity.getOrDefault("panSource", "DIGILOCKER"));
        identityDetails.put("panMaskedAadhaar", identity.getOrDefault("panMaskedAadhaar", ""));
        identityDetails.put("panAadhaarLast4", identity.getOrDefault("panAadhaarLast4", ""));
        identityDetails.put("panAadhaarLast4Match", identity.getOrDefault("panAadhaarLast4Match", aadhaarPanLinked));
        identityDetails.put("faceReviewStatus", "PENDING");
        String identityDetailsJson = toJson(identityDetails);

        String aadhaarLast4 = clipChar4(String.valueOf(identity.getOrDefault("aadhaarLast4", "")));
        String aadhaarEncrypted = identity.get("aadhaarEncrypted") instanceof String s && !s.isBlank() ? s.trim() : null;
        String panLast4 = clipChar4(String.valueOf(identity.getOrDefault("panLast4", "")));
        String panEncrypted = (String) identity.get("panEncrypted");
        String selfieKey = (String) identity.get("selfieKey");
        String digilockerRef = (String) identity.get("digilockerRef");
        BigDecimal lat = toBigDecimal(identity.get("latitude"));
        BigDecimal lng = toBigDecimal(identity.get("longitude"));

        jdbc.update("""
                INSERT INTO agent_kyc_profiles
                    (user_id, aadhaar_ref_key, aadhaar_enc, aadhaar_last4, aadhaar_verified_at,
                     pan_enc, pan_last4, pan_verified_at,
                     aadhaar_pan_linked,
                     liveness_score, liveness_passed_at, selfie_key,
                     face_review_status,
                     shop_address, kyc_status,
                     personal_identity_verified, business_verified,
                     personal_status, personal_details,
                     identity_status, identity_verified_at, identity_details)
                VALUES (?, ?, ?, ?, now(), ?, ?, now(), ?, NULL, NULL, ?,
                        'PENDING', NULL, 'COMPLETED',
                        FALSE, FALSE,
                        'NOT_VERIFIED', ?::jsonb, 'VERIFIED', now(), ?::jsonb)
                """,
                userId, digilockerRef, aadhaarEncrypted, aadhaarLast4,
                panEncrypted, panLast4,
                aadhaarPanLinked,
                selfieKey,
                identityDetailsJson, identityDetailsJson);

        processor.bindLatestDigilockerDocumentsToUser(journeyId, userId, digilockerRef);

        if (lat != null && lng != null) {
            jdbc.update("""
                    INSERT INTO partner_verification_events (user_id, step, latitude, longitude, provider_ref, payload)
                    VALUES (?, 'PERSONAL', ?, ?, ?, ?::jsonb)
                    """, userId, lat, lng, digilockerRef, identityDetailsJson);
        }

        jdbc.update("""
                UPDATE onboarding_invites
                   SET status = 'COMPLETED', user_id = ?, session_hash = NULL, session_expires_at = NULL, updated_at = now()
                 WHERE id = ?
                """, userId, inviteId);

        jdbc.update("""
                UPDATE onboarding_journeys
                   SET status = 'COMPLETED', user_id = ?, current_step = ?, completed_at = now(), last_activity_at = now()
                 WHERE id = ?
                """, userId, JourneyTracker.CREDENTIALS_SET, journeyId);

        journeyTracker.record(
                journeyId, userId, JourneyTracker.CREDENTIALS_SET, "SUCCESS",
                Map.of("userId", userId.toString(), "code", gfinCode, "role", role),
                lat, lng, ip, userAgent, true);

        log.info("SELF_ONBOARD_COMPLETED inviteId={} userId={} role={} code={}", inviteId, userId, role, gfinCode);
        return Map.of(
                "completed", true,
                "userId", userId,
                "code", gfinCode,
                "role", role,
                "mobile", mobile);
    }

    private Map<String, Object> loadByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "Invalid onboarding link");
        }
        String tokenHash = InviteService.sha256(rawToken.trim());
        try {
            return jdbc.queryForMap("""
                    SELECT i.id, i.purpose, i.role, i.full_name, i.mobile, i.email,
                           i.parent_id, i.hub_id, i.user_id, i.status, i.expires_at, i.created_by,
                           j.id AS journey_id, j.current_step, j.status AS journey_status
                      FROM onboarding_invites i
                      LEFT JOIN onboarding_journeys j ON j.invite_id = i.id
                     WHERE i.token_hash = ?
                    """, tokenHash);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "Onboarding link is invalid or has been replaced");
        }
    }

    private Map<String, Object> requireActiveInviteByToken(String rawToken) {
        Map<String, Object> row = loadByToken(rawToken);
        String status = String.valueOf(row.get("status"));
        if ("COMPLETED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVITE_COMPLETED",
                    "This onboarding link has already been completed. Sign in with your mobile, password, and PIN.");
        }
        if ("REVOKED".equals(status)) {
            throw ApiException.of(HttpStatus.GONE, "INVITE_REVOKED", "This onboarding link has been revoked.");
        }
        Instant expiresAt = toInstant(row.get("expires_at"));
        if ("EXPIRED".equals(status) || (expiresAt != null && Instant.now().isAfter(expiresAt))) {
            throw ApiException.of(HttpStatus.GONE, "INVITE_EXPIRED", "This onboarding link has expired.");
        }
        return row;
    }

    private Map<String, Object> requireSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "ONBOARDING_SESSION_REQUIRED",
                    "Verify your mobile number with OTP to continue");
        }
        String hash = InviteService.sha256(sessionToken.trim());
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT i.id, i.purpose, i.role, i.full_name, i.mobile, i.email,
                           i.parent_id, i.hub_id, i.user_id, i.status, i.expires_at,
                           i.session_expires_at, i.created_by,
                           j.id AS journey_id, j.current_step, j.status AS journey_status
                      FROM onboarding_invites i
                      JOIN onboarding_journeys j ON j.invite_id = i.id
                     WHERE i.session_hash = ?
                    """, hash);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "ONBOARDING_SESSION_EXPIRED",
                    "Your onboarding session has expired. Verify OTP again to resume.");
        }
        String status = String.valueOf(row.get("status"));
        if ("COMPLETED".equals(status) || "REVOKED".equals(status) || "EXPIRED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVITE_CLOSED",
                    "This onboarding invite is no longer active");
        }
        Instant sessionExpires = toInstant(row.get("session_expires_at"));
        if (sessionExpires == null || Instant.now().isAfter(sessionExpires)) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "ONBOARDING_SESSION_EXPIRED",
                    "Your onboarding session has timed out. Verify OTP again to continue from where you left off.");
        }
        // Sliding window extension
        Instant nextExpiry = Instant.now().plus(Duration.ofMinutes(sessionTtlMinutes));
        jdbc.update("UPDATE onboarding_invites SET session_expires_at = ?, updated_at = now() WHERE id = ?",
                Timestamp.from(nextExpiry), row.get("id"));
        return row;
    }

    public static Map<String, Object> sanitizeIdentity(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(raw);
        out.remove("panEncrypted");
        out.remove("aadhaarEncrypted");
        out.remove("digilockerState");
        out.remove("digilockerClientId");
        out.remove("digilockerExpiresAt");
        out.remove("digilockerCompletingAt");
        return out;
    }

    private String generateGfinCode() {
        for (int attempt = 0; attempt < 32; attempt++) {
            StringBuilder suffix = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                suffix.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String code = CODE_PREFIX + suffix;
            Integer taken = jdbc.queryForObject("SELECT COUNT(*)::int FROM users WHERE code = ?", Integer.class, code);
            if (taken == null || taken == 0) {
                return code;
            }
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CODE_EXHAUSTED", "Could not generate user code");
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    private static String clipChar4(String s) {
        if (s == null || s.length() < 4) {
            return null;
        }
        return s.substring(s.length() - 4);
    }

    private static BigDecimal toBigDecimal(Object val) {
        if (val instanceof BigDecimal bd) {
            return bd;
        }
        if (val instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (val != null) {
            try {
                return new BigDecimal(String.valueOf(val));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Instant toInstant(Object val) {
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
}

