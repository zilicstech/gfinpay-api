package com.fintech.biometric;

import com.fintech.emerald.EmeraldClient;
import com.fintech.emerald.EmeraldMessageSanitizer;
import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.instantpay.InstantPayClient;
import com.fintech.instantpay.InstantPayCrypto;
import com.fintech.instantpay.InstantPayProperties;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ChannelLimits;
import com.fintech.provider.ProviderOnboardingService;
import com.fintech.verification.AadhaarValidator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider-opaque merchant biometric activation & live status check service (Steps 2 & 3).
 *
 * <p>Never exposes upstream vendor or pipe names (e.g. Emerald / InstantPay) to retailers.
 */
@Service
public class BiometricActivationService {

    private static final Logger log = LoggerFactory.getLogger(BiometricActivationService.class);
    private static final Duration LIVE_SYNC_MIN_INTERVAL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final FieldCipher fieldCipher;
    private final EmeraldClient emeraldClient;
    private final ProviderOnboardingService providerOnboarding;
    private final InstantPayClient instantPayClient;
    private final InstantPayProperties instantPayProps;
    private final String rdEnv;
    private final boolean allowPastedPid;

    @Autowired
    public BiometricActivationService(
            JdbcTemplate jdbc,
            FieldCipher fieldCipher,
            EmeraldClient emeraldClient,
            ProviderOnboardingService providerOnboarding,
            InstantPayClient instantPayClient,
            InstantPayProperties instantPayProps,
            @Value("${app.biometric.rd-env:P}") String rdEnv,
            @Value("${app.biometric.allow-pasted-pid:false}") boolean allowPastedPid) {
        this.jdbc = jdbc;
        this.fieldCipher = fieldCipher;
        this.emeraldClient = emeraldClient;
        this.providerOnboarding = providerOnboarding;
        this.instantPayClient = instantPayClient;
        this.instantPayProps = instantPayProps;
        this.rdEnv = (rdEnv == null || rdEnv.isBlank()) ? "P" : rdEnv.trim().toUpperCase(Locale.ROOT);
        this.allowPastedPid = allowPastedPid;
    }

    public BiometricActivationService(
            JdbcTemplate jdbc,
            FieldCipher fieldCipher,
            EmeraldClient emeraldClient,
            ProviderOnboardingService providerOnboarding,
            String rdEnv,
            boolean allowPastedPid) {
        this(jdbc, fieldCipher, emeraldClient, providerOnboarding, null, null, rdEnv, allowPastedPid);
    }

    /**
     * Step 3: Returns white-labeled cash & payout activation state for the logged-in retailer.
     * When {@code refresh == true}, performs a throttled live sync against upstream outlet details
     * if any enabled channel is still awaiting {@code FULL_KYC} or bank sync.
     */
    public Map<String, Object> getCashActivation(AuthPrincipal me, boolean refresh) {
        requirePartner(me);
        UUID userId = me.userId();

        if (refresh && shouldTriggerLiveSync(userId)) {
            try {
                providerOnboarding.syncFromEmeraldLive(userId);
            } catch (Exception ex) {
                log.debug("Passive Emerald cash-activation sync skipped for user={}: {}", userId, ex.getMessage());
            }
            try {
                providerOnboarding.syncFromInstantPayLive(userId);
            } catch (Exception ex) {
                log.debug("Passive InstantPay cash-activation sync skipped for user={}: {}", userId, ex.getMessage());
            }
        }

        Map<String, Object> kycRow = loadKycRow(userId);
        boolean businessVerified = Boolean.TRUE.equals(kycRow.get("business_verified"))
                || "BUSINESS_VERIFIED".equalsIgnoreCase(str(kycRow.get("business_status")));
        boolean agreementSigned = "SIGNED".equalsIgnoreCase(str(kycRow.get("agreement_status")));
        boolean servicesUnlocked = businessVerified && agreementSigned;
        boolean hasFullAadhaar = Boolean.TRUE.equals(kycRow.get("has_full_aadhaar"));
        String aadhaarLast4 = str(kycRow.get("aadhaar_last4"));

        List<Map<String, Object>> activations = jdbc.queryForList("""
                SELECT a.id, c.channel_key, c.supports_upi_cashout, c.supports_aeps, c.supports_payout,
                       c.biometric_required,
                       a.onboarding_status, a.kyc_status, a.biometric_verified_at, a.bank_sync_status,
                       a.enabled_for_retailer, c.active AS platform_active, c.priority_order
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                  JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                 WHERE a.user_id = ?
                   AND a.enabled_for_retailer = TRUE
                   AND c.active = TRUE
                   AND m.status = 'ACTIVE'
                 ORDER BY c.priority_order ASC, c.created_at ASC
                """, userId);

        boolean terminalProvisioned = !activations.isEmpty();
        boolean anyFullKyc = false;
        boolean anyPendingBiometric = false;
        boolean upiCashoutActive = false;
        boolean aepsActive = false;
        boolean payoutBankReady = false;
        String defaultPendingChannelKey = null;

        List<Map<String, Object>> opaqueChannels = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> row : activations) {
            String key = str(row.get("channel_key"));
            String kycStatus = str(row.get("kyc_status"));
            String bankSync = str(row.get("bank_sync_status"));
            boolean biometricNeeded = Boolean.TRUE.equals(row.get("biometric_required"));
            boolean isFull = "FULL_KYC".equalsIgnoreCase(kycStatus);
            boolean readyWithoutBiometric = !biometricNeeded && "NOT_REQUIRED".equalsIgnoreCase(kycStatus);
            if (isFull) {
                anyFullKyc = true;
            }
            if (isFull || readyWithoutBiometric) {
                if (Boolean.TRUE.equals(row.get("supports_upi_cashout"))) upiCashoutActive = true;
                if (isFull && Boolean.TRUE.equals(row.get("supports_aeps"))) aepsActive = true;
                if (isFull && Boolean.TRUE.equals(row.get("supports_payout")) && "SYNCED".equalsIgnoreCase(bankSync)) {
                    payoutBankReady = true;
                }
            } else if (biometricNeeded) {
                anyPendingBiometric = true;
                if (defaultPendingChannelKey == null) {
                    defaultPendingChannelKey = key;
                }
            }

            boolean channelReady = isFull || readyWithoutBiometric;
            boolean approvalPending = "APPROVAL_PENDING".equalsIgnoreCase(kycStatus);
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("channelKey", key);
            ch.put("terminalLabel", activations.size() == 1 ? "Merchant Banking Terminal" : "Banking Terminal #" + index);
            ch.put("biometricVerified", channelReady);
            ch.put("status", channelReady ? "ACTIVE" : (approvalPending ? "APPROVAL_PENDING" : "BIOMETRIC_REQUIRED"));
            ch.put("biometricVerifiedAt", row.get("biometric_verified_at"));
            ch.put("bankReady", "SYNCED".equalsIgnoreCase(bankSync));
            opaqueChannels.add(ch);
            index++;
        }

        boolean cashServicesActive = servicesUnlocked && (anyFullKyc || upiCashoutActive);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("servicesUnlocked", servicesUnlocked);
        out.put("businessVerified", businessVerified);
        out.put("agreementSigned", agreementSigned);
        out.put("terminalProvisioned", terminalProvisioned);
        out.put("biometricRequired", terminalProvisioned && anyPendingBiometric && !upiCashoutActive && !aepsActive);
        out.put("additionalBiometricAvailable", (anyFullKyc || upiCashoutActive) && anyPendingBiometric);
        out.put("biometricVerified", anyFullKyc);
        out.put("cashServicesActive", cashServicesActive);
        out.put("upiCashoutActive", servicesUnlocked && upiCashoutActive);
        out.put("aepsActive", servicesUnlocked && aepsActive);
        out.put("payoutBankReady", servicesUnlocked && payoutBankReady);
        out.put("hasFullAadhaar", hasFullAadhaar);
        out.put("aadhaarLast4", aadhaarLast4);
        out.put("pendingChannelKey", defaultPendingChannelKey);
        out.put("rdEnv", rdEnv);
        out.put("allowPastedPid", allowPastedPid);
        out.put("channels", opaqueChannels);
        return out;
    }

    @Transactional
    public Map<String, Object> initiateBiometric(
            AuthPrincipal me,
            String channelKeyRaw,
            String aadhaarIfMissing) {
        requirePartner(me);
        UUID userId = me.userId();

        if (aadhaarIfMissing != null && !aadhaarIfMissing.isBlank()) {
            saveRetailerAadhaar(userId, aadhaarIfMissing);
        }
        requireStoredAadhaar(userId);

        Map<String, Object> target = resolveRetailerChannel(userId, channelKeyRaw);
        if ("FULL_KYC".equalsIgnoreCase(str(target.get("kyc_status")))) {
            return Map.of(
                    "alreadyVerified", true,
                    "channelKey", str(target.get("channel_key")),
                    "message", "Biometric verification is already active on your account.");
        }

        UUID channelId = (UUID) target.get("channel_id");
        String channelKey = str(target.get("channel_key"));
        String providerCode = str(target.get("provider_code"));
        String pipeCode = str(target.get("pipe_code"));
        String outletCode = str(target.get("outlet_code"));
        String configJson = str(target.get("config_json"));

        UUID sessionId = UUID.randomUUID();
        String externalRef = "EKYC" + System.currentTimeMillis() + sessionId.toString().substring(0, 4).toUpperCase(Locale.ROOT);

        if ("INSTANTPAY".equalsIgnoreCase(providerCode)) {
            return initiateInstantPayBiometric(userId, channelId, channelKey, outletCode, configJson, sessionId, externalRef);
        }

        if (!"EMERALD".equalsIgnoreCase(providerCode)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_TERMINAL",
                    "Biometric initiation is not supported on this terminal channel yet.");
        }

        EmeraldClient.BiometricInitResponse initRes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP2_EKYC_INIT", externalRef)) {
            initRes = emeraldClient.initiateBiometricKyc(outletCode, pipeCode);
        }
        if (!initRes.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    initRes.message(),
                    "Unable to start biometric verification right now. Please retry in a moment.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BIOMETRIC_INIT_FAILED", msg);
        }

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));

        jdbc.update("""
                INSERT INTO biometric_kyc_sessions
                    (id, user_id, channel_id, external_ref, reference_key, pid_wadh, reference_type,
                     status, provider_status_code, provider_message, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'INITIATED', ?, ?, ?)
                """,
                sessionId,
                userId,
                channelId,
                externalRef,
                initRes.referenceKey(),
                initRes.pidWadh(),
                initRes.referenceKeyType(),
                initRes.statusCode(),
                initRes.message(),
                Timestamp.from(expiresAt));

        log.info("MERCHANT_EKYC initiated user={} session={} channel={} hasWadh={}",
                userId, sessionId, channelKey, initRes.pidWadh() != null && !initRes.pidWadh().isBlank());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alreadyVerified", false);
        out.put("otpRequired", false);
        out.put("sessionId", sessionId);
        out.put("channelKey", channelKey);
        out.put("wadh", initRes.pidWadh() == null ? "" : initRes.pidWadh());
        out.put("rdEnv", rdEnv);
        out.put("allowPastedPid", allowPastedPid);
        out.put("expiresAt", expiresAt);
        return out;
    }

    private Map<String, Object> initiateInstantPayBiometric(
            UUID userId,
            UUID channelId,
            String channelKey,
            String outletCode,
            String configJson,
            UUID sessionId,
            String externalRef) {
        if (instantPayClient == null || instantPayProps == null || !instantPayProps.isConfigured()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "TERMINAL_UNAVAILABLE",
                    "Banking terminal verification is temporarily unavailable. Please try again shortly.");
        }
        String spkey = ChannelLimits.text(configJson, "spkey", instantPayProps.defaultSpkey());
        String gw = ChannelLimits.text(configJson, "gw", instantPayProps.defaultGw());

        InstantPayClient.BiometricStatusResponse statusRes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP2_EKYC_INIT", externalRef)) {
            statusRes = instantPayClient.checkBiometricKycStatus(outletCode, spkey, gw);
        }
        if (!statusRes.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    statusRes.message(),
                    "Unable to start biometric verification right now. Please retry in a moment.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BIOMETRIC_INIT_FAILED", msg);
        }

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));

        // Step 2a: Outlet not yet registered with acquiring bank -> trigger 6-digit OTP verification first
        if ("NOTYETREGISTERED".equalsIgnoreCase(statusRes.actCode())) {
            InstantPayClient.OtpRegistrationResponse regRes;
            try (var ignored = EmeraldTraceContext.open(userId, "STEP2_OUTLET_OTP_SEND", externalRef)) {
                regRes = instantPayClient.registerOutletOtp(outletCode, statusRes.referenceKey());
            }
            if (!regRes.otpSent() || regRes.referenceKey().isBlank()) {
                String msg = EmeraldMessageSanitizer.sanitize(
                        regRes.message(),
                        "Could not send verification code to your registered mobile number. Please retry.");
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_SEND_FAILED", msg);
            }
            jdbc.update("""
                    INSERT INTO biometric_kyc_sessions
                        (id, user_id, channel_id, external_ref, registration_ref_key, reference_type,
                         status, provider_status_code, provider_message, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'AWAITING_OTP', ?, ?, ?)
                    """,
                    sessionId, userId, channelId, externalRef,
                    regRes.referenceKey(),
                    regRes.referenceKeyType(),
                    regRes.statusCode(),
                    regRes.message(),
                    Timestamp.from(expiresAt));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("alreadyVerified", false);
            out.put("otpRequired", true);
            out.put("sessionId", sessionId);
            out.put("channelKey", channelKey);
            out.put("rdEnv", rdEnv);
            out.put("allowPastedPid", allowPastedPid);
            out.put("expiresAt", expiresAt);
            out.put("message", "Enter the verification OTP sent to your Aadhaar-linked mobile number.");
            return out;
        }

        // Step 2b: Already verified or awaiting acquiring bank review
        if ("NO-ACTION-REQUIRED".equalsIgnoreCase(statusRes.action())) {
            String resolved = ProviderOnboardingService.resolveInstantPayKycState(statusRes);
            if ("FULL_KYC".equals(resolved)) {
                jdbc.update("""
                        UPDATE provider_pipe_activations
                           SET kyc_status = 'FULL_KYC',
                               biometric_verified_at = COALESCE(biometric_verified_at, now()),
                               last_error = NULL,
                               last_synced_at = now(),
                               updated_at = now()
                         WHERE user_id = ? AND channel_id = ?
                        """, userId, channelId);
                return Map.of(
                        "alreadyVerified", true,
                        "channelKey", channelKey,
                        "message", "Biometric verification is already active on your account.");
            }
            jdbc.update("""
                    UPDATE provider_pipe_activations
                       SET kyc_status = 'APPROVAL_PENDING',
                           last_synced_at = now(),
                           updated_at = now()
                     WHERE user_id = ? AND channel_id = ?
                    """, userId, channelId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("alreadyVerified", false);
            out.put("approvalPending", true);
            out.put("channelKey", channelKey);
            out.put("message", "Your biometric verification is under bank review. Please check status again in 30 minutes.");
            return out;
        }

        // Step 2c: ACTION-REQUIRED -> ready for L1 RD fingerprint capture
        jdbc.update("""
                INSERT INTO biometric_kyc_sessions
                    (id, user_id, channel_id, external_ref, reference_key, pid_wadh, reference_type,
                     status, provider_status_code, provider_message, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'INITIATED', ?, ?, ?)
                """,
                sessionId, userId, channelId, externalRef,
                statusRes.referenceKey(),
                statusRes.pidOptionWadh(),
                statusRes.referenceKeyType(),
                statusRes.statusCode(),
                statusRes.message(),
                Timestamp.from(expiresAt));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alreadyVerified", false);
        out.put("otpRequired", false);
        out.put("sessionId", sessionId);
        out.put("channelKey", channelKey);
        out.put("wadh", statusRes.pidOptionWadh() == null ? "" : statusRes.pidOptionWadh());
        out.put("rdEnv", rdEnv);
        out.put("allowPastedPid", allowPastedPid);
        out.put("expiresAt", expiresAt);
        return out;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyRegistrationOtp(
            AuthPrincipal me,
            String channelKeyRaw,
            UUID sessionId,
            String otpRaw) {
        requirePartner(me);
        UUID userId = me.userId();
        if (sessionId == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_REQUIRED",
                    "Start terminal verification again to request a fresh OTP.");
        }
        String otp = otpRaw == null ? "" : otpRaw.replaceAll("\\D", "");
        if (otp.length() < 4 || otp.length() > 8) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OTP",
                    "Enter the valid numeric OTP sent to your mobile number.");
        }

        Map<String, Object> session;
        try {
            session = jdbc.queryForMap("""
                    SELECT s.id, s.channel_id, s.external_ref, s.registration_ref_key, s.status, s.expires_at,
                           c.channel_key, c.provider_code, c.config_json::text AS config_json, m.outlet_code
                      FROM biometric_kyc_sessions s
                      JOIN service_provider_channels c ON c.id = s.channel_id
                      JOIN provider_pipe_activations a ON a.user_id = s.user_id AND a.channel_id = s.channel_id
                      JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                     WHERE s.id = ? AND s.user_id = ?
                     FOR UPDATE OF s
                    """, sessionId, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                    "Verification session was not found. Please start again.");
        }

        Timestamp expTs = (Timestamp) session.get("expires_at");
        if (!"AWAITING_OTP".equalsIgnoreCase(str(session.get("status")))
                || (expTs != null && expTs.toInstant().isBefore(Instant.now()))) {
            jdbc.update("UPDATE biometric_kyc_sessions SET status = 'EXPIRED' WHERE id = ?", sessionId);
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_EXPIRED",
                    "OTP session expired. Click 'Start Biometric Scan' to send a new code.");
        }

        String outletCode = str(session.get("outlet_code"));
        String regRefKey = str(session.get("registration_ref_key"));
        String externalRef = str(session.get("external_ref"));
        String channelKey = str(session.get("channel_key"));
        String configJson = str(session.get("config_json"));
        UUID channelId = (UUID) session.get("channel_id");

        InstantPayClient.GenericResponse verifyRes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP2_OUTLET_OTP_VERIFY", externalRef)) {
            verifyRes = instantPayClient.verifyOutletRegistrationOtp(outletCode, regRefKey, otp);
        }
        if (!verifyRes.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    verifyRes.message(),
                    "Incorrect or expired OTP. Please verify the code and try again.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_VERIFY_FAILED", msg);
        }

        String spkey = ChannelLimits.text(configJson, "spkey", instantPayProps.defaultSpkey());
        String gw = ChannelLimits.text(configJson, "gw", instantPayProps.defaultGw());
        InstantPayClient.BiometricStatusResponse bioStatus;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP2_EKYC_AFTER_OTP", externalRef)) {
            bioStatus = instantPayClient.checkBiometricKycStatus(outletCode, spkey, gw);
        }
        if (!bioStatus.success()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BIOMETRIC_INIT_FAILED",
                    "Mobile OTP verified, please click Start Biometric Scan once more to continue.");
        }

        if ("NO-ACTION-REQUIRED".equalsIgnoreCase(bioStatus.action())) {
            String resolved = ProviderOnboardingService.resolveInstantPayKycState(bioStatus);
            if ("FULL_KYC".equals(resolved)) {
                jdbc.update("UPDATE biometric_kyc_sessions SET status = 'SUCCESS', otp_verified_at = now(), submitted_at = now() WHERE id = ?", sessionId);
                jdbc.update("""
                        UPDATE provider_pipe_activations
                           SET kyc_status = 'FULL_KYC',
                               biometric_verified_at = COALESCE(biometric_verified_at, now()),
                               last_error = NULL,
                               last_synced_at = now(),
                               updated_at = now()
                         WHERE user_id = ? AND channel_id = ?
                        """, userId, channelId);
                return Map.of("alreadyVerified", true, "channelKey", channelKey, "message", "Terminal verification completed!");
            }
        }

        Instant nextExpiry = Instant.now().plus(Duration.ofMinutes(10));
        jdbc.update("""
                UPDATE biometric_kyc_sessions
                   SET status = 'INITIATED',
                       otp_verified_at = now(),
                       reference_key = ?,
                       pid_wadh = ?,
                       reference_type = ?,
                       provider_status_code = ?,
                       provider_message = ?,
                       expires_at = ?
                 WHERE id = ?
                """,
                bioStatus.referenceKey(),
                bioStatus.pidOptionWadh(),
                bioStatus.referenceKeyType(),
                bioStatus.statusCode(),
                bioStatus.message(),
                Timestamp.from(nextExpiry),
                sessionId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alreadyVerified", false);
        out.put("otpRequired", false);
        out.put("sessionId", sessionId);
        out.put("channelKey", channelKey);
        out.put("wadh", bioStatus.pidOptionWadh() == null ? "" : bioStatus.pidOptionWadh());
        out.put("rdEnv", rdEnv);
        out.put("allowPastedPid", allowPastedPid);
        out.put("expiresAt", nextExpiry);
        return out;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> submitBiometric(
            AuthPrincipal me,
            String channelKeyRaw,
            UUID sessionId,
            String pidXml,
            String latitudeRaw,
            String longitudeRaw) {
        requirePartner(me);
        UUID userId = me.userId();

        if (sessionId == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_REQUIRED",
                    "Start a fresh biometric verification session before scanning.");
        }

        Map<String, Object> session;
        try {
            session = jdbc.queryForMap("""
                    SELECT s.id, s.channel_id, s.external_ref, s.reference_key, s.status, s.expires_at,
                           c.channel_key, c.provider_code, c.pipe_code, c.config_json::text AS config_json, m.outlet_code
                      FROM biometric_kyc_sessions s
                      JOIN service_provider_channels c ON c.id = s.channel_id
                      JOIN provider_pipe_activations a ON a.user_id = s.user_id AND a.channel_id = s.channel_id
                      JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                     WHERE s.id = ? AND s.user_id = ?
                     FOR UPDATE OF s
                    """, sessionId, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                    "Biometric verification session was not found. Please start again.");
        }

        String curStatus = str(session.get("status"));
        if ("SUCCESS".equalsIgnoreCase(curStatus)) {
            return getCashActivation(me, false);
        }
        Timestamp expTs = (Timestamp) session.get("expires_at");
        if (!"INITIATED".equalsIgnoreCase(curStatus) || (expTs != null && expTs.toInstant().isBefore(Instant.now()))) {
            jdbc.update("UPDATE biometric_kyc_sessions SET status = 'EXPIRED' WHERE id = ?", sessionId);
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_EXPIRED",
                    "Biometric session expired. Click 'Start Biometric Scan' to generate a fresh session.");
        }

        BigDecimal lat = parseCoordinate(latitudeRaw, true, userId);
        BigDecimal lng = parseCoordinate(longitudeRaw, false, userId);
        String aadhaar12 = requireStoredAadhaar(userId);
        RdPidXmlParser.ParsedPidData parsed = RdPidXmlParser.parse(pidXml);

        String providerCode = str(session.get("provider_code"));
        String outletCode = str(session.get("outlet_code"));
        String pipeCode = str(session.get("pipe_code"));
        String configJson = str(session.get("config_json"));
        String referenceKey = str(session.get("reference_key"));
        String externalRef = str(session.get("external_ref"));
        UUID channelId = (UUID) session.get("channel_id");

        Integer qScoreInt = parseIntOrNull(parsed.qScore());
        Integer nmPointsInt = parseIntOrNull(parsed.nmPoints());

        if ("INSTANTPAY".equalsIgnoreCase(providerCode)) {
            String encAadhaar = InstantPayCrypto.encryptAadhaar(aadhaar12, instantPayProps.encryptionKey());
            Map<String, String> biometricData25 = parsed.toInstantPayBiometricData(encAadhaar);
            InstantPayClient.GenericResponse ipRes;
            try (var ignored = EmeraldTraceContext.open(userId, "STEP2_EKYC_SUBMIT", externalRef)) {
                ipRes = instantPayClient.submitBiometricKyc(
                        outletCode,
                        referenceKey,
                        lat.toPlainString(),
                        lng.toPlainString(),
                        externalRef,
                        biometricData25);
            }
            if (!ipRes.success()) {
                String cleanReason = EmeraldMessageSanitizer.sanitize(
                        ipRes.message(),
                        "Biometric verification did not match. Clean your fingerprint sensor and scan again.");
                jdbc.update("""
                        UPDATE biometric_kyc_sessions
                           SET status = 'FAILED',
                               capture_latitude = ?, capture_longitude = ?,
                               rd_rds_id = ?, rd_rds_ver = ?, rd_dp_id = ?, rd_mi = ?, rd_srno = ?,
                               q_score = ?, nm_points = ?,
                               provider_status_code = ?, provider_message = ?,
                               submitted_at = now()
                         WHERE id = ?
                        """,
                        lat, lng,
                        parsed.rdsId(), parsed.rdsVer(), parsed.dpId(), parsed.mi(), parsed.srno(),
                        qScoreInt, nmPointsInt,
                        ipRes.statusCode(), ipRes.message(),
                        sessionId);
                jdbc.update("""
                        UPDATE provider_pipe_activations
                           SET last_error = ?, updated_at = now()
                         WHERE user_id = ? AND channel_id = ?
                        """, cleanReason, userId, channelId);
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BIOMETRIC_KYC_FAILED", cleanReason);
            }

            // Re-check biometricKycStatus to distinguish immediate FULL_KYC vs bank APPROVAL_PENDING
            String finalKycState = "FULL_KYC";
            try (var ignored = EmeraldTraceContext.open(userId, "STEP2_EKYC_VERIFY_POST_SUBMIT", externalRef)) {
                String spkey = ChannelLimits.text(configJson, "spkey", instantPayProps.defaultSpkey());
                String gw = ChannelLimits.text(configJson, "gw", instantPayProps.defaultGw());
                InstantPayClient.BiometricStatusResponse postBio = instantPayClient.checkBiometricKycStatus(outletCode, spkey, gw);
                if (postBio.success()) {
                    String resolved = ProviderOnboardingService.resolveInstantPayKycState(postBio);
                    if ("APPROVAL_PENDING".equals(resolved)) {
                        finalKycState = "APPROVAL_PENDING";
                    }
                }
            } catch (Exception ex) {
                log.debug("Post-submit InstantPay status check skipped user={}: {}", userId, ex.getMessage());
            }

            boolean fullKycNow = "FULL_KYC".equals(finalKycState);
            jdbc.update("""
                    UPDATE biometric_kyc_sessions
                       SET status = ?,
                           capture_latitude = ?, capture_longitude = ?,
                           rd_rds_id = ?, rd_rds_ver = ?, rd_dp_id = ?, rd_mi = ?, rd_srno = ?,
                           q_score = ?, nm_points = ?,
                           provider_status_code = ?, provider_message = ?,
                           submitted_at = now()
                     WHERE id = ?
                    """,
                    fullKycNow ? "SUCCESS" : "APPROVAL_PENDING",
                    lat, lng,
                    parsed.rdsId(), parsed.rdsVer(), parsed.dpId(), parsed.mi(), parsed.srno(),
                    qScoreInt, nmPointsInt,
                    ipRes.statusCode(), ipRes.message(),
                    sessionId);

            jdbc.update("""
                    UPDATE provider_pipe_activations
                       SET kyc_status = ?,
                           biometric_verified_at = CASE WHEN ? THEN COALESCE(biometric_verified_at, now()) ELSE biometric_verified_at END,
                           last_error = NULL,
                           last_synced_at = now(),
                           updated_at = now()
                     WHERE user_id = ? AND channel_id = ?
                    """, finalKycState, fullKycNow, userId, channelId);

            log.info("MERCHANT_EKYC InstantPay submitted state={} user={} session={} rdsId={} qScore={}",
                    finalKycState, userId, sessionId, parsed.rdsId(), parsed.qScore());
            return getCashActivation(me, false);
        }

        Map<String, String> biometricData26 = parsed.toEmeraldBiometricData(aadhaar12);

        EmeraldClient.BiometricSubmitResponse submitRes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP2_EKYC_SUBMIT", externalRef)) {
            submitRes = emeraldClient.submitBiometricKyc(
                    outletCode,
                    pipeCode,
                    referenceKey,
                    lat.toPlainString(),
                    lng.toPlainString(),
                    externalRef,
                    biometricData26);
        }

        if (!submitRes.success()) {
            String cleanReason = EmeraldMessageSanitizer.sanitize(
                    submitRes.message(),
                    "Biometric verification did not match. Clean your fingerprint sensor and scan again.");
            jdbc.update("""
                    UPDATE biometric_kyc_sessions
                       SET status = 'FAILED',
                           capture_latitude = ?, capture_longitude = ?,
                           rd_rds_id = ?, rd_rds_ver = ?, rd_dp_id = ?, rd_mi = ?, rd_srno = ?,
                           q_score = ?, nm_points = ?,
                           provider_status_code = ?, provider_message = ?,
                           submitted_at = now()
                     WHERE id = ?
                    """,
                    lat, lng,
                    parsed.rdsId(), parsed.rdsVer(), parsed.dpId(), parsed.mi(), parsed.srno(),
                    qScoreInt, nmPointsInt,
                    submitRes.statusCode(), submitRes.message(),
                    sessionId);
            jdbc.update("""
                    UPDATE provider_pipe_activations
                       SET last_error = ?, updated_at = now()
                     WHERE user_id = ? AND channel_id = ?
                    """, cleanReason, userId, channelId);
            log.warn("MERCHANT_EKYC failed user={} session={} code={} qScore={} rdsId={}",
                    userId, sessionId, submitRes.statusCode(), parsed.qScore(), parsed.rdsId());
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BIOMETRIC_KYC_FAILED", cleanReason);
        }

        jdbc.update("""
                UPDATE biometric_kyc_sessions
                   SET status = 'SUCCESS',
                       capture_latitude = ?, capture_longitude = ?,
                       rd_rds_id = ?, rd_rds_ver = ?, rd_dp_id = ?, rd_mi = ?, rd_srno = ?,
                       q_score = ?, nm_points = ?,
                       provider_status_code = ?, provider_message = ?,
                       submitted_at = now()
                 WHERE id = ?
                """,
                lat, lng,
                parsed.rdsId(), parsed.rdsVer(), parsed.dpId(), parsed.mi(), parsed.srno(),
                qScoreInt, nmPointsInt,
                submitRes.statusCode(), submitRes.message(),
                sessionId);

        boolean bankSynced = "SYNCED".equalsIgnoreCase(submitRes.bankSyncStatus());
        jdbc.update("""
                UPDATE provider_pipe_activations
                   SET kyc_status = 'FULL_KYC',
                       biometric_verified_at = COALESCE(biometric_verified_at, now()),
                       bank_sync_status = CASE WHEN ? THEN 'SYNCED' ELSE bank_sync_status END,
                       last_error = NULL,
                       last_synced_at = now(),
                       updated_at = now()
                 WHERE user_id = ? AND channel_id = ?
                """, bankSynced, userId, channelId);

        log.info("MERCHANT_EKYC verified FULL_KYC user={} session={} rdsId={} mi={} qScore={}",
                userId, sessionId, parsed.rdsId(), parsed.mi(), parsed.qScore());

        return getCashActivation(me, false);
    }

    public void recordDeviceEvent(
            AuthPrincipal me,
            String stage,
            Integer port,
            String status,
            String rdsId,
            String mi,
            String errCode,
            String detail,
            String requestId) {
        UUID userId = me == null ? null : me.userId();
        log.info("MERCHANT_EKYC device stage={} user={} port={} status={} rdsId={} mi={} errCode={} reqId={} detail={}",
                safeLog(stage), userId, port, safeLog(status), safeLog(rdsId), safeLog(mi),
                safeLog(errCode), safeLog(requestId), safeLog(detail));
    }

    private boolean shouldTriggerLiveSync(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.kyc_status, a.bank_sync_status, a.last_synced_at
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                 WHERE a.user_id = ?
                   AND a.enabled_for_retailer = TRUE
                   AND c.active = TRUE
                   AND c.provider_code IN ('EMERALD', 'INSTANTPAY')
                """, userId);
        if (rows.isEmpty()) {
            return false;
        }
        Instant cutoff = Instant.now().minus(LIVE_SYNC_MIN_INTERVAL);
        for (Map<String, Object> r : rows) {
            boolean needsSync = !"FULL_KYC".equalsIgnoreCase(str(r.get("kyc_status")))
                    || !"SYNCED".equalsIgnoreCase(str(r.get("bank_sync_status")));
            Timestamp last = (Timestamp) r.get("last_synced_at");
            if (needsSync && (last == null || last.toInstant().isBefore(cutoff))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> resolveRetailerChannel(UUID userId, String channelKeyRaw) {
        String key = channelKeyRaw == null ? "" : channelKeyRaw.trim();
        List<Map<String, Object>> rows;
        if (key.isEmpty() || "default".equalsIgnoreCase(key) || "primary".equalsIgnoreCase(key)) {
            rows = jdbc.queryForList("""
                    SELECT a.channel_id, c.channel_key, c.provider_code, c.pipe_code, c.config_json::text AS config_json,
                           m.outlet_code, a.kyc_status
                      FROM provider_pipe_activations a
                      JOIN service_provider_channels c ON c.id = a.channel_id
                      JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                     WHERE a.user_id = ? AND a.enabled_for_retailer = TRUE AND c.active = TRUE
                     ORDER BY (a.kyc_status <> 'FULL_KYC') DESC, c.priority_order ASC
                     LIMIT 1
                    """, userId);
        } else {
            rows = jdbc.queryForList("""
                    SELECT a.channel_id, c.channel_key, c.provider_code, c.pipe_code, c.config_json::text AS config_json,
                           m.outlet_code, a.kyc_status
                      FROM provider_pipe_activations a
                      JOIN service_provider_channels c ON c.id = a.channel_id
                      JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                     WHERE a.user_id = ? AND c.channel_key = ? AND a.enabled_for_retailer = TRUE AND c.active = TRUE
                     LIMIT 1
                    """, userId, key);
        }
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "TERMINAL_NOT_PROVISIONED",
                    "Your merchant banking terminal is being provisioned. Please try again shortly or contact support.");
        }
        return rows.get(0);
    }

    private void saveRetailerAadhaar(UUID userId, String rawAadhaar) {
        List<Map<String, Object>> sigRows = jdbc.queryForList("""
                SELECT s.id, COALESCE(s.aadhaar_last4, '') AS aadhaar_last4
                  FROM business_signatories s
                  JOIN agent_kyc_profiles p ON p.user_id = s.user_id
                 WHERE s.user_id = ?
                   AND s.active = TRUE
                   AND s.is_self = FALSE
                   AND p.business_type = 'COMPANY'
                 ORDER BY s.created_at DESC
                 LIMIT 1
                """, userId);
        if (!sigRows.isEmpty()) {
            UUID sigId = (UUID) sigRows.get(0).get("id");
            String sigLast4 = str(sigRows.get(0).get("aadhaar_last4"));
            String validSig = AadhaarValidator.validateAndNormalize(rawAadhaar, sigLast4);
            jdbc.update("""
                    UPDATE business_signatories
                       SET aadhaar_enc = ?,
                           aadhaar_last4 = CASE WHEN aadhaar_last4 IS NULL OR aadhaar_last4 = '' THEN ? ELSE aadhaar_last4 END,
                           updated_at = now()
                     WHERE id = ? AND user_id = ?
                    """, fieldCipher.encrypt(validSig), validSig.substring(8), sigId, userId);
            return;
        }
        String last4 = jdbc.queryForObject(
                "SELECT COALESCE(aadhaar_last4, '') FROM agent_kyc_profiles WHERE user_id = ?",
                String.class, userId);
        String valid = AadhaarValidator.validateAndNormalize(rawAadhaar, last4);
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET aadhaar_enc = ?,
                       aadhaar_last4 = CASE WHEN aadhaar_last4 IS NULL OR aadhaar_last4 = '' THEN ? ELSE aadhaar_last4 END,
                       updated_at = now()
                 WHERE user_id = ?
                """, fieldCipher.encrypt(valid), valid.substring(8), userId);
    }

    private String requireStoredAadhaar(UUID userId) {
        List<String> sigRows = jdbc.queryForList("""
                SELECT s.aadhaar_enc
                  FROM business_signatories s
                  JOIN agent_kyc_profiles p ON p.user_id = s.user_id
                 WHERE s.user_id = ?
                   AND s.active = TRUE
                   AND s.is_self = FALSE
                   AND p.business_type = 'COMPANY'
                   AND s.aadhaar_enc IS NOT NULL
                   AND s.aadhaar_enc <> ''
                 ORDER BY s.created_at DESC
                 LIMIT 1
                """, String.class, userId);
        if (!sigRows.isEmpty()) {
            String plainSig = fieldCipher.decrypt(sigRows.get(0));
            if (plainSig != null && plainSig.matches("[0-9]{12}")) {
                return plainSig;
            }
        }
        List<String> rows = jdbc.queryForList(
                "SELECT aadhaar_enc FROM agent_kyc_profiles WHERE user_id = ?",
                String.class, userId);
        if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).isBlank()) {
            String plain = fieldCipher.decrypt(rows.get(0));
            if (plain != null && plain.matches("[0-9]{12}")) {
                return plain;
            }
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AADHAAR_REQUIRED",
                "Confirm your 12-digit Aadhaar number to proceed with biometric verification.");
    }

    private Map<String, Object> loadKycRow(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT COALESCE(p.business_verified, FALSE) AS business_verified,
                       p.business_status,
                       p.agreement_status,
                       COALESCE(
                           (SELECT s.aadhaar_last4 FROM business_signatories s
                             WHERE s.user_id = p.user_id AND s.active = TRUE AND s.is_self = FALSE AND p.business_type = 'COMPANY'
                             ORDER BY s.created_at DESC LIMIT 1),
                           p.aadhaar_last4
                       ) AS aadhaar_last4,
                       (
                           (p.aadhaar_enc IS NOT NULL AND p.aadhaar_enc <> '')
                           OR EXISTS (
                               SELECT 1 FROM business_signatories s
                                WHERE s.user_id = p.user_id AND s.active = TRUE AND s.is_self = FALSE
                                  AND p.business_type = 'COMPANY' AND s.aadhaar_enc IS NOT NULL AND s.aadhaar_enc <> ''
                           )
                       ) AS has_full_aadhaar,
                       p.work_latitude,
                       p.work_longitude
                  FROM agent_kyc_profiles p
                 WHERE p.user_id = ?
                """, userId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        return rows.get(0);
    }

    private BigDecimal parseCoordinate(String raw, boolean latitude, UUID userId) {
        if (raw != null && !raw.isBlank()) {
            try {
                BigDecimal val = new BigDecimal(raw.trim());
                double d = val.doubleValue();
                if ((latitude && d >= -90 && d <= 90) || (!latitude && d >= -180 && d <= 180)) {
                    return val;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String col = latitude ? "work_latitude" : "work_longitude";
        List<BigDecimal> saved = jdbc.queryForList(
                "SELECT " + col + " FROM agent_kyc_profiles WHERE user_id = ? AND " + col + " IS NOT NULL",
                BigDecimal.class, userId);
        if (!saved.isEmpty() && saved.get(0) != null) {
            return saved.get(0);
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                "Allow location access in your browser to complete biometric verification.");
    }

    private static void requirePartner(AuthPrincipal me) {
        if (me == null || (!"RETAILER".equals(me.userType()) && !"MASTER_DISTRIBUTOR".equals(me.userType()))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a partner account can access terminal activation");
        }
    }

    private static Integer parseIntOrNull(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safeLog(String val) {
        if (val == null) return "-";
        String clean = val.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}

