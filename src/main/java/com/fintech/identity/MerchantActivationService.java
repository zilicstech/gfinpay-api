package com.fintech.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.paysprint.MerchantActivationOutcome;
import com.fintech.paysprint.PaysprintOnboardClient;
import com.fintech.paysprint.PaysprintPidCipher;
import com.fintech.paysprint.PaysprintProperties;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MerchantActivationService {

    private static final Logger log = LoggerFactory.getLogger(MerchantActivationService.class);
    private static final int HOURLY_LIMIT = 5;
    private static final Set<String> PIPES = Set.of("bank2", "bank6");

    private final JdbcTemplate jdbc;
    private final PaysprintOnboardClient client;
    private final PaysprintPidCipher cipher;
    private final PaysprintProperties props;
    private final PlatformServiceGate services;
    private final RetailerPaysprintEkycService onboarding;

    public MerchantActivationService(JdbcTemplate jdbc, PaysprintOnboardClient client, PaysprintPidCipher cipher,
                                     PaysprintProperties props, PlatformServiceGate services,
                                     RetailerPaysprintEkycService onboarding) {
        this.jdbc = jdbc;
        this.client = client;
        this.cipher = cipher;
        this.props = props;
        this.services = services;
        this.onboarding = onboarding;
    }

    public Map<String, Object> status(AuthPrincipal me) {
        return view(me, false);
    }

    /** KYC page load: create a missing link, or ask PaySprint when a link already exists. */
    public Map<String, Object> prepare(AuthPrincipal me) {
        return view(me, true);
    }

    private Map<String, Object> view(AuthPrincipal me, boolean prepare) {
        requireRetailer(me);
        Map<String, Object> gate = prepare
                ? onboarding.syncForRetailer(me.userId())
                : onboarding.currentForRetailer(me.userId());
        Map<String, Object> profile = profile(me.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kycStatus", text(gate.get("kycStatus"), text(profile.get("kyc_status"), "PENDING")));
        out.put("bankStatus", text(profile.get("bank_status"), "PENDING"));
        out.put("onboardingUrl", gate.get("onboardingUrl"));
        out.put("canRefresh", Boolean.TRUE.equals(gate.get("canRefresh")));
        out.put("message", text(gate.get("message"), ""));
        out.put("allowPastedPid", props.allowPastedPid());
        out.put("rdEnv", props.rdEnvOrLive());
        out.put("dmtActive", dmtActive(me.userId()));
        out.put("banks", banks(me.userId()));
        return out;
    }

    public Map<String, Object> refreshOnboarding(AuthPrincipal me) {
        requireRetailer(me);
        Map<String, Object> refreshed = onboarding.refreshOnboardUrl(me.userId());
        String status = text(refreshed.get("kyc_status"), "IN PROGRESS");
        if (!"COMPLETED".equals(status)) {
            status = "IN PROGRESS";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kycStatus", status);
        out.put("onboardingUrl", refreshed.get("redirect_url"));
        out.put("canRefresh", refreshed.get("redirect_url") != null);
        out.put("message", text(refreshed.get("message"), ""));
        return out;
    }

    public Map<String, Object> activate(AuthPrincipal me, String pipe, String aadhaar, String dob, String pidData,
                                        String latitude, String longitude, boolean activateDmt) {
        requireRetailer(me);
        String bank = pipe(pipe);
        Map<String, Object> profile = profile(me.userId());
        if (!"COMPLETED".equals(text(profile.get("kyc_status"), ""))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ONBOARDING_INCOMPLETE",
                    "Finish the eKYC link before scanning a finger.");
        }
        String digits = aadhaar == null ? "" : aadhaar.replaceAll("\\D", "");
        if (digits.length() != 12) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AADHAAR", "Enter the 12-digit Aadhaar number.");
        }
        if (dob == null || !dob.trim().matches("\\d{4}/\\d{2}/\\d{2}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DOB", "Enter the date of birth as YYYY/MM/DD.");
        }
        String pid = pidData == null ? "" : pidData.trim();
        if (!pid.contains("PidData") || pid.length() < 40) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FINGERPRINT_MISSING",
                    "The scanner did not return a fingerprint. Scan again.");
        }
        if ("bank6".equals(bank) && (blank(latitude) || blank(longitude))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Allow location so cash out eKYC can continue.");
        }
        throttle(me.userId(), bank);
        String code = text(profile.get("code"), "");
        if (code.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RETAILER_CODE_MISSING", "This retailer has no GFIN code.");
        }
        log.info("MERCHANT_EKYC activate start pipe={} retailer={} is_casa={} pid_length={} has_location={}",
                bank, code, activateDmt ? "1" : "0", pid.length(), !blank(latitude) && !blank(longitude));
        String encrypted = cipher.encrypt(pid);
        log.info("MERCHANT_EKYC pid encrypted pipe={} in_length={} out_length={}", bank, pid.length(), encrypted.length());
        JsonNode root = client.activateMerchant(code, digits, encrypted, dob.trim(), bank,
                activateDmt ? "1" : "0", "SITE", latitude, longitude);
        String responseCode = root.path("response_code").asText("");
        MerchantActivationOutcome outcome = MerchantActivationOutcome.from(responseCode);
        String message = root.path("message").asText("");
        if (message.isBlank()) {
            message = outcome.message();
        } else if (!"1".equals(responseCode) && !"3".equals(responseCode)) {
            message = outcome.message();
        }
        record(me.userId(), bank, code, digits, responseCode, outcome.status(), message, activateDmt);
        if (outcome.activated()) {
            jdbc.update("""
                    UPDATE agent_kyc_profiles
                       SET aadhaar_last4 = ?, updated_at = now()
                     WHERE user_id = ?
                    """, digits.substring(8), me.userId());
        }
        String bankStatus = refreshBankStatus(me.userId());
        if (!"1".equals(responseCode)) {
            log.warn("MERCHANT_EKYC paysprint response pipe={} response_code={} outcome={}",
                    bank, responseCode, outcome.status());
        } else {
            log.info("MERCHANT_EKYC paysprint response pipe={} response_code={} outcome={}",
                    bank, responseCode, outcome.status());
        }
        log.info("MERCHANT_EKYC result pipe={} outcome={} bank_status={}", bank, outcome.status(), bankStatus);
        if (!"1".equals(responseCode) && !"3".equals(responseCode)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MERCHANT_EKYC_REJECTED", message);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipe", bank);
        out.put("status", outcome.status());
        out.put("message", "1".equals(responseCode) ? outcome.message() : message);
        out.put("responseCode", responseCode);
        out.put("bankStatus", bankStatus);
        out.put("dmtActive", dmtActive(me.userId()));
        return out;
    }

    public void deviceEvent(AuthPrincipal me, String stage, String pipe, boolean ok, Integer port, String errCode,
                            String errInfo, String reason, String deviceSerial, String deviceModel, String dpId,
                            String rdsId, Integer qScore, Integer pidLength, Integer tookMs, Integer portsTried) {
        requireRetailer(me);
        String line = "MERCHANT_EKYC device stage={} ok={} pipe={} port={} errCode={} errInfo={} reason={} serial={} model={} dpId={} rdsId={} qScore={} pid_length={} took_ms={} ports_tried={}";
        if (ok) {
            log.info(line, stage, true, pipe, port, errCode, errInfo, reason, deviceSerial, deviceModel, dpId, rdsId,
                    qScore, pidLength, tookMs, portsTried);
        } else {
            log.warn(line, stage, false, pipe, port, errCode, errInfo, reason, deviceSerial, deviceModel, dpId, rdsId,
                    qScore, pidLength, tookMs, portsTried);
        }
    }

    public void record(java.util.UUID userId, String bank, String code, String aadhaar, String responseCode,
                       String status, String message, boolean activateDmt) {
        boolean dmtOn = "SUCCESS".equals(status) && activateDmt;
        jdbc.update("""
                INSERT INTO paysprint_bank_ekyc
                    (user_id, bank_name, status, completed_at, last_response_code, last_message, attempts, last_attempt_at, is_dmt_active)
                VALUES (?, ?, ?, CASE WHEN ? = 'SUCCESS' THEN now() ELSE NULL END, ?, ?, 1, now(), ?)
                ON CONFLICT (user_id, bank_name) DO UPDATE
                   SET status = EXCLUDED.status,
                       completed_at = CASE WHEN EXCLUDED.status = 'SUCCESS' THEN now() ELSE paysprint_bank_ekyc.completed_at END,
                       last_response_code = EXCLUDED.last_response_code,
                       last_message = EXCLUDED.last_message,
                       attempts = paysprint_bank_ekyc.attempts + 1,
                       last_attempt_at = now(),
                       is_dmt_active = paysprint_bank_ekyc.is_dmt_active OR EXCLUDED.is_dmt_active,
                       updated_at = now()
                """, userId, bank, status, status, responseCode, clip(message), dmtOn);
        log.info("MERCHANT_EKYC stored pipe={} merchantcode={} aadhaar_last4={} response_code={}",
                bank, code, aadhaar.substring(aadhaar.length() - 4), responseCode);
    }

    private boolean dmtActive(java.util.UUID userId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*)::int FROM paysprint_bank_ekyc
                 WHERE user_id = ? AND is_dmt_active
                """, Integer.class, userId);
        return n != null && n > 0;
    }

    private String refreshBankStatus(java.util.UUID userId) {
        boolean ready = true;
        for (String bank : requiredBanks()) {
            Integer n = jdbc.queryForObject("""
                    SELECT count(*)::int FROM paysprint_bank_ekyc
                     WHERE user_id = ? AND bank_name = ? AND status = 'SUCCESS'
                    """, Integer.class, userId, bank);
            if (n == null || n == 0) {
                ready = false;
                break;
            }
        }
        String status = ready ? "VERIFIED" : "PENDING";
        jdbc.update("UPDATE agent_kyc_profiles SET bank_status = ?, updated_at = now() WHERE user_id = ?",
                status, userId);
        return status;
    }

    private List<String> requiredBanks() {
        List<String> banks = new ArrayList<>();
        banks.add("bank2");
        if (services.isEnabled(PlatformServiceGate.UPI_CASHOUT)) {
            banks.add("bank6");
        }
        return banks;
    }

    private List<Map<String, Object>> banks(java.util.UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT bank_name, status, last_message, completed_at, is_dmt_active
                  FROM paysprint_bank_ekyc WHERE user_id = ?
                """, userId);
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byName.put(String.valueOf(row.get("bank_name")), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String bank : List.of("bank2", "bank6")) {
            boolean needed = "bank2".equals(bank) || services.isEnabled(PlatformServiceGate.UPI_CASHOUT);
            Map<String, Object> stored = byName.get(bank);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bankName", bank);
            item.put("label", "bank6".equals(bank) ? "Cash out eKYC" : "Money transfer eKYC");
            item.put("needed", needed);
            item.put("needsLocation", "bank6".equals(bank));
            item.put("wadh", props.wadhFor(bank));
            item.put("status", stored == null ? "PENDING" : text(stored.get("status"), "PENDING"));
            item.put("message", stored == null ? "" : text(stored.get("last_message"), ""));
            item.put("dmtActive", stored != null && Boolean.TRUE.equals(stored.get("is_dmt_active")));
            out.add(item);
        }
        return out;
    }

    private void throttle(java.util.UUID userId, String bank) {
        Integer recent = jdbc.queryForObject("""
                SELECT count(*)::int FROM activity_events
                 WHERE actor_user_id = ?
                   AND action = 'Merchant eKYC'
                   AND http_path = ?
                   AND occurred_at > now() - interval '1 hour'
                """, Integer.class, userId, "/api/v1/me/ekyc/" + bank + "/activate");
        if (recent != null && recent >= HOURLY_LIMIT) {
            throw ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "EKYC_LIMIT",
                    "Too many scans in the last hour. Wait and try again.");
        }
    }

    private Map<String, Object> profile(java.util.UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.code, k.kyc_status, k.bank_status
                  FROM users u
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                 WHERE u.id = ?
                """, userId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Account not found");
        }
        return rows.get(0);
    }

    private static void requireRetailer(AuthPrincipal me) {
        if (me == null || !"RETAILER".equals(me.userType())) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a retailer can complete this eKYC.");
        }
    }

    private static String pipe(String pipe) {
        String bank = pipe == null ? "" : pipe.trim().toLowerCase(java.util.Locale.ROOT);
        if (!PIPES.contains(bank)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_BANK", "Choose money transfer or cash out eKYC.");
        }
        return bank;
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equals(text) ? fallback : text;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
