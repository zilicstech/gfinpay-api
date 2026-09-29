package com.fintech.kyc;

import com.fintech.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walk-in customer KYC. DMT (RBI DPSS 24 Jul 2024): verified mobile + self-certified OVD + AFA per txn.
 * FD cards: CDD with PAN + OVD + explicit consent. UPI cash-out: identity log; 2FA is the customer's UPI PIN.
 */
@Service
public class CustomerKycService {

    public static final String PURPOSE_MOBILE = "MOBILE_VERIFY";
    public static final String PURPOSE_DMT = "DMT_CONSENT";
    public static final String PURPOSE_CARD = "CARD_CONSENT";
    public static final Set<String> OVD_TYPES = Set.of("AADHAAR", "PAN", "VOTER_ID", "DL", "PASSPORT");

    private static final Logger log = LoggerFactory.getLogger(CustomerKycService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public CustomerKycService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> getCustomer(UUID senderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.id, s.mobile, s.full_name, s.kyc_level, s.ovd_type, s.ovd_last4, s.address_line,
                       s.pan_last4, s.mobile_verified_at, s.ovd_captured_at, s.created_at
                  FROM dmt_senders s
                 WHERE s.id = ?
                """, senderId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SENDER_NOT_FOUND", "Customer is not registered");
        }
        return withFlags(rows.get(0));
    }

    public Map<String, Object> withFlags(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        boolean ovd = row.get("ovd_type") != null && row.get("ovd_last4") != null
                && row.get("address_line") != null && !String.valueOf(row.get("address_line")).isBlank();
        boolean mobileOk = row.get("mobile_verified_at") != null;
        boolean pan = row.get("pan_last4") != null && !String.valueOf(row.get("pan_last4")).isBlank();
        out.put("ovd_complete", ovd);
        out.put("mobile_verified", mobileOk);
        out.put("ready_for_dmt", ovd && mobileOk);
        out.put("ready_for_card", ovd && mobileOk && pan);
        return out;
    }

    @Transactional
    public Map<String, Object> captureOvd(UUID senderId, String ovdType, String ovdLast4, String addressLine, String pan) {
        String type = ovdType == null ? "" : ovdType.trim().toUpperCase().replace(' ', '_');
        if (!OVD_TYPES.contains(type)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OVD",
                    "OVD must be Aadhaar, PAN, voter ID, driving licence or passport");
        }
        String compact = ovdLast4 == null ? "" : ovdLast4.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (compact.length() < 4) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OVD",
                    "Enter the last 4 characters of the ID. Full Aadhaar is never stored.");
        }
        String last4 = compact.substring(compact.length() - 4);
        String address = addressLine == null ? "" : addressLine.trim();
        if (address.length() < 8) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ADDRESS",
                    "Enter the address as printed on the ID");
        }
        String panLast4 = null;
        if (pan != null && !pan.isBlank()) {
            String panCompact = pan.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
            if (!panCompact.matches("[A-Z]{5}[0-9]{4}[A-Z]")) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAN",
                        "PAN should look like ABCDE1234F");
            }
            panLast4 = panCompact.substring(panCompact.length() - 4);
        }
        int n = jdbc.update("""
                UPDATE dmt_senders
                   SET ovd_type = ?, ovd_last4 = ?, address_line = ?,
                       pan_last4 = COALESCE(?, pan_last4),
                       ovd_captured_at = now(),
                       kyc_level = CASE WHEN mobile_verified_at IS NOT NULL THEN 'OTP_VERIFIED' ELSE 'MIN_KYC' END
                 WHERE id = ?
                """, type, last4, address, panLast4, senderId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SENDER_NOT_FOUND", "Customer is not registered");
        }
        log.info("CUSTOMER_OVD sender={} type={}", senderId, type);
        return getCustomer(senderId);
    }

    @Transactional
    public Map<String, Object> sendOtp(UUID senderId, String purpose) {
        requirePurpose(purpose);
        getCustomer(senderId);
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("""
                UPDATE customer_otp_challenges SET expires_at = now()
                 WHERE sender_id = ? AND purpose = ? AND verified_at IS NULL AND expires_at > now()
                """, senderId, purpose);
        jdbc.update("""
                INSERT INTO customer_otp_challenges (id, sender_id, purpose, otp_hash, expires_at)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes')
                """, UUID.randomUUID(), senderId, purpose, hash(otp, senderId, purpose));
        log.info("CUSTOMER_OTP_SENT sender={} purpose={}", senderId, purpose);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        out.put("expiresInSeconds", 300);
        out.put("sent", true);
        return out;
    }

    @Transactional
    public Map<String, Object> verifyOtp(UUID senderId, String purpose, String otp) {
        requirePurpose(purpose);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id FROM customer_otp_challenges
                 WHERE sender_id = ? AND purpose = ? AND verified_at IS NULL AND expires_at > now()
                 ORDER BY created_at DESC
                 LIMIT 1
                """, senderId, purpose);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_EXPIRED",
                    "OTP expired or was not requested. Send a new one.");
        }
        UUID challengeId = (UUID) rows.get(0).get("id");
        String expected = jdbc.queryForObject(
                "SELECT otp_hash FROM customer_otp_challenges WHERE id = ?", String.class, challengeId);
        if (expected == null || !expected.equals(hash(otp == null ? "" : otp.trim(), senderId, purpose))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_INVALID", "Incorrect OTP");
        }
        jdbc.update("UPDATE customer_otp_challenges SET verified_at = now() WHERE id = ?", challengeId);
        if (PURPOSE_MOBILE.equals(purpose)) {
            jdbc.update("""
                    UPDATE dmt_senders
                       SET mobile_verified_at = now(),
                           kyc_level = CASE WHEN ovd_type IS NOT NULL THEN 'OTP_VERIFIED' ELSE 'OTP_VERIFIED' END
                     WHERE id = ?
                    """, senderId);
        }
        if (PURPOSE_CARD.equals(purpose) || PURPOSE_DMT.equals(purpose)) {
            jdbc.update("""
                    UPDATE dmt_senders SET mobile_verified_at = COALESCE(mobile_verified_at, now()),
                           kyc_level = CASE WHEN ovd_type IS NOT NULL THEN 'OTP_VERIFIED' ELSE kyc_level END
                     WHERE id = ?
                    """, senderId);
        }
        log.info("CUSTOMER_OTP_OK sender={} purpose={}", senderId, purpose);
        return getCustomer(senderId);
    }

    public void requireDmtReady(UUID senderId) {
        Map<String, Object> c = getCustomer(senderId);
        if (!Boolean.TRUE.equals(c.get("ovd_complete"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "KYC_REQUIRED",
                    "Capture a self-certified ID (Aadhaar / PAN / voter ID / DL / passport) and address before DMT");
        }
        if (!Boolean.TRUE.equals(c.get("mobile_verified"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MOBILE_NOT_VERIFIED",
                    "Verify the customer's mobile with OTP before DMT");
        }
    }

    public void requireRecentConsent(UUID senderId, String purpose) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM customer_otp_challenges
                 WHERE sender_id = ? AND purpose = ? AND verified_at IS NOT NULL
                   AND verified_at > now() - interval '10 minutes'
                """, Integer.class, senderId, purpose);
        if (n == null || n == 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CONSENT_REQUIRED",
                    "Customer must confirm with OTP before this action");
        }
    }

    public void requireCardReady(UUID senderId) {
        requireDmtReady(senderId);
        Map<String, Object> c = getCustomer(senderId);
        if (!Boolean.TRUE.equals(c.get("ready_for_card"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAN_REQUIRED",
                    "PAN is required to originate an FD-backed card");
        }
    }

    private static void requirePurpose(String purpose) {
        if (!PURPOSE_MOBILE.equals(purpose) && !PURPOSE_DMT.equals(purpose) && !PURPOSE_CARD.equals(purpose)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PURPOSE", "Unknown OTP purpose");
        }
    }

    private static String hash(String otp, UUID senderId, String purpose) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((otp + ":" + senderId + ":" + purpose).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("OTP hash failed", e);
        }
    }
}
