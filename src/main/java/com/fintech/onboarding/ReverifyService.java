package com.fintech.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.security.SessionService;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PublicAppUrl;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ReverifyService {

    private static final Logger log = LoggerFactory.getLogger(ReverifyService.class);

    private final JdbcTemplate jdbc;
    private final OtpService otpService;
    private final JourneyTracker journeyTracker;
    private final OnboardingIdentityProcessor processor;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final String publicAppUrl;

    public ReverifyService(
            JdbcTemplate jdbc,
            OtpService otpService,
            JourneyTracker journeyTracker,
            OnboardingIdentityProcessor processor,
            PasswordEncoder passwordEncoder,
            SessionService sessions,
            ObjectMapper mapper,
            @Value("${app.public-app-url:http://localhost:3001}") String publicAppUrl) {
        this.jdbc = jdbc;
        this.otpService = otpService;
        this.journeyTracker = journeyTracker;
        this.processor = processor;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.mapper = mapper;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
    }

    @Transactional
    public Map<String, Object> status(AuthPrincipal me) {
        requirePartner(me);
        Map<String, Object> user = loadUser(me.userId());
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        Map<String, Object> identity = PublicOnboardingService.sanitizeIdentity(journeyTracker.getIdentity(journeyId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journeyId", journeyId);
        out.put("role", user.get("user_type"));
        out.put("fullName", user.get("full_name"));
        out.put("maskedMobile", OtpService.maskMobile(String.valueOf(user.get("mobile"))));
        out.put("status", journey.get("status"));
        out.put("journeyStatus", journey.get("status"));
        out.put("currentStep", journey.get("current_step"));
        out.put("hasPin", Boolean.TRUE.equals(user.get("has_pin")));
        out.put("identity", identity);
        return out;
    }

    public Map<String, Object> sendOtp(AuthPrincipal me, String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> user = loadUser(me.userId());
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        String mobile = String.valueOf(user.get("mobile"));

        OtpService.IssuedChallenge ch = otpService.sendOtp(mobile, "REVERIFY", me.userId());
        journeyTracker.record(
                journeyId, me.userId(), JourneyTracker.PHONE_VERIFIED, "INFO",
                Map.of("action", "OTP_SENT", "maskedMobile", ch.maskedMobile()),
                null, null, ip, userAgent, false);
        return Map.of(
                "maskedMobile", ch.maskedMobile(),
                "expiresAt", ch.expiresAt().toString(),
                "cooldownSeconds", ch.cooldownSeconds());
    }

    @Transactional
    public Map<String, Object> verifyOtp(AuthPrincipal me, String otp, String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> user = loadUser(me.userId());
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        String mobile = String.valueOf(user.get("mobile"));

        try {
            otpService.verifyOtp(mobile, "REVERIFY", me.userId(), otp);
        } catch (ApiException e) {
            journeyTracker.record(
                    journeyId, me.userId(), JourneyTracker.PHONE_VERIFIED, "FAILURE",
                    Map.of("reason", e.getCode()),
                    null, null, ip, userAgent, false);
            throw e;
        }

        journeyTracker.mergeIdentity(journeyId, Map.of("phoneVerified", true));
        journeyTracker.record(
                journeyId, me.userId(), JourneyTracker.PHONE_VERIFIED, "SUCCESS",
                Map.of("maskedMobile", OtpService.maskMobile(mobile)),
                null, null, ip, userAgent, true);

        String currentStep = jdbc.queryForObject(
                "SELECT current_step FROM onboarding_journeys WHERE id = ?", String.class, journeyId);
        Map<String, Object> identity = PublicOnboardingService.sanitizeIdentity(journeyTracker.getIdentity(journeyId));
        return Map.of(
                "currentStep", currentStep,
                "identity", identity);
    }

    @Transactional
    public Map<String, Object> captureLocation(AuthPrincipal me, Double latitude, Double longitude,
                                               Double accuracy, String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        return processor.captureLocation(journeyId, me.userId(), latitude, longitude, accuracy, ip, userAgent);
    }

    public Map<String, Object> startDigilocker(AuthPrincipal me, String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> user = loadUser(me.userId());
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        String fullName = String.valueOf(user.get("full_name"));
        return processor.startDigilocker(journeyId, me.userId(), fullName, ip, userAgent);
    }

    public Map<String, Object> completeDigilocker(AuthPrincipal me, String state,
                                                  String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> user = loadUser(me.userId());
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        String existingFullName = String.valueOf(user.get("full_name"));
        return processor.completeDigilocker(journeyId, null, me.userId(), state, existingFullName, ip, userAgent);
    }

    public Map<String, Object> verifyPan(AuthPrincipal me, String pan, String ip, String userAgent) {
        return verifyPan(me, pan, null, null, false, ip, userAgent);
    }

    public Map<String, Object> verifyPan(AuthPrincipal me, String pan, MultipartFile panFile,
                                         String ip, String userAgent) {
        return verifyPan(me, pan, null, panFile, false, ip, userAgent);
    }

    public Map<String, Object> verifyPan(AuthPrincipal me, String pan, String aadhaar,
                                         MultipartFile panFile, boolean requireFullAadhaar,
                                         String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        return processor.verifyPan(journeyId, null, me.userId(), pan, aadhaar, panFile, requireFullAadhaar, ip, userAgent);
    }

    public Map<String, Object> verifySelfie(AuthPrincipal me, MultipartFile file, String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        return processor.verifySelfie(journeyId, null, me.userId(), file, ip, userAgent);
    }

    @Transactional
    public Map<String, Object> completeReverification(AuthPrincipal me, String pinRaw, String newPasswordRaw,
                                                      String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> journey = getOrCreateJourney(me.userId());
        UUID journeyId = (UUID) journey.get("id");
        Map<String, Object> identity = journeyTracker.getIdentity(journeyId);

        if (!Boolean.TRUE.equals(identity.get("selfieVerified"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "STEP_ORDER_VIOLATION",
                    "Complete live selfie verification before setting your PIN");
        }

        Map<String, Object> userRow = loadUser(me.userId());
        boolean alreadyHasPin = Boolean.TRUE.equals(userRow.get("has_pin"));
        boolean providingPin = pinRaw != null && !pinRaw.isBlank();

        if (!alreadyHasPin && !providingPin) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIN",
                    "Set a 6-digit login PIN to complete verification");
        }

        String pinHash = providingPin ? passwordEncoder.encode(CredentialRules.validatePin(pinRaw)) : null;

        if (newPasswordRaw != null && !newPasswordRaw.isBlank()) {
            String validPwd = CredentialRules.validatePassword(newPasswordRaw);
            if (pinHash != null) {
                jdbc.update("""
                        UPDATE users
                           SET password_hash = ?, pin_hash = ?, failed_login_attempts = 0, locked_until = NULL, updated_at = now()
                         WHERE id = ?
                        """, passwordEncoder.encode(validPwd), pinHash, me.userId());
            } else {
                jdbc.update("""
                        UPDATE users
                           SET password_hash = ?, failed_login_attempts = 0, locked_until = NULL, updated_at = now()
                         WHERE id = ?
                        """, passwordEncoder.encode(validPwd), me.userId());
            }
        } else if (pinHash != null) {
            jdbc.update("""
                    UPDATE users
                       SET pin_hash = ?, failed_login_attempts = 0, locked_until = NULL, updated_at = now()
                     WHERE id = ?
                    """, pinHash, me.userId());
        }

        String verifiedName = String.valueOf(identity.getOrDefault("aadhaarName", me.name()));
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
                INSERT INTO agent_kyc_profiles (user_id, kyc_status)
                VALUES (?, 'COMPLETED')
                ON CONFLICT (user_id) DO NOTHING
                """, me.userId());

        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET aadhaar_ref_key = ?,
                       aadhaar_enc = COALESCE(?, aadhaar_enc),
                       aadhaar_last4 = ?,
                       aadhaar_verified_at = now(),
                       pan_enc = ?,
                       pan_last4 = ?,
                       pan_verified_at = now(),
                       aadhaar_pan_linked = ?,
                       liveness_score = NULL,
                       liveness_passed_at = NULL,
                       selfie_key = ?,
                       face_review_status = 'PENDING',
                       face_reviewed_by = NULL,
                       face_reviewed_at = NULL,
                       face_review_note = NULL,
                       kyc_status = 'COMPLETED',
                       personal_identity_verified = FALSE,
                       personal_status = 'NOT_VERIFIED',
                       personal_details = ?::jsonb,
                       identity_status = 'VERIFIED',
                       identity_verified_at = now(),
                       identity_details = ?::jsonb,
                       updated_at = now()
                 WHERE user_id = ?
                """,
                digilockerRef, aadhaarEncrypted, aadhaarLast4,
                panEncrypted, panLast4,
                aadhaarPanLinked,
                selfieKey,
                identityDetailsJson, identityDetailsJson,
                me.userId());

        processor.bindLatestDigilockerDocumentsToUser(journeyId, me.userId(), digilockerRef);

        if (lat != null && lng != null) {
            jdbc.update("""
                    INSERT INTO partner_verification_events (user_id, step, latitude, longitude, provider_ref, payload)
                    VALUES (?, 'PERSONAL', ?, ?, ?, ?::jsonb)
                    """, me.userId(), lat, lng, digilockerRef, identityDetailsJson);
        }

        jdbc.update("""
                UPDATE onboarding_journeys
                   SET status = 'COMPLETED', current_step = ?, completed_at = now(), last_activity_at = now()
                 WHERE id = ?
                """, JourneyTracker.CREDENTIALS_SET, journeyId);

        journeyTracker.record(
                journeyId, me.userId(), JourneyTracker.CREDENTIALS_SET, "SUCCESS",
                Map.of("reverified", true),
                lat, lng, ip, userAgent, true);

        sessions.revokeAllForUser(me.userId(), "REVERIFY_COMPLETED");
        log.info("REVERIFY_COMPLETED userId={} journeyId={}", me.userId(), journeyId);
        return Map.of("completed", true);
    }

    private Map<String, Object> getOrCreateJourney(UUID userId) {
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id, current_step, status
                  FROM onboarding_journeys
                 WHERE user_id = ? AND purpose = 'REVERIFY'
                   AND status IN ('IN_PROGRESS', 'NEEDS_REVIEW', 'ABANDONED')
                 ORDER BY started_at DESC
                 LIMIT 1
                """, userId);
        if (!open.isEmpty()) {
            return open.get(0);
        }
        UUID journeyId = journeyTracker.createJourney(null, userId, "REVERIFY", JourneyTracker.LINK_OPENED);
        journeyTracker.record(
                journeyId, userId, JourneyTracker.LINK_OPENED, "SUCCESS",
                Map.of("purpose", "REVERIFY"),
                null, null, null, null, true);
        return Map.of("id", journeyId, "current_step", JourneyTracker.LINK_OPENED, "status", "IN_PROGRESS");
    }

    private Map<String, Object> loadUser(UUID userId) {
        try {
            Map<String, Object> user = jdbc.queryForMap("""
                    SELECT u.id, u.full_name, u.mobile, u.user_type::text AS user_type, u.status::text AS status,
                           (u.pin_hash IS NOT NULL) AS has_pin,
                           COALESCE(k.identity_status, 'NOT_VERIFIED') AS identity_status
                      FROM users u
                      LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                     WHERE u.id = ?
                    """, userId);
            if ("VERIFIED".equals(String.valueOf(user.get("identity_status")))) {
                throw ApiException.of(
                        HttpStatus.CONFLICT,
                        "ALREADY_VERIFIED",
                        "Identity is already verified. Re-verification is only permitted when requested by an administrator.");
            }
            return user;
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
    }

    private static void requirePartner(AuthPrincipal me) {
        if (me == null || (!"RETAILER".equals(me.userType()) && !"MASTER_DISTRIBUTOR".equals(me.userType()))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a retailer or distributor can re-verify");
        }
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
}

