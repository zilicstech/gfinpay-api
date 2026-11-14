package com.fintech.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import com.fintech.verification.BusinessRegistrationService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SignatoryKycService {

    private final JdbcTemplate jdbc;
    private final OtpService otpService;
    private final JourneyTracker journeyTracker;
    private final OnboardingIdentityProcessor processor;
    private final ObjectMapper mapper;
    private final int sessionTtlMinutes;

    public SignatoryKycService(
            JdbcTemplate jdbc,
            OtpService otpService,
            JourneyTracker journeyTracker,
            OnboardingIdentityProcessor processor,
            ObjectMapper mapper,
            @Value("${app.onboarding.session-ttl-minutes:60}") int sessionTtlMinutes) {
        this.jdbc = jdbc;
        this.otpService = otpService;
        this.journeyTracker = journeyTracker;
        this.processor = processor;
        this.mapper = mapper;
        this.sessionTtlMinutes = sessionTtlMinutes;
    }

    @Transactional
    public Map<String, Object> openLink(String rawToken, String ip, String userAgent) {
        Map<String, Object> row = loadByToken(rawToken);
        String status = String.valueOf(row.get("status"));
        UUID signatoryId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");

        if ("REVOKED".equals(status) || !Boolean.TRUE.equals(row.get("active"))) {
            throw ApiException.of(HttpStatus.GONE, "SIGNATORY_LINK_REVOKED",
                    "This authorized signatory verification link has been revoked.");
        }
        Instant expiresAt = toInstant(row.get("expires_at"));
        if (!"KYC_COMPLETED".equals(status) && ("EXPIRED".equals(status) || (expiresAt != null && Instant.now().isAfter(expiresAt)))) {
            if (!"EXPIRED".equals(status)) {
                jdbc.update("UPDATE business_signatories SET status = 'EXPIRED', updated_at = now() WHERE id = ?", signatoryId);
                if (journeyId != null) {
                    jdbc.update("UPDATE onboarding_journeys SET status = 'EXPIRED' WHERE id = ?", journeyId);
                }
            }
            throw ApiException.of(HttpStatus.GONE, "SIGNATORY_LINK_EXPIRED",
                    "This authorized signatory verification link has expired. Ask the business partner to resend it.");
        }

        String currentStep = String.valueOf(row.get("current_step"));
        if (!"KYC_COMPLETED".equals(status) && JourneyTracker.LINK_SENT.equals(currentStep) && journeyId != null) {
            journeyTracker.record(
                    journeyId, null, JourneyTracker.LINK_OPENED, "SUCCESS",
                    Map.of("signatoryId", signatoryId.toString()),
                    null, null, ip, userAgent, true);
            currentStep = JourneyTracker.LINK_OPENED;
        }

        Map<String, Object> identity = journeyId == null
                ? Map.of()
                : PublicOnboardingService.sanitizeIdentity(journeyTracker.getIdentity(journeyId));
        String companyName = String.valueOf(identity.getOrDefault("companyName", row.get("cin")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inviteId", signatoryId);
        out.put("role", "AUTHORIZED_SIGNATORY");
        out.put("fullName", row.get("director_name"));
        out.put("directorName", row.get("director_name"));
        out.put("din", row.get("din"));
        out.put("cin", row.get("cin"));
        out.put("companyName", companyName);
        out.put("maskedMobile", OtpService.maskMobile(String.valueOf(row.get("mobile"))));
        out.put("status", "KYC_COMPLETED".equals(status) ? "COMPLETED" : status);
        out.put("journeyStatus", row.get("journey_status"));
        out.put("currentStep", currentStep);
        out.put("completed", "KYC_COMPLETED".equals(status));
        out.put("identity", identity);
        return out;
    }

    public Map<String, Object> sendOtp(String rawToken, String ip, String userAgent) {
        Map<String, Object> row = requireActiveSignatoryByToken(rawToken);
        UUID signatoryId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");
        String mobile = String.valueOf(row.get("mobile"));

        OtpService.IssuedChallenge ch = otpService.sendOtp(mobile, "SIGNATORY", signatoryId);
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
        Map<String, Object> row = requireActiveSignatoryByToken(rawToken);
        UUID signatoryId = (UUID) row.get("id");
        UUID journeyId = (UUID) row.get("journey_id");
        String mobile = String.valueOf(row.get("mobile"));

        try {
            otpService.verifyOtp(mobile, "SIGNATORY", signatoryId, otp);
        } catch (ApiException e) {
            if (journeyId != null) {
                journeyTracker.record(
                        journeyId, null, JourneyTracker.PHONE_VERIFIED, "FAILURE",
                        Map.of("reason", e.getCode()),
                        null, null, ip, userAgent, false);
            }
            throw e;
        }

        String sessionToken = BusinessRegistrationService.newOpaqueToken();
        String sessionHash = BusinessRegistrationService.sha256(sessionToken);
        Instant sessionExpiresAt = Instant.now().plus(Duration.ofMinutes(sessionTtlMinutes));

        jdbc.update("""
                UPDATE business_signatories
                   SET session_hash = ?,
                       session_expires_at = ?,
                       status = CASE WHEN status = 'LINK_SENT' THEN 'IN_PROGRESS' ELSE status END,
                       updated_at = now()
                 WHERE id = ?
                """, sessionHash, Timestamp.from(sessionExpiresAt), signatoryId);

        if (journeyId != null) {
            journeyTracker.mergeIdentity(journeyId, Map.of("phoneVerified", true));
            journeyTracker.record(
                    journeyId, null, JourneyTracker.PHONE_VERIFIED, "SUCCESS",
                    Map.of("maskedMobile", OtpService.maskMobile(mobile)),
                    null, null, ip, userAgent, true);
        }

        String currentStep = journeyId == null ? JourneyTracker.PHONE_VERIFIED
                : jdbc.queryForObject("SELECT current_step FROM onboarding_journeys WHERE id = ?", String.class, journeyId);
        Map<String, Object> identity = journeyId == null
                ? Map.of()
                : PublicOnboardingService.sanitizeIdentity(journeyTracker.getIdentity(journeyId));

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
        String directorName = String.valueOf(row.get("director_name"));
        return processor.startDigilocker(journeyId, null, directorName, ip, userAgent);
    }

    public Map<String, Object> completeDigilocker(String sessionToken, String state,
                                                  String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID journeyId = (UUID) row.get("journey_id");
        return processor.completeDigilocker(journeyId, null, null, state, null, ip, userAgent);
    }

    public Map<String, Object> verifyPan(String sessionToken, String pan, MultipartFile panFile,
                                         String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID journeyId = (UUID) row.get("journey_id");
        return processor.verifyPan(journeyId, null, null, pan, panFile, ip, userAgent);
    }

    @Transactional
    public Map<String, Object> verifySelfie(String sessionToken, MultipartFile file, String ip, String userAgent) {
        Map<String, Object> row = requireSession(sessionToken);
        UUID signatoryId = (UUID) row.get("id");
        UUID partnerUserId = (UUID) row.get("user_id");
        UUID journeyId = (UUID) row.get("journey_id");
        String directorName = String.valueOf(row.get("director_name"));

        Map<String, Object> selfieResult = processor.verifySelfie(journeyId, null, null, file, ip, userAgent);
        Map<String, Object> identity = journeyTracker.getIdentity(journeyId);

        String verifiedName = String.valueOf(identity.getOrDefault("aadhaarName",
                identity.getOrDefault("panName", directorName)));
        int nameMatchScore = NameMatcher.score(verifiedName, directorName);
        String aadhaarLast4 = clipChar4(String.valueOf(identity.getOrDefault("aadhaarLast4", "")));
        String panLast4 = clipChar4(String.valueOf(identity.getOrDefault("panLast4", "")));
        String panEncrypted = (String) identity.get("panEncrypted");
        boolean aadhaarPanLinked = Boolean.TRUE.equals(identity.get("aadhaarPanLinked"))
                || Boolean.TRUE.equals(identity.get("aadhaarLinked"));
        String selfieKey = (String) identity.get("selfieKey");

        Map<String, Object> sanitizedIdentity = new LinkedHashMap<>(PublicOnboardingService.sanitizeIdentity(identity));
        sanitizedIdentity.put("fullName", verifiedName);
        sanitizedIdentity.put("directorName", directorName);
        sanitizedIdentity.put("nameMatchScore", nameMatchScore);

        jdbc.update("""
                UPDATE business_signatories
                   SET status = 'KYC_COMPLETED',
                       verified_name = ?,
                       name_match_score = ?,
                       aadhaar_last4 = ?,
                       pan_last4 = ?,
                       pan_enc = ?,
                       aadhaar_pan_linked = ?,
                       selfie_key = ?,
                       identity_details = ?::jsonb,
                       session_hash = NULL,
                       session_expires_at = NULL,
                       completed_at = now(),
                       updated_at = now()
                 WHERE id = ?
                """,
                verifiedName, nameMatchScore, aadhaarLast4, panLast4, panEncrypted,
                aadhaarPanLinked, selfieKey, toJson(sanitizedIdentity), signatoryId);

        jdbc.update("""
                UPDATE onboarding_journeys
                   SET status = 'COMPLETED',
                       completed_at = now(),
                       last_activity_at = now()
                 WHERE id = ?
                """, journeyId);

        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET business_status = CASE WHEN business_status = 'AWAITING_SIGNATORY_KYC'
                                              THEN 'UNDER_REVIEW' ELSE business_status END,
                       updated_at = now()
                 WHERE user_id = ?
                """, partnerUserId);

        journeyTracker.recordUserMilestone(
                partnerUserId, "SIGNATORY_KYC_COMPLETED", "SUCCESS",
                Map.of("signatoryId", signatoryId.toString(), "directorName", directorName,
                        "verifiedName", verifiedName, "nameMatchScore", nameMatchScore));

        Map<String, Object> out = new LinkedHashMap<>(selfieResult);
        out.put("status", "COMPLETED");
        out.put("signatoryStatus", "KYC_COMPLETED");
        out.put("businessStatus", "UNDER_REVIEW");
        out.put("completed", true);
        out.put("verifiedName", verifiedName);
        out.put("nameMatchScore", nameMatchScore);
        return out;
    }

    private Map<String, Object> loadByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SIGNATORY_LINK_NOT_FOUND", "Invalid signatory verification link");
        }
        String hash = BusinessRegistrationService.sha256(rawToken.trim());
        try {
            return jdbc.queryForMap("""
                    SELECT s.*, j.current_step, j.status AS journey_status
                      FROM business_signatories s
                      LEFT JOIN onboarding_journeys j ON j.id = s.journey_id
                     WHERE s.token_hash = ?
                    """, hash);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SIGNATORY_LINK_NOT_FOUND",
                    "This signatory verification link is invalid or has been replaced.");
        }
    }

    private Map<String, Object> requireActiveSignatoryByToken(String rawToken) {
        Map<String, Object> row = loadByToken(rawToken);
        String status = String.valueOf(row.get("status"));
        if (!Boolean.TRUE.equals(row.get("active")) || "REVOKED".equals(status)) {
            throw ApiException.of(HttpStatus.GONE, "SIGNATORY_LINK_REVOKED", "This verification link has been revoked");
        }
        if ("KYC_COMPLETED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_ALREADY_COMPLETED",
                    "Authorized signatory verification has already been completed");
        }
        Instant expiresAt = toInstant(row.get("expires_at"));
        if ("EXPIRED".equals(status) || (expiresAt != null && Instant.now().isAfter(expiresAt))) {
            throw ApiException.of(HttpStatus.GONE, "SIGNATORY_LINK_EXPIRED", "This verification link has expired");
        }
        String journeyStatus = String.valueOf(row.get("journey_status"));
        if ("NEEDS_REVIEW".equals(status) || "NEEDS_REVIEW".equals(journeyStatus)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NEEDS_REVIEW",
                    "Verification is queued for manual compliance review");
        }
        return row;
    }

    private Map<String, Object> requireSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "ONBOARDING_SESSION_REQUIRED",
                    "Your verification session is missing or expired. Verify OTP again.");
        }
        String hash = BusinessRegistrationService.sha256(sessionToken.trim());
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT s.*, j.current_step, j.status AS journey_status
                      FROM business_signatories s
                      JOIN onboarding_journeys j ON j.id = s.journey_id
                     WHERE s.session_hash = ?
                    """, hash);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "ONBOARDING_SESSION_EXPIRED",
                    "Your verification session has expired. Verify OTP again to resume.");
        }
        String status = String.valueOf(row.get("status"));
        if (!Boolean.TRUE.equals(row.get("active"))
                || "KYC_COMPLETED".equals(status)
                || "REVOKED".equals(status)
                || "EXPIRED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_CLOSED",
                    "This signatory verification session is no longer active");
        }
        Instant sessionExpires = toInstant(row.get("session_expires_at"));
        if (sessionExpires == null || Instant.now().isAfter(sessionExpires)) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "ONBOARDING_SESSION_EXPIRED",
                    "Your verification session has timed out. Verify OTP again to continue.");
        }
        Instant nextExpiry = Instant.now().plus(Duration.ofMinutes(sessionTtlMinutes));
        jdbc.update("UPDATE business_signatories SET session_expires_at = ?, updated_at = now() WHERE id = ?",
                Timestamp.from(nextExpiry), row.get("id"));
        return row;
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

