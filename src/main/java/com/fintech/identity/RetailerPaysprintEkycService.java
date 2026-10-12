package com.fintech.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.paysprint.PaysprintJwtVerifier;
import com.fintech.paysprint.PaysprintOnboardClient;
import com.fintech.paysprint.PaysprintOnboardOutcome;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PublicAppUrl;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Issues a PaySprint hosted onboarding link for a retailer and records Bank 2 completion.
 * Does not collect PID data and does not change outlet activation.
 */
@Service
public class RetailerPaysprintEkycService {

    private static final Logger log = LoggerFactory.getLogger(RetailerPaysprintEkycService.class);

    private final JdbcTemplate jdbc;
    private final PaysprintOnboardClient client;
    private final PaysprintJwtVerifier jwt;
    private final String publicAppUrl;

    public RetailerPaysprintEkycService(JdbcTemplate jdbc,
                                        PaysprintOnboardClient client,
                                        PaysprintJwtVerifier jwt,
                                        @Value("${app.public-app-url}") String publicAppUrl) {
        this.jdbc = jdbc;
        this.client = client;
        this.jwt = jwt;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
    }

    public Map<String, Object> issue(UUID retailerId) {
        return issue(retailerId, null);
    }

    public Map<String, Object> issue(UUID retailerId, String suppliedEmail) {
        Map<String, Object> row = loadRetailer(retailerId);
        if ("VERIFIED".equals(String.valueOf(row.get("kyc_status")))) {
            return result("VERIFIED", null, "eKYC is already completed.");
        }
        String code = text(row.get("code"));
        String mobile = digits(text(row.get("mobile")));
        String email = resolveEmail(retailerId, text(row.get("email")), suppliedEmail);
        String firm = text(row.get("shop_name"));
        if (firm.isBlank()) {
            firm = text(row.get("full_name"));
        }
        if (code.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RETAILER_CODE_MISSING",
                    "This retailer has no GFIN code");
        }
        if (mobile.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                    "Retailer mobile must be 10 digits");
        }
        if (firm.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FIRM_REQUIRED",
                    "Add a shop name or retailer name before starting eKYC");
        }

        boolean issued = row.get("paysprint_onboard_issued_at") != null;
        if (issued && refreshAccepted(code, mobile)) {
            return result("VERIFIED", null, "eKYC is completed.");
        }
        String token = ensureLinkToken(retailerId, text(row.get("paysprint_onboard_token")));
        String link = publicAppUrl + "/ekyc/" + token;
        log.info("PAYSPRINT_ONBOARD portal link merchantcode={} email_present={}", code, !email.isBlank());
        return result(text(row.get("kyc_status")).isBlank() ? "NOT_STARTED" : text(row.get("kyc_status")),
                link, "Open this link to continue to PaySprint eKYC.");
    }

    public Map<String, Object> publicStatus(String token) {
        Map<String, Object> row = loadByToken(token);
        boolean verified = "VERIFIED".equals(String.valueOf(row.get("kyc_status")));
        return Map.of("active", !verified, "kyc_status", String.valueOf(row.get("kyc_status")));
    }

    public Map<String, String> start(String token) {
        Map<String, Object> row = loadByToken(token);
        if ("VERIFIED".equals(String.valueOf(row.get("kyc_status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EKYC_COMPLETED",
                    "eKYC is already completed.");
        }
        String code = text(row.get("code"));
        String mobile = digits(text(row.get("mobile")));
        String email = text(row.get("email"));
        String firm = text(row.get("shop_name"));
        if (firm.isBlank()) {
            firm = text(row.get("full_name"));
        }
        if (email.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_REQUIRED",
                    "PaySprint requires an email for this retailer.");
        }
        String storedRedirect = text(row.get("paysprint_redirect_url"));
        if (!storedRedirect.isBlank()) {
            log.info("PAYSPRINT_ONBOARD reuse merchantcode={}", code);
            return Map.of("url", storedRedirect, "method", "GET");
        }
        boolean issued = row.get("paysprint_onboard_issued_at") != null;
        String callback = publicAppUrl + "/ekyc/" + token + "/done";
        log.info("PAYSPRINT_ONBOARD start merchantcode={} is_new={} callback={}", code, issued ? "0" : "1", callback);
        JsonNode root = client.onboardUrl(code, mobile, email, firm, callback, issued ? "0" : "1");
        String redirect = root.path("redirecturl").asText("");
        log.info("PAYSPRINT_ONBOARD link merchantcode={} response_code={} redirect_present={} message={}",
                code, root.path("response_code").asText(""), !redirect.isBlank(), root.path("message").asText(""));
        if (redirect.isBlank()) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_ONBOARD_ERROR",
                    root.path("message").asText("Paysprint did not return an onboarding link"));
        }
        rememberRedirect((UUID) row.get("id"), redirect);
        return Map.of("url", redirect, "method", "GET");
    }

    public Map<String, Object> complete(String token, String data) {
        Map<String, Object> row = loadByToken(token);
        JsonNode claims;
        try {
            claims = jwt.verify(data);
        } catch (RuntimeException e) {
            throw ApiException.of(HttpStatus.BAD_REQUEST, "EKYC_UNCONFIRMED",
                    "This eKYC result could not be confirmed.");
        }
        String claimCode = claims.path("merchantcode").asText("").trim();
        if (claimCode.isBlank() || !claimCode.equalsIgnoreCase(text(row.get("code")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EKYC_MISMATCH",
                    "This eKYC result does not match the link.");
        }
        log.info("PAYSPRINT_ONBOARD portal return merchantcode={} status={} bank2={}",
                claimCode, claims.path("status").asText(""), claims.path("bank").path("Bank2").asText(""));
        boolean done = PaysprintOnboardOutcome.redirectComplete(claims);
        if (done) {
            markVerified(claimCode);
        }
        return Map.of("completed", done);
    }

    private String ensureLinkToken(UUID retailerId, String existing) {
        if (!existing.isBlank()) {
            return existing;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, kyc_status, paysprint_onboard_token)
                VALUES (?, 'NOT_STARTED', ?)
                ON CONFLICT (user_id) DO UPDATE
                   SET paysprint_onboard_token = COALESCE(agent_kyc_profiles.paysprint_onboard_token, EXCLUDED.paysprint_onboard_token),
                       updated_at = now()
                """, retailerId, token);
        String stored = jdbc.queryForObject(
                "SELECT paysprint_onboard_token FROM agent_kyc_profiles WHERE user_id = ?",
                String.class, retailerId);
        return stored == null || stored.isBlank() ? token : stored;
    }

    private Map<String, Object> loadByToken(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "This link is not valid");
        }
        try {
            return jdbc.queryForMap("""
                    SELECT u.id, u.full_name, u.mobile, u.email, u.code,
                           k.shop_name, k.kyc_status::text AS kyc_status, k.paysprint_onboard_issued_at,
                           k.paysprint_redirect_url
                      FROM agent_kyc_profiles k
                      JOIN users u ON u.id = k.user_id
                     WHERE k.paysprint_onboard_token = ? AND u.user_type = 'RETAILER'
                    """, token.trim());
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "This link is not valid");
        }
    }

    /** Charge callback is an acknowledgement only. The JWT must verify. */
    public JsonNode verifyCharge(JsonNode body) {
        JsonNode claims = verifyEnc(body);
        JsonNode param = body.path("param");
        log.info("PAYSPRINT_ONBOARD charge merchant_id={} request_id={} amount={}",
                param.path("merchant_id").asText(""),
                param.path("request_id").asText(""),
                param.path("amount").asText(""));
        return claims;
    }

    /** Status callback. Marks eKYC verified only when Bank 2 is Active. */
    public void applyStatus(JsonNode body) {
        verifyEnc(body);
        JsonNode param = body.path("param");
        log.info("PAYSPRINT_ONBOARD status callback merchantcode={} status={} bank2={}",
                param.path("merchantcode").asText(""),
                param.path("status").asText(""),
                param.path("bank").path("Bank2").asText(""));
        if (!PaysprintOnboardOutcome.statusCallbackComplete(param)) {
            return;
        }
        markVerified(param.path("merchantcode").asText(""));
    }

    /** Redirect callback `data` token. Marks eKYC verified only when Bank 2 is 1. */
    public boolean applyRedirect(String data) {
        JsonNode claims = jwt.verify(data);
        log.info("PAYSPRINT_ONBOARD redirect merchantcode={} status={} bank2={}",
                claims.path("merchantcode").asText(""),
                claims.path("status").asText(""),
                claims.path("bank").path("Bank2").asText(""));
        if (!PaysprintOnboardOutcome.redirectComplete(claims)) {
            return false;
        }
        markVerified(claims.path("merchantcode").asText(""));
        return true;
    }

    private JsonNode verifyEnc(JsonNode body) {
        String enc = body.path("param_enc").asText("");
        if (enc.isBlank()) {
            enc = body.path("param_inc").asText("");
        }
        JsonNode claims = jwt.verify(enc);
        String claimMerchant = firstText(claims, "merchantcode", "merchant_id");
        String paramMerchant = firstText(body.path("param"), "merchantcode", "merchant_id");
        if (!claimMerchant.isBlank() && !paramMerchant.isBlank()
                && !claimMerchant.equalsIgnoreCase(paramMerchant)) {
            throw new IllegalArgumentException("Token does not match the merchant");
        }
        return claims;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** Live Bank 2 onboard status. Accepted marks the retailer eKYC verified. */
    public Map<String, Object> checkStatus(UUID retailerId) {
        Map<String, Object> row = loadRetailer(retailerId);
        String code = text(row.get("code"));
        String mobile = digits(text(row.get("mobile")));
        if (code.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RETAILER_CODE_MISSING",
                    "This retailer has no GFIN code");
        }
        if (mobile.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                    "Retailer mobile must be 10 digits");
        }
        JsonNode status = client.onboardStatus(code, mobile);
        String approved = status.path("is_approved").asText("").trim();
        String message = status.path("message").asText("").trim();
        log.info("PAYSPRINT_ONBOARD status merchantcode={} http_status={} is_approved={} message={}",
                code, status.path("status").asBoolean(false), approved, message);
        boolean accepted = PaysprintOnboardOutcome.statusCheckAccepted(status);
        if (accepted) {
            markVerified(code);
        }
        if (message.isBlank()) {
            message = accepted ? "Onboarding completed" : "PaySprint did not return an onboarding status.";
        }
        String kyc = accepted
                ? "VERIFIED"
                : (text(row.get("kyc_status")).isBlank() ? "NOT_STARTED" : text(row.get("kyc_status")));
        return statusResult(kyc, approved, message, accepted, portalLink(text(row.get("paysprint_onboard_token"))));
    }

    private boolean refreshAccepted(String code, String mobile) {
        JsonNode status;
        try {
            status = client.onboardStatus(code, mobile);
        } catch (ApiException e) {
            log.info("PAYSPRINT_ONBOARD status skipped merchantcode={} reason={}", code, e.getMessage());
            return false;
        }
        log.info("PAYSPRINT_ONBOARD status merchantcode={} http_status={} is_approved={} message={}",
                code, status.path("status").asBoolean(false), status.path("is_approved").asText(""),
                status.path("message").asText(""));
        if (!PaysprintOnboardOutcome.statusCheckAccepted(status)) {
            return false;
        }
        markVerified(code);
        return true;
    }

    public void markVerified(String merchantCode) {
        String code = merchantCode == null ? "" : merchantCode.trim();
        if (code.isBlank()) {
            throw new PaysprintCallbackRejected("Merchant code missing");
        }
        UUID userId;
        try {
            userId = jdbc.queryForObject("""
                    SELECT id FROM users
                     WHERE user_type = 'RETAILER' AND upper(code) = upper(?)
                    """, UUID.class, code);
        } catch (EmptyResultDataAccessException e) {
            throw new PaysprintCallbackRejected("Retailer not found");
        }
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, kyc_status, aadhaar_verified_at)
                VALUES (?, 'VERIFIED', now())
                ON CONFLICT (user_id) DO UPDATE
                   SET kyc_status = 'VERIFIED',
                       aadhaar_verified_at = COALESCE(agent_kyc_profiles.aadhaar_verified_at, now()),
                       updated_at = now()
                """, userId);
        log.info("PAYSPRINT_ONBOARD verified merchantcode={}", code);
    }

    /** Keeps the first PaySprint URL for this retailer. Later opens reuse it. */
    private void rememberRedirect(UUID retailerId, String redirectUrl) {
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET paysprint_redirect_url = COALESCE(paysprint_redirect_url, ?),
                       paysprint_onboard_issued_at = COALESCE(paysprint_onboard_issued_at, now()),
                       updated_at = now()
                 WHERE user_id = ?
                """, redirectUrl, retailerId);
    }

    private String portalLink(String token) {
        if (token.isBlank()) {
            return null;
        }
        return publicAppUrl + "/ekyc/" + token;
    }

    private String resolveEmail(UUID retailerId, String stored, String supplied) {
        if (!stored.isBlank()) {
            return stored;
        }
        String mail = supplied == null ? "" : supplied.trim().toLowerCase();
        if (mail.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_REQUIRED",
                    "PaySprint requires an email for this retailer.");
        }
        if (!mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL", "Enter a valid email");
        }
        try {
            jdbc.update("""
                    UPDATE users SET email = ?, updated_at = now()
                     WHERE id = ? AND (email IS NULL OR btrim(email) = '')
                    """, mail, retailerId);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_ALREADY_REGISTERED",
                    "This email is already used on another account");
        }
        return mail;
    }

    private Map<String, Object> loadRetailer(UUID retailerId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT u.id, u.user_type::text AS user_type, u.full_name, u.mobile, u.email, u.code,
                           k.shop_name, k.kyc_status::text AS kyc_status, k.paysprint_onboard_issued_at,
                           k.paysprint_onboard_token
                      FROM users u
                      LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                     WHERE u.id = ?
                    """, retailerId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        if (!"RETAILER".equals(row.get("user_type"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_A_RETAILER",
                    "PaySprint eKYC is for a retailer");
        }
        return row;
    }

    private static Map<String, Object> result(String kycStatus, String redirectUrl, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kyc_status", kycStatus);
        out.put("redirect_url", redirectUrl);
        out.put("message", message == null ? "" : message);
        return out;
    }

    private static Map<String, Object> statusResult(String kycStatus, String approved, String message,
                                                    boolean completed, String redirectUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kyc_status", kycStatus);
        out.put("is_approved", approved);
        out.put("message", message);
        out.put("completed", completed);
        out.put("redirect_url", redirectUrl);
        return out;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String digits(String value) {
        return value.replaceAll("\\D", "");
    }

    public static class PaysprintCallbackRejected extends RuntimeException {
        public PaysprintCallbackRejected(String message) {
            super(message);
        }
    }
}
