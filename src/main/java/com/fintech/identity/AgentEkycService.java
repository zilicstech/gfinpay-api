package com.fintech.identity;

import com.fintech.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentEkycService {

    private static final Logger log = LoggerFactory.getLogger(AgentEkycService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> OVD_TYPES = Set.of("AADHAAR", "PAN", "VOTER_ID", "DL", "PASSPORT");
    private static final Set<String> AGENT_TYPES = Set.of("MASTER_DISTRIBUTOR", "RETAILER");

    private final JdbcTemplate jdbc;
    private final AdminPlatformService admin;

    public AgentEkycService(JdbcTemplate jdbc, AdminPlatformService admin) {
        this.jdbc = jdbc;
        this.admin = admin;
    }

    @Transactional
    public Map<String, Object> start(UUID userId, String ovdType, String ovdLast4) {
        requireAgent(userId);
        if (isVerified(userId)) {
            throw ApiException.of(HttpStatus.CONFLICT, "EKYC_ALREADY_VERIFIED", "eKYC is already verified for this user");
        }
        String type = ovdType == null ? "" : ovdType.trim().toUpperCase().replace(' ', '_');
        if (!OVD_TYPES.contains(type)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OVD",
                    "ID must be Aadhaar, PAN, voter ID, driving licence or passport");
        }
        String compact = ovdLast4 == null ? "" : ovdLast4.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (compact.length() < 4) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OVD",
                    "Enter the last 4 characters of the ID. The full number is never stored.");
        }
        String last4 = compact.substring(compact.length() - 4);
        ensureProfile(userId);
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET ovd_type = ?, aadhaar_last4 = ?, updated_at = now()
                 WHERE user_id = ?
                """, type, last4, userId);
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("""
                UPDATE agent_ekyc_challenges SET expires_at = now()
                 WHERE user_id = ? AND verified_at IS NULL AND expires_at > now()
                """, userId);
        jdbc.update("""
                INSERT INTO agent_ekyc_challenges (id, user_id, otp_hash, expires_at)
                VALUES (?, ?, ?, now() + interval '5 minutes')
                """, UUID.randomUUID(), userId, hash(otp, userId));
        log.info("AGENT_EKYC_OTP user={}", userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", true);
        out.put("expiresInSeconds", 300);
        return out;
    }

    @Transactional
    public Map<String, Object> verify(UUID userId, String otp) {
        requireAgent(userId);
        if (isVerified(userId)) {
            return admin.getUser(userId);
        }
        List<Map<String, Object>> profile = jdbc.queryForList(
                "SELECT aadhaar_last4 FROM agent_kyc_profiles WHERE user_id = ?", userId);
        if (profile.isEmpty() || profile.get(0).get("aadhaar_last4") == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EKYC_INCOMPLETE",
                    "Capture the ID before verifying the OTP");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, otp_hash FROM agent_ekyc_challenges
                 WHERE user_id = ? AND verified_at IS NULL AND expires_at > now()
                 ORDER BY created_at DESC
                 LIMIT 1
                """, userId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_EXPIRED", "OTP expired. Send a new one.");
        }
        String expected = String.valueOf(rows.get(0).get("otp_hash"));
        if (!expected.equals(hash(otp == null ? "" : otp.trim(), userId))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_INVALID", "Incorrect OTP");
        }
        jdbc.update("UPDATE agent_ekyc_challenges SET verified_at = now() WHERE id = ?", rows.get(0).get("id"));
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET kyc_status = 'VERIFIED', aadhaar_verified_at = now(), updated_at = now()
                 WHERE user_id = ?
                """, userId);
        log.info("AGENT_EKYC_VERIFIED user={}", userId);
        return admin.getUser(userId);
    }

    private void requireAgent(UUID userId) {
        String type;
        try {
            type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        }
        if (!AGENT_TYPES.contains(type)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EKYC_NOT_APPLICABLE",
                    "eKYC applies only to distributors and retailers");
        }
    }

    private boolean isVerified(UUID userId) {
        try {
            String status = jdbc.queryForObject(
                    "SELECT kyc_status::text FROM agent_kyc_profiles WHERE user_id = ?", String.class, userId);
            return "VERIFIED".equals(status);
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    private void ensureProfile(UUID userId) {
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, kyc_status)
                VALUES (?, 'NOT_STARTED')
                ON CONFLICT (user_id) DO NOTHING
                """, userId);
    }

    private static String hash(String otp, UUID userId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((otp + ":" + userId + ":AGENT_EKYC").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("OTP hash failed", e);
        }
    }
}
