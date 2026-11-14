package com.fintech.onboarding;

import com.fintech.platform.web.ApiException;
import com.fintech.sms.SmsSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OtpService {

    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_PER_HOUR = 15;
    public static final Duration TTL = Duration.ofMinutes(5);
    public static final Duration COOLDOWN = Duration.ofSeconds(30);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final JdbcTemplate jdbc;
    private final SmsSender smsSender;
    private final byte[] secret;

    public OtpService(JdbcTemplate jdbc, SmsSender smsSender,
                      @Value("${app.onboarding.otp-secret}") String otpSecret) {
        this.jdbc = jdbc;
        this.smsSender = smsSender;
        this.secret = otpSecret.getBytes(StandardCharsets.UTF_8);
    }

    public record IssuedChallenge(UUID challengeId, String maskedMobile, Instant expiresAt, int cooldownSeconds) {}

    @Transactional
    public IssuedChallenge sendOtp(String mobile, String purpose, UUID subjectId) {
        return sendOtp(mobile, purpose, subjectId, true);
    }

    @Transactional
    public IssuedChallenge sendOtp(String mobile, String purpose, UUID subjectId, boolean enforceCooldown) {
        String cleanMobile = cleanMobile(mobile);
        Instant now = Instant.now();

        if (enforceCooldown) {
            List<Map<String, Object>> recent = jdbc.queryForList("""
                    SELECT created_at
                      FROM phone_otp_challenges
                     WHERE subject_id = ? AND purpose = ?
                     ORDER BY created_at DESC
                     LIMIT 1
                    """, subjectId, purpose);
            if (!recent.isEmpty()) {
                Instant lastSent = toInstant(recent.get(0).get("created_at"));
                if (now.isBefore(lastSent.plus(COOLDOWN))) {
                    throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "OTP_COOLDOWN",
                            "Please wait 30 seconds before requesting another OTP");
                }
            }
        }

        Integer sentInLastHour = jdbc.queryForObject("""
                SELECT COUNT(*)::int
                  FROM phone_otp_challenges
                 WHERE mobile = ? AND created_at > ?
                """, Integer.class, cleanMobile, Timestamp.from(now.minus(Duration.ofHours(1))));
        if (sentInLastHour != null && sentInLastHour >= MAX_PER_HOUR) {
            throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED",
                    "Too many OTP requests for this mobile number. Try again after an hour.");
        }

        String otp = String.format("%06d", 100000 + RANDOM.nextInt(900000));
        String otpHash = hmac(subjectId, purpose, otp);
        UUID challengeId = UUID.randomUUID();
        Instant expiresAt = now.plus(TTL);

        jdbc.update("""
                INSERT INTO phone_otp_challenges (id, mobile, purpose, subject_id, otp_hash, attempts, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                """, challengeId, cleanMobile, purpose, subjectId, otpHash,
                Timestamp.from(expiresAt), Timestamp.from(now));

        smsSender.send(cleanMobile, "OTP_" + purpose, Map.of(
                "otp", otp,
                "purpose", purpose,
                "ttlMinutes", String.valueOf(TTL.toMinutes())
        ));
        return new IssuedChallenge(challengeId, maskMobile(cleanMobile), expiresAt, (int) COOLDOWN.toSeconds());
    }

    public UUID verifyOtp(String mobile, String purpose, UUID subjectId, String otpInput) {
        String cleanMobile = cleanMobile(mobile);
        String cleanOtp = otpInput == null ? "" : otpInput.trim();
        if (!cleanOtp.matches("\\d{6}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OTP", "Enter the 6-digit OTP");
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, otp_hash, attempts, expires_at, verified_at
                  FROM phone_otp_challenges
                 WHERE subject_id = ? AND purpose = ? AND mobile = ?
                 ORDER BY created_at DESC
                 LIMIT 1
                """, subjectId, purpose, cleanMobile);

        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_NOT_FOUND",
                    "Request an OTP first before verifying");
        }

        Map<String, Object> row = rows.get(0);
        UUID challengeId = (UUID) row.get("id");
        if (row.get("verified_at") != null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_ALREADY_USED",
                    "This OTP has already been used. Request a new OTP.");
        }

        int attempts = ((Number) row.get("attempts")).intValue();
        if (attempts >= MAX_ATTEMPTS) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_MAX_ATTEMPTS",
                    "Too many incorrect attempts. Request a new OTP.");
        }

        Instant expiresAt = toInstant(row.get("expires_at"));
        if (Instant.now().isAfter(expiresAt)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_EXPIRED",
                    "This OTP has expired. Request a new OTP.");
        }

        String expectedHash = String.valueOf(row.get("otp_hash"));
        String actualHash = hmac(subjectId, purpose, cleanOtp);
        boolean matches = MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                actualHash.getBytes(StandardCharsets.UTF_8));

        if (!matches) {
            int nextAttempts = attempts + 1;
            jdbc.update("UPDATE phone_otp_challenges SET attempts = ? WHERE id = ?", nextAttempts, challengeId);
            if (nextAttempts >= MAX_ATTEMPTS) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_MAX_ATTEMPTS",
                        "Too many incorrect attempts. Request a new OTP.");
            }
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OTP",
                    "Incorrect OTP. " + (MAX_ATTEMPTS - nextAttempts) + " attempt(s) remaining.");
        }

        jdbc.update("UPDATE phone_otp_challenges SET verified_at = now(), attempts = attempts + 1 WHERE id = ?", challengeId);
        return challengeId;
    }

    private String hmac(UUID subjectId, String purpose, String otp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String input = subjectId + ":" + purpose + ":" + otp;
            return B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "******0000";
        }
        return "******" + mobile.substring(mobile.length() - 4);
    }

    private static String cleanMobile(String mobile) {
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE", "Enter a 10-digit mobile number");
        }
        return digits;
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
        throw new IllegalStateException("Unexpected timestamp: " + val);
    }
}

