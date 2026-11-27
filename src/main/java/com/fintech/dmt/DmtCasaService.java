package com.fintech.dmt;

import com.fintech.biometric.RdPidXmlParser;
import com.fintech.emerald.EmeraldMessageSanitizer;
import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.instantpay.InstantPayDmtAdapter;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.tx.ShortTransactions;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ProviderDispatch;
import com.fintech.provider.ProviderRegistry;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.verification.AadhaarValidator;
import com.fintech.verification.VerificationGate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Domestic Money Transfer (DMT) domain service orchestrating:
 * <ul>
 *   <li>Strict Merchant {@code FULL_KYC} + Business/Agreement verification</li>
 *   <li>Remitter Profile lookup &amp; permanent system-wide one-time Remitter eKYC ({@code ₹0} fee)</li>
 *   <li>OTP-verified Beneficiary Registration &amp; Deletion</li>
 *   <li>3.0 km shop geo-fencing, Admin/property surcharge quotes, {@code ShortTransactions} holds, and {@code IMPS}/{@code NEFT} execution</li>
 *   <li>Webhook-free {@code POST /reports/txnStatus} status check &amp; post-settlement reconfirmation</li>
 * </ul>
 */
@Service
public class DmtCasaService {

    private static final Logger log = LoggerFactory.getLogger(DmtCasaService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter IPAY_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration SESSION_TTL = Duration.ofMinutes(15);

    private record GeoCheck(BigDecimal lat, BigDecimal lng, BigDecimal distanceKm) {}

    private final JdbcTemplate jdbc;
    private final VerificationGate verification;
    private final PlatformServiceGate services;
    private final ProviderDispatch dispatch;
    private final ProviderRegistry providers;
    private final DmtBankListService bankList;
    private final DmtCasaLedger ledger;
    private final FieldCipher fieldCipher;
    private final ShortTransactions shortTx;
    private final DmtProperties dmtProps;
    private final String rdEnv;

    @Autowired
    public DmtCasaService(
            JdbcTemplate jdbc,
            VerificationGate verification,
            PlatformServiceGate services,
            ProviderDispatch dispatch,
            ProviderRegistry providers,
            DmtBankListService bankList,
            DmtCasaLedger ledger,
            FieldCipher fieldCipher,
            ShortTransactions shortTx,
            DmtProperties dmtProps,
            @Value("${app.biometric.rd-env:P}") String rdEnv) {
        this.jdbc = jdbc;
        this.verification = verification;
        this.services = services;
        this.dispatch = dispatch;
        this.providers = providers;
        this.bankList = bankList;
        this.ledger = ledger;
        this.fieldCipher = fieldCipher;
        this.shortTx = shortTx;
        this.dmtProps = dmtProps != null ? dmtProps : new DmtProperties();
        this.rdEnv = (rdEnv == null || rdEnv.isBlank()) ? "P" : rdEnv.trim().toUpperCase(Locale.ROOT);
    }

    // =========================================================================
    // 1. Remitter Lookup & One-Time Remitter Onboarding (Register -> OTP -> eKYC)
    // =========================================================================

    public Map<String, Object> queryRemitter(AuthPrincipal me, String mobileRaw) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        String mobile = validateMobile10(mobileRaw);

        ProviderModels.DmtRemitterProfile profile;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_REMITTER_LOOKUP", null)) {
            profile = route.port().fetchRemitterProfile(route.channel(), mobile);
        }

        if ("ERROR".equalsIgnoreCase(profile.status())) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    profile.message(),
                    "Unable to check remitter profile right now. Please try again.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_PROFILE_ERROR", msg);
        }

        return shortTx.required(() -> syncSenderAndBuildView(me.userId(), route.channel(), mobile, profile));
    }

    public Map<String, Object> registerRemitter(AuthPrincipal me, String mobileRaw, String aadhaarRaw) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        String mobile = validateMobile10(mobileRaw);
        String aadhaar12 = AadhaarValidator.validateAndNormalize(aadhaarRaw, null);
        String last4 = aadhaar12.substring(8);

        Map<String, Object> sender = findOrCreateSender(me.userId(), route.channel(), mobile);
        if (Boolean.TRUE.equals(sender.get("ekyc_verified")) && "ACTIVE".equalsIgnoreCase(str(sender.get("provider_status")))) {
            return queryRemitter(me, mobile);
        }

        String profileRef = resolveFreshProfileRef(me.userId(), route, mobile, sender);
        String pidWadh = firstNonBlank(str(sender.get("pid_wadh")), InstantPayDmtAdapter.DEFAULT_EKYC_WADH);

        ProviderModels.DmtOtpChallenge res;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_REMITTER_REGISTER", null)) {
            res = route.port().registerRemitter(route.channel(), mobile, aadhaar12, profileRef);
        }
        if (!res.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    res.message(), "Could not send registration OTP to the remitter's mobile number.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_REMITTER_REGISTER_FAILED", msg);
        }

        UUID senderId = (UUID) sender.get("id");
        UUID sessionId = UUID.randomUUID();
        String externalRef = "DMTONB" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        Instant expiresAt = parseValidityOrDefault(res.validity(), SESSION_TTL);
        String stage = res.otpRequired() ? "AWAITING_OTP" : "AWAITING_EKYC";

        shortTx.required(() -> {
            jdbc.update("""
                    UPDATE dmt_senders
                       SET ovd_type = 'AADHAAR',
                           ovd_last4 = ?,
                           provider_status = ?,
                           profile_reference_key = COALESCE(?, profile_reference_key),
                           updated_at = now()
                     WHERE id = ?
                    """, last4, res.otpRequired() ? "REGISTRATION_REQUIRED" : "KYC_REQUIRED",
                    blankToNull(res.referenceKey()), senderId);

            jdbc.update("""
                    INSERT INTO dmt_remitter_onboarding_sessions
                        (id, sender_id, agent_user_id, channel_id, outlet_code, external_ref,
                         mobile, aadhaar_last4, stage, reference_key, pid_wadh,
                         provider_status_code, provider_message, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    sessionId, senderId, me.userId(), route.channel().channelId(), route.channel().outletCode(),
                    externalRef, mobile, last4, stage, res.referenceKey(), pidWadh,
                    res.statusCode(), res.message(), Timestamp.from(expiresAt));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("senderId", senderId);
        out.put("mobile", mobile);
        out.put("otpRequired", res.otpRequired());
        out.put("status", res.otpRequired() ? "OTP_REQUIRED" : "KYC_REQUIRED");
        out.put("wadh", pidWadh);
        out.put("rdEnv", rdEnv);
        out.put("validity", res.validity());
        out.put("message", EmeraldMessageSanitizer.sanitize(res.message(),
                res.otpRequired()
                        ? "Verification OTP sent to customer mobile +91 " + mobile + "."
                        : "Mobile verified. Proceed to one-time fingerprint eKYC."));
        return out;
    }

    public Map<String, Object> verifyRemitterRegistration(AuthPrincipal me, UUID sessionId, String otpRaw) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        String otp = validateOtp(otpRaw);

        Map<String, Object> session = loadRemitterOnboardingSession(me.userId(), sessionId);
        if (!"AWAITING_OTP".equalsIgnoreCase(str(session.get("stage")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_INVALID_STAGE",
                    "This registration OTP session is no longer awaiting verification.");
        }
        String mobile = str(session.get("mobile"));
        String refKey = str(session.get("reference_key"));
        String wadh = firstNonBlank(str(session.get("pid_wadh")), InstantPayDmtAdapter.DEFAULT_EKYC_WADH);

        ProviderModels.DmtOtpChallenge res;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_REMITTER_OTP_VERIFY", str(session.get("external_ref")))) {
            res = route.port().verifyRemitterRegistration(route.channel(), mobile, otp, refKey);
        }
        if (!res.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    res.message(), "Incorrect or expired OTP. Please verify the code with the customer.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_REMITTER_OTP_FAILED", msg);
        }

        if ("ACTIVE".equalsIgnoreCase(res.status())) {
            shortTx.required(() -> jdbc.update("""
                    UPDATE dmt_remitter_onboarding_sessions
                       SET stage = 'COMPLETED', otp_verified_at = now(), updated_at = now()
                     WHERE id = ?
                    """, sessionId));
            return queryRemitter(me, mobile);
        }

        Instant nextExp = parseValidityOrDefault(res.validity(), SESSION_TTL);
        shortTx.required(() -> {
            jdbc.update("""
                    UPDATE dmt_remitter_onboarding_sessions
                       SET stage = 'AWAITING_EKYC',
                           otp_verified_at = now(),
                           reference_key = ?,
                           provider_status_code = ?,
                           provider_message = ?,
                           expires_at = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, res.referenceKey(), res.statusCode(), res.message(), Timestamp.from(nextExp), sessionId);

            jdbc.update("""
                    UPDATE dmt_senders
                       SET mobile_verified_at = COALESCE(mobile_verified_at, now()),
                           provider_status = 'KYC_REQUIRED',
                           profile_reference_key = ?,
                           profile_expires_at = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, res.referenceKey(), Timestamp.from(nextExp), session.get("sender_id"));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("senderId", session.get("sender_id"));
        out.put("mobile", mobile);
        out.put("status", "KYC_REQUIRED");
        out.put("wadh", wadh);
        out.put("rdEnv", rdEnv);
        out.put("validity", res.validity());
        out.put("message", "Mobile verified! Scan the customer's fingerprint once to complete Remitter eKYC.");
        return out;
    }

    public Map<String, Object> submitRemitterEkyc(
            AuthPrincipal me,
            String mobileRaw,
            UUID sessionId,
            String pidXml,
            String captureTypeRaw,
            String latitudeRaw,
            String longitudeRaw,
            boolean consent) {
        if (!consent) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CONSENT_REQUIRED",
                    "Customer Aadhaar eKYC consent is required before fingerprint capture.");
        }
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        GeoCheck geo = resolveAndVerifyGeoFence(me.userId(), latitudeRaw, longitudeRaw);
        RdPidXmlParser.ParsedPidData parsedPid = RdPidXmlParser.parse(pidXml);

        String mobile;
        String referenceKey;
        UUID senderId;
        String externalRef = "DMTEKYC" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));

        if (sessionId != null) {
            Map<String, Object> session = loadRemitterOnboardingSession(me.userId(), sessionId);
            mobile = str(session.get("mobile"));
            referenceKey = str(session.get("reference_key"));
            senderId = (UUID) session.get("sender_id");
            externalRef = str(session.get("external_ref"));
        } else {
            mobile = validateMobile10(mobileRaw);
            Map<String, Object> sender = findOrCreateSender(me.userId(), route.channel(), mobile);
            senderId = (UUID) sender.get("id");
            referenceKey = resolveFreshProfileRef(me.userId(), route, mobile, sender);
        }

        if (referenceKey.isBlank()) {
            referenceKey = resolveFreshProfileRef(me.userId(), route, mobile, null);
        }

        String captureType = (captureTypeRaw == null || captureTypeRaw.isBlank())
                ? "FINGER"
                : captureTypeRaw.trim().toUpperCase(Locale.ROOT);

        ProviderModels.DmtRemitterEkycResult kycRes;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_REMITTER_EKYC", externalRef)) {
            kycRes = route.port().submitRemitterEkyc(route.channel(), new ProviderModels.DmtRemitterEkycRequest(
                    mobile,
                    referenceKey,
                    geo.lat().toPlainString(),
                    geo.lng().toPlainString(),
                    externalRef,
                    captureType,
                    parsedPid));
        }

        Integer qScore = parseIntOrNull(parsedPid.qScore());
        UUID finalSenderId = senderId;
        String finalExtRef = externalRef;

        if (!kycRes.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    kycRes.message(),
                    "Remitter fingerprint eKYC did not match. Clean the scanner sensor and try again.");
            shortTx.required(() -> {
                if (sessionId != null) {
                    jdbc.update("""
                            UPDATE dmt_remitter_onboarding_sessions
                               SET capture_latitude = ?, capture_longitude = ?, distance_from_shop_km = ?,
                                   rd_rds_id = ?, rd_mi = ?, rd_srno = ?, q_score = ?,
                                   provider_status_code = ?, provider_message = ?, updated_at = now()
                             WHERE id = ?
                            """,
                            geo.lat(), geo.lng(), geo.distanceKm(),
                            parsedPid.rdsId(), parsedPid.mi(), parsedPid.srno(), qScore,
                            kycRes.statusCode(), kycRes.message(), sessionId);
                }
            });
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_REMITTER_EKYC_FAILED", msg);
        }

        // Persist permanent one-time eKYC completion across system rows for this mobile
        shortTx.required(() -> {
            if (sessionId != null) {
                jdbc.update("""
                        UPDATE dmt_remitter_onboarding_sessions
                           SET stage = 'COMPLETED',
                               ekyc_order_id = ?,
                               ekyc_pool_amount = ?,
                               capture_latitude = ?, capture_longitude = ?, distance_from_shop_km = ?,
                               rd_rds_id = ?, rd_mi = ?, rd_srno = ?, q_score = ?,
                               provider_status_code = ?, provider_message = ?, updated_at = now()
                         WHERE id = ?
                        """,
                        kycRes.orderId(), kycRes.poolAmount(),
                        geo.lat(), geo.lng(), geo.distanceKm(),
                        parsedPid.rdsId(), parsedPid.mi(), parsedPid.srno(), qScore,
                        kycRes.statusCode(), kycRes.message(), sessionId);
            } else {
                jdbc.update("""
                        INSERT INTO dmt_remitter_onboarding_sessions
                            (id, sender_id, agent_user_id, channel_id, outlet_code, external_ref,
                             mobile, stage, ekyc_order_id, ekyc_pool_amount,
                             capture_latitude, capture_longitude, distance_from_shop_km,
                             rd_rds_id, rd_mi, rd_srno, q_score, provider_status_code, provider_message, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                        """,
                        UUID.randomUUID(), finalSenderId, me.userId(), route.channel().channelId(),
                        route.channel().outletCode(), finalExtRef, mobile,
                        kycRes.orderId(), kycRes.poolAmount(),
                        geo.lat(), geo.lng(), geo.distanceKm(),
                        parsedPid.rdsId(), parsedPid.mi(), parsedPid.srno(), qScore,
                        kycRes.statusCode(), kycRes.message());
            }

            jdbc.update("""
                    UPDATE dmt_senders
                       SET kyc_level = 'FULL_KYC',
                           ekyc_verified = TRUE,
                           ekyc_verified_at = COALESCE(ekyc_verified_at, now()),
                           ovd_captured_at = COALESCE(ovd_captured_at, now()),
                           provider_status = 'ACTIVE',
                           updated_at = now()
                     WHERE mobile = ?
                    """, mobile);
        });

        // Immediately fetch the newly activated remitter profile (limits, referenceKey, beneficiaries)
        return queryRemitter(me, mobile);
    }

    // =========================================================================
    // 2. OTP-Verified Beneficiary Management (List, Add, Delete)
    // =========================================================================

    public List<Map<String, Object>> beneficiaries(AuthPrincipal me, UUID senderId) {
        requireDmtReady(me);
        assertSenderOwned(senderId, me.userId());
        return listBeneficiariesInternal(senderId);
    }

    public Map<String, Object> sendBeneOtp(
            AuthPrincipal me,
            UUID senderId,
            String nameRaw,
            String accountNumberRaw,
            String ifscRaw,
            String bankIdRaw,
            String beneficiaryMobileRaw) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        Map<String, Object> sender = loadOwnedSender(senderId, me.userId());
        String remitterMobile = str(sender.get("mobile"));

        String cleanName = nameRaw == null ? "" : nameRaw.trim().replaceAll("\\s+", " ");
        if (cleanName.length() < 2) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BENE_NAME",
                    "Enter the beneficiary's full name.");
        }
        String cleanAcc = accountNumberRaw == null ? "" : accountNumberRaw.replaceAll("\\s+", "");
        if (!cleanAcc.matches("[0-9A-Za-z]{6,26}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCOUNT_NUMBER",
                    "Enter a valid bank account number.");
        }
        String cleanIfsc = DmtCasaTexts.ifsc(ifscRaw);
        if (!cleanIfsc.matches("^[A-Z]{4}0[A-Z0-9]{6}$")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IFSC",
                    "Enter a valid 11-character IFSC code (5th character must be 0).");
        }
        Map<String, Object> bankRow = bankList.findActiveByBankId(bankIdRaw);
        if (bankRow == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_INACTIVE",
                    "Choose an active recipient bank from the DMT bank list.");
        }
        String bankName = str(bankRow.get("bank_name"));
        String cleanBeneMobile = (beneficiaryMobileRaw == null || beneficiaryMobileRaw.isBlank())
                ? remitterMobile
                : validateMobile10(beneficiaryMobileRaw);

        ProviderModels.DmtOtpChallenge res;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_BENE_REGISTER", null)) {
            res = route.port().registerBeneficiary(route.channel(), new ProviderModels.DmtBeneRegisterRequest(
                    remitterMobile, cleanBeneMobile, cleanAcc, cleanIfsc, bankIdRaw.trim(), cleanName));
        }
        if (!res.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    res.message(), "Could not initiate beneficiary registration with the bank.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_BENE_REGISTER_FAILED", msg);
        }

        if (!res.otpRequired()) {
            Map<String, Object> created = shortTx.required(() -> upsertBeneficiaryRecord(
                    senderId,
                    res.beneficiaryId(),
                    cleanName,
                    cleanAcc,
                    cleanIfsc,
                    bankIdRaw.trim(),
                    bankName,
                    cleanBeneMobile,
                    true));
            refreshSenderProfileQuietly(me.userId(), route, remitterMobile);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("otpRequired", false);
            out.put("beneficiary", created);
            out.put("message", "Beneficiary added and verified.");
            return out;
        }

        UUID sessionId = UUID.randomUUID();
        Instant exp = parseValidityOrDefault(res.validity(), SESSION_TTL);
        String encAcc = fieldCipher.encrypt(cleanAcc);
        shortTx.required(() -> jdbc.update("""
                INSERT INTO dmt_bene_otp_sessions
                    (id, sender_id, agent_user_id, channel_id, outlet_code, action,
                     name, bank_id, bank_name, account_number_enc, ifsc,
                     beneficiary_mobile, provider_bene_id, stateresp, expires_at)
                VALUES (?, ?, ?, ?, ?, 'REGISTER', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, senderId, me.userId(), route.channel().channelId(), route.channel().outletCode(),
                cleanName, bankIdRaw.trim(), bankName, encAcc, cleanIfsc,
                cleanBeneMobile, res.beneficiaryId(), res.referenceKey(), Timestamp.from(exp)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("otpRequired", true);
        out.put("sessionId", sessionId);
        out.put("validity", res.validity());
        out.put("message", EmeraldMessageSanitizer.sanitize(res.message(),
                "OTP sent to customer mobile +91 " + remitterMobile + " to confirm beneficiary."));
        return out;
    }

    public Map<String, Object> addBeneficiary(AuthPrincipal me, UUID senderId, UUID sessionId, String otpRaw) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        Map<String, Object> sender = loadOwnedSender(senderId, me.userId());
        String otp = validateOtp(otpRaw);
        Map<String, Object> session = loadBeneOtpSession(me.userId(), sessionId, "REGISTER");
        if (!senderId.equals(session.get("sender_id"))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "SENDER_MISMATCH", "Session does not belong to this remitter.");
        }

        String remitterMobile = str(sender.get("mobile"));
        String providerBeneId = str(session.get("provider_bene_id"));
        String refKey = str(session.get("stateresp"));

        ProviderModels.DmtOtpChallenge res;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_BENE_VERIFY", null)) {
            res = route.port().verifyBeneficiaryRegistration(route.channel(), remitterMobile, providerBeneId, otp, refKey);
        }
        if (!res.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    res.message(), "Incorrect or expired beneficiary OTP.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_BENE_OTP_FAILED", msg);
        }

        String plainAcc = fieldCipher.decrypt(str(session.get("account_number_enc")));
        String finalBeneId = firstNonBlank(res.beneficiaryId(), providerBeneId);
        Map<String, Object> created = shortTx.required(() -> {
            jdbc.update("UPDATE dmt_bene_otp_sessions SET consumed_at = now() WHERE id = ?", sessionId);
            return upsertBeneficiaryRecord(
                    senderId,
                    finalBeneId,
                    str(session.get("name")),
                    plainAcc,
                    str(session.get("ifsc")),
                    str(session.get("bank_id")),
                    str(session.get("bank_name")),
                    str(session.get("beneficiary_mobile")),
                    true);
        });

        refreshSenderProfileQuietly(me.userId(), route, remitterMobile);
        return created;
    }

    public Map<String, Object> initiateDeleteBeneficiary(AuthPrincipal me, UUID beneficiaryId) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        Map<String, Object> bene = loadOwnedBeneficiary(beneficiaryId, me.userId());
        String providerBeneId = str(bene.get("provider_bene_id"));
        String remitterMobile = str(bene.get("remitter_mobile"));
        UUID senderId = (UUID) bene.get("sender_id");

        if (providerBeneId.isEmpty()) {
            shortTx.required(() -> jdbc.update(
                    "UPDATE dmt_beneficiaries SET deleted_at = now(), updated_at = now() WHERE id = ?", beneficiaryId));
            return Map.of("otpRequired", false, "deleted", true, "message", "Beneficiary removed.");
        }

        ProviderModels.DmtOtpChallenge res;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_BENE_DELETE", null)) {
            res = route.port().deleteBeneficiary(route.channel(), remitterMobile, providerBeneId);
        }
        if (!res.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    res.message(), "Could not initiate beneficiary deletion.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_BENE_DELETE_FAILED", msg);
        }

        if (!res.otpRequired()) {
            shortTx.required(() -> jdbc.update(
                    "UPDATE dmt_beneficiaries SET deleted_at = now(), updated_at = now() WHERE id = ?", beneficiaryId));
            return Map.of("otpRequired", false, "deleted", true, "message", "Beneficiary deleted.");
        }

        UUID sessionId = UUID.randomUUID();
        Instant exp = parseValidityOrDefault(res.validity(), SESSION_TTL);
        shortTx.required(() -> jdbc.update("""
                INSERT INTO dmt_bene_otp_sessions
                    (id, sender_id, agent_user_id, channel_id, outlet_code, action,
                     beneficiary_id, provider_bene_id, stateresp, expires_at)
                VALUES (?, ?, ?, ?, ?, 'DELETE', ?, ?, ?, ?)
                """,
                sessionId, senderId, me.userId(), route.channel().channelId(), route.channel().outletCode(),
                beneficiaryId, providerBeneId, res.referenceKey(), Timestamp.from(exp)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("otpRequired", true);
        out.put("deleted", false);
        out.put("sessionId", sessionId);
        out.put("validity", res.validity());
        out.put("message", EmeraldMessageSanitizer.sanitize(res.message(),
                "OTP sent to customer mobile +91 " + remitterMobile + " to confirm deletion."));
        return out;
    }

    public Map<String, Object> confirmDeleteBeneficiary(AuthPrincipal me, UUID beneficiaryId, UUID sessionId, String otpRaw) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        Map<String, Object> bene = loadOwnedBeneficiary(beneficiaryId, me.userId());
        String otp = validateOtp(otpRaw);
        Map<String, Object> session = loadBeneOtpSession(me.userId(), sessionId, "DELETE");
        if (!beneficiaryId.equals(session.get("beneficiary_id"))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "BENEFICIARY_MISMATCH",
                    "Delete OTP session does not match the selected beneficiary.");
        }

        String remitterMobile = str(bene.get("remitter_mobile"));
        String providerBeneId = str(session.get("provider_bene_id"));
        String refKey = str(session.get("stateresp"));

        ProviderModels.DmtOtpChallenge res;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_BENE_DELETE_VERIFY", null)) {
            res = route.port().verifyBeneficiaryDeletion(route.channel(), remitterMobile, providerBeneId, otp, refKey);
        }
        if (!res.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    res.message(), "Incorrect or expired OTP for beneficiary deletion.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_BENE_DELETE_OTP_FAILED", msg);
        }

        shortTx.required(() -> {
            jdbc.update("UPDATE dmt_bene_otp_sessions SET consumed_at = now() WHERE id = ?", sessionId);
            jdbc.update("UPDATE dmt_beneficiaries SET deleted_at = now(), updated_at = now() WHERE id = ?", beneficiaryId);
        });
        return Map.of("deleted", true, "beneficiaryId", beneficiaryId, "message", "Beneficiary deleted.");
    }

    public Map<String, Object> deleteBeneficiary(AuthPrincipal me, UUID beneficiaryId) {
        return initiateDeleteBeneficiary(me, beneficiaryId);
    }

    // =========================================================================
    // 3. Transfer OTP, Execution & Webhook-Free Status Reconfirmation
    // =========================================================================

    public Map<String, Object> sendTxnOtp(
            AuthPrincipal me,
            UUID senderId,
            UUID beneficiaryId,
            BigDecimal rawAmount,
            String transferModeRaw) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        Map<String, Object> sender = loadOwnedSender(senderId, me.userId());
        if (!"ACTIVE".equalsIgnoreCase(str(sender.get("provider_status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REMITTER_NOT_ACTIVE",
                    "Complete one-time Remitter KYC before initiating a money transfer.");
        }
        Map<String, Object> bene = loadOwnedBeneficiary(beneficiaryId, me.userId());
        if (!senderId.equals(bene.get("sender_id"))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "BENEFICIARY_MISMATCH",
                    "Selected beneficiary does not belong to this remitter.");
        }
        if (str(bene.get("account_number_enc")).isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BENEFICIARY_ACCOUNT_MISSING",
                    "Please re-add this beneficiary account to initiate a transfer.");
        }

        BigDecimal amount = validateTransferAmount(rawAmount, sender);
        String mode = validateTransferMode(transferModeRaw, sender, bene);

        // Pre-validate retailer MAIN wallet balance (amount + surcharge) before sending SMS OTP
        DmtCasaLedger.Quote quote = shortTx.required(() -> ledger.quote(me.userId(), amount));

        String remitterMobile = str(sender.get("mobile"));
        String profileRef = resolveFreshProfileRef(me.userId(), route, remitterMobile, sender);

        ProviderModels.DmtOtpChallenge otpRes;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_TXN_OTP", null)) {
            otpRes = route.port().generateTransactionOtp(route.channel(), remitterMobile, amount, profileRef);
        }

        // Transparent recovery if InstantPay expired the profile referenceKey early
        if (!otpRes.success() && isStaleReferenceError(otpRes.message(), otpRes.actCode())) {
            String refreshedRef = resolveFreshProfileRef(me.userId(), route, remitterMobile, null);
            try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_TXN_OTP_RETRY", null)) {
                otpRes = route.port().generateTransactionOtp(route.channel(), remitterMobile, amount, refreshedRef);
            }
        }

        if (!otpRes.success() || otpRes.referenceKey().isBlank()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    otpRes.message(), "Could not generate transfer OTP. Please retry.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_TXN_OTP_FAILED", msg);
        }

        UUID sessionId = UUID.randomUUID();
        String refSeq = ledger.nextReference();
        Instant exp = parseValidityOrDefault(otpRes.validity(), Duration.ofMinutes(10));
        String otpRefKey = otpRes.referenceKey();

        shortTx.required(() -> jdbc.update("""
                INSERT INTO dmt_txn_otp_sessions
                    (id, sender_id, agent_user_id, channel_id, outlet_code,
                     beneficiary_id, provider_bene_id, amount, fee, txn_type,
                     reference_id, stateresp, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, senderId, me.userId(), route.channel().channelId(), route.channel().outletCode(),
                beneficiaryId, blankToNull(str(bene.get("provider_bene_id"))),
                amount, quote.fee(), mode, refSeq, otpRefKey, Timestamp.from(exp)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("amount", amount);
        out.put("fee", quote.fee());
        out.put("total", quote.total());
        out.put("transferMode", mode);
        out.put("otpRequired", otpRes.otpRequired());
        out.put("validity", otpRes.validity());
        out.put("message", EmeraldMessageSanitizer.sanitize(otpRes.message(),
                "Transfer OTP sent to customer mobile +91 " + remitterMobile + "."));
        return out;
    }

    public Map<String, Object> process(
            AuthPrincipal me,
            UUID sessionId,
            String otpRaw,
            String latitudeRaw,
            String longitudeRaw,
            String idemKey,
            String canonical) {
        ProviderDispatch.RoutedDmt route = requireDmtReady(me);
        String otp = validateOtp(otpRaw);
        String cleanIdem = com.fintech.platform.idempotency.ExternalRefs.requireKey(idemKey);
        GeoCheck geo = resolveAndVerifyGeoFence(me.userId(), latitudeRaw, longitudeRaw);

        DmtCasaLedger.HeldTransfer hold = shortTx.required(() -> ledger.placeTransferHold(
                me.userId(), sessionId, route.channel(), geo.lat(), geo.lng(), geo.distanceKm(), cleanIdem, canonical));
        if (hold.replay()) {
            return hold.replayBody();
        }

        ProviderModels.DmtTransferResult txRes;
        try (var ignored = EmeraldTraceContext.open(me.userId(), "DMT_TRANSACTION", hold.externalRef())) {
            txRes = route.port().executeTransfer(route.channel(), new ProviderModels.DmtTransferRequest(
                    hold.remitterMobile(),
                    hold.accountNumber(),
                    hold.ifsc(),
                    hold.transferMode(),
                    hold.amount(),
                    geo.lat().toPlainString(),
                    geo.lng().toPlainString(),
                    hold.referenceKey(),
                    otp,
                    hold.externalRef()));
        } catch (RuntimeException ex) {
            log.warn("DMT provider call inconclusive txnId={} ref={}: {}", hold.txnId(), hold.externalRef(), ex.getMessage());
            txRes = new ProviderModels.DmtTransferResult(
                    ProviderModels.DmtTransferOutcome.PENDING,
                    "TIMEOUT",
                    "",
                    "Transfer status is pending bank confirmation. Use Check Status to reconfirm.",
                    "",
                    "",
                    null);
        }

        ProviderModels.DmtTransferResult finalRes = txRes;
        DmtCasaLedger.AppliedTransfer applied = shortTx.required(() -> ledger.applySyncTransferOutcome(hold, finalRes));
        if (applied.declineReason() != null) {
            throw ApiException.of(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    applied.errorCode() != null ? applied.errorCode() : "DMT_TRANSFER_DECLINED",
                    applied.declineReason());
        }
        return applied.view();
    }

    public Map<String, Object> status(AuthPrincipal me, UUID txnId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        return ledger.viewOwned(txnId, me.userId());
    }

    public Map<String, Object> refreshStatus(AuthPrincipal me, UUID txnId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        ledger.viewOwned(txnId, me.userId());
        return reconcileByTransactionId(txnId, false);
    }

    public List<Map<String, Object>> history(AuthPrincipal me, Integer limit) {
        services.requireEnabled(PlatformServiceGate.DMT);
        return ledger.historyForAgent(me.userId(), limit == null ? 20 : limit);
    }

    /**
     * Webhook-free status check &amp; reconfirmation via {@code POST /reports/txnStatus}.
     * Supports both open ({@code HOLD}/{@code PENDING}) transfers and settled ({@code SUCCESS}) transfers.
     */
    public Map<String, Object> reconcileByTransactionId(UUID txnId, boolean force) {
        Map<String, Object> row = ledger.loadTransferRow(txnId);
        if (row == null) {
            return Map.of("matched", false);
        }
        String curStatus = str(row.get("status"));
        boolean open = "HOLD".equalsIgnoreCase(curStatus)
                || "PENDING".equalsIgnoreCase(curStatus)
                || "IN_PROCESS".equalsIgnoreCase(curStatus)
                || "SENT".equalsIgnoreCase(curStatus)
                || "ON_HOLD".equalsIgnoreCase(curStatus);
        if (!open && !"SUCCESS".equalsIgnoreCase(curStatus)) {
            return ledger.view(txnId);
        }

        String providerCode = firstNonBlank(str(row.get("provider_code")), "INSTANTPAY");
        String externalRef = firstNonBlank(str(row.get("external_ref")), str(row.get("reference_id")));
        String providerTxnId = str(row.get("provider_ackno"));
        Timestamp createdTs = (Timestamp) row.get("created_at");
        String txnDate = createdTs != null
                ? createdTs.toInstant().atZone(IST).toLocalDate().toString()
                : LocalDate.now(IST).toString();

        ProviderModels.DmtTransferStatus upstream;
        try (var ignored = EmeraldTraceContext.open((UUID) row.get("agent_user_id"), "DMT_STATUS_RECONFIRM", externalRef)) {
            ServiceProviderRouter.RoutedChannel ch = new ServiceProviderRouter.RoutedChannel(
                    (UUID) row.get("channel_id"),
                    "",
                    providerCode,
                    str(row.get("pipe_code")),
                    str(row.get("outlet_code")),
                    "",
                    "FULL_KYC",
                    "SYNCED",
                    "{}",
                    "");
            upstream = providers.dmt(providerCode).checkTransferStatus(ch, externalRef, providerTxnId, txnDate, force);
        } catch (Exception ex) {
            log.warn("DMT status check warning txnId={} ref={}: {}", txnId, externalRef, ex.getMessage());
            return ledger.view(txnId);
        }

        return shortTx.required(() -> ledger.applyStatusReconfirmation(txnId, upstream));
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    private ProviderDispatch.RoutedDmt requireDmtReady(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.DMT);
        verification.requireBusiness(me.userId());
        return dispatch.dmt(me.userId());
    }

    private Map<String, Object> syncSenderAndBuildView(
            UUID agentId,
            ServiceProviderRouter.RoutedChannel channel,
            String mobile,
            ProviderModels.DmtRemitterProfile profile) {
        Map<String, Object> existing = findOrCreateSender(agentId, channel, mobile);
        UUID senderId = (UUID) existing.get("id");

        boolean isActive = "ACTIVE".equalsIgnoreCase(profile.status());
        String fullName = (profile.firstName() + " " + profile.lastName()).trim();
        Instant profileExp = parseValidityOrDefault(profile.validity(), SESSION_TTL);
        String wadh = firstNonBlank(profile.pidOptionWadh(), InstantPayDmtAdapter.DEFAULT_EKYC_WADH);

        jdbc.update("""
                UPDATE dmt_senders
                   SET provider_code = ?,
                       outlet_code = ?,
                       full_name = CASE WHEN ? <> '' THEN ? ELSE full_name END,
                       first_name = CASE WHEN ? <> '' THEN ? ELSE first_name END,
                       last_name = CASE WHEN ? <> '' THEN ? ELSE last_name END,
                       city = CASE WHEN ? <> '' THEN ? ELSE city END,
                       pincode = CASE WHEN ? <> '' THEN ? ELSE pincode END,
                       kyc_level = CASE WHEN ? THEN 'FULL_KYC' ELSE kyc_level END,
                       ekyc_verified = CASE WHEN ? THEN TRUE ELSE ekyc_verified END,
                       ekyc_verified_at = CASE WHEN ? THEN COALESCE(ekyc_verified_at, now()) ELSE ekyc_verified_at END,
                       provider_status = ?,
                       provider_limit = ?,
                       per_txn_limit = ?,
                       daily_limit_max = ?,
                       daily_limit_available = ?,
                       monthly_limit_max = ?,
                       monthly_limit_available = ?,
                       profile_reference_key = CASE WHEN ? <> '' THEN ? ELSE profile_reference_key END,
                       profile_expires_at = ?,
                       pid_wadh = ?,
                       is_txn_otp_required = ?,
                       is_txn_bio_required = ?,
                       imps_allowed = ?,
                       neft_allowed = ?,
                       face_auth_available = ?,
                       provider_checked_at = now(),
                       updated_at = now()
                 WHERE id = ?
                """,
                channel.providerCode(),
                channel.outletCode(),
                fullName, fullName,
                profile.firstName(), profile.firstName(),
                profile.lastName(), profile.lastName(),
                profile.city(), profile.city(),
                profile.pincode(), profile.pincode(),
                isActive, isActive, isActive,
                profile.status(),
                profile.monthlyLimitAvailable(),
                profile.perTxnLimit(),
                profile.dailyLimitMax(),
                profile.dailyLimitAvailable(),
                profile.monthlyLimitMax(),
                profile.monthlyLimitAvailable(),
                profile.referenceKey(), profile.referenceKey(),
                Timestamp.from(profileExp),
                wadh,
                profile.txnOtpRequired(),
                profile.txnBioRequired(),
                profile.impsAllowed(),
                profile.neftAllowed(),
                profile.faceAuthAvailable(),
                senderId);

        if (isActive) {
            // Propagate one-time eKYC flag to any sibling rows for the same remitter mobile across outlets
            jdbc.update("""
                    UPDATE dmt_senders
                       SET kyc_level = 'FULL_KYC',
                           ekyc_verified = TRUE,
                           ekyc_verified_at = COALESCE(ekyc_verified_at, now()),
                           updated_at = now()
                     WHERE mobile = ? AND ekyc_verified = FALSE
                    """, mobile);
            syncBeneficiariesFromProfile(senderId, profile.beneficiaries());
        }

        List<Map<String, Object>> benes = isActive ? listBeneficiariesInternal(senderId) : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("senderId", senderId);
        out.put("mobile", mobile);
        out.put("exists", isActive);
        out.put("status", profile.status());
        out.put("ekycVerified", isActive);
        out.put("fullName", fullName.isEmpty() ? null : fullName);
        out.put("firstName", blankToNull(profile.firstName()));
        out.put("lastName", blankToNull(profile.lastName()));
        out.put("city", blankToNull(profile.city()));
        out.put("pincode", blankToNull(profile.pincode()));
        out.put("limitPerTransaction", profile.perTxnLimit());
        out.put("limit", profile.monthlyLimitAvailable());
        out.put("dailyLimitMax", profile.dailyLimitMax());
        out.put("dailyLimitAvailable", profile.dailyLimitAvailable());
        out.put("monthlyLimitMax", profile.monthlyLimitMax());
        out.put("monthlyLimitAvailable", profile.monthlyLimitAvailable());
        out.put("impsAllowed", profile.impsAllowed());
        out.put("neftAllowed", profile.neftAllowed());
        out.put("wadh", wadh);
        out.put("rdEnv", rdEnv);
        out.put("beneficiaries", benes);
        out.put("message", buildProfileMessage(profile.status(), profile.message()));
        return out;
    }

    private void syncBeneficiariesFromProfile(UUID senderId, List<ProviderModels.DmtBeneficiaryItem> remoteBenes) {
        if (remoteBenes == null) {
            return;
        }
        Set<String> seenIds = new HashSet<>();
        for (ProviderModels.DmtBeneficiaryItem b : remoteBenes) {
            if (b.id() != null && !b.id().isBlank()) {
                seenIds.add(b.id().trim());
            }
            upsertBeneficiaryRecord(
                    senderId,
                    b.id(),
                    b.name(),
                    b.account(),
                    b.ifsc(),
                    null,
                    b.bankName(),
                    b.beneficiaryMobile(),
                    b.verificationDt() != null && !b.verificationDt().isBlank());
        }
        if (!seenIds.isEmpty()) {
            List<Map<String, Object>> localRows = jdbc.queryForList("""
                    SELECT id, provider_bene_id
                      FROM dmt_beneficiaries
                     WHERE sender_id = ? AND deleted_at IS NULL AND provider_bene_id IS NOT NULL
                    """, senderId);
            for (Map<String, Object> r : localRows) {
                String pid = str(r.get("provider_bene_id"));
                if (!pid.isEmpty() && !seenIds.contains(pid)) {
                    jdbc.update("UPDATE dmt_beneficiaries SET deleted_at = now(), updated_at = now() WHERE id = ?", r.get("id"));
                }
            }
        }
    }

    private Map<String, Object> upsertBeneficiaryRecord(
            UUID senderId,
            String providerBeneIdRaw,
            String nameRaw,
            String accountRaw,
            String ifscRaw,
            String bankIdRaw,
            String bankNameRaw,
            String beneMobileRaw,
            boolean verified) {
        String providerBeneId = blankToNull(providerBeneIdRaw);
        String name = firstNonBlank(nameRaw, "Beneficiary");
        String ifsc = DmtCasaTexts.ifsc(ifscRaw);
        String rawAcc = accountRaw == null ? "" : accountRaw.trim();
        boolean unmasked = !rawAcc.isEmpty() && !rawAcc.contains("X") && !rawAcc.contains("x") && !rawAcc.contains("•") && !rawAcc.contains("*");
        String last4 = DmtCasaTexts.last4(rawAcc);
        String masked = "••••" + last4;
        String encAcc = unmasked ? fieldCipher.encrypt(rawAcc) : null;

        UUID targetId = null;
        if (providerBeneId != null) {
            List<UUID> found = jdbc.queryForList("""
                    SELECT id FROM dmt_beneficiaries
                     WHERE sender_id = ? AND provider_bene_id = ? AND deleted_at IS NULL
                    """, UUID.class, senderId, providerBeneId);
            if (!found.isEmpty()) {
                targetId = found.get(0);
            }
        }
        if (targetId == null && unmasked) {
            List<Map<String, Object>> candidates = jdbc.queryForList("""
                    SELECT id, account_number_enc
                      FROM dmt_beneficiaries
                     WHERE sender_id = ? AND account_last4 = ? AND ifsc = ? AND deleted_at IS NULL
                    """, senderId, last4, ifsc);
            for (Map<String, Object> c : candidates) {
                String existingEnc = str(c.get("account_number_enc"));
                if (!existingEnc.isEmpty() && rawAcc.equals(fieldCipher.decrypt(existingEnc))) {
                    targetId = (UUID) c.get("id");
                    break;
                }
            }
        }

        if (targetId != null) {
            jdbc.update("""
                    UPDATE dmt_beneficiaries
                       SET name = ?,
                           account_number_enc = COALESCE(?, account_number_enc),
                           account_last4 = ?,
                           account_masked = ?,
                           ifsc = CASE WHEN ? <> '' THEN ? ELSE ifsc END,
                           provider_bene_id = COALESCE(?, provider_bene_id),
                           provider_bank_id = COALESCE(?, provider_bank_id),
                           bank_name = COALESCE(?, bank_name),
                           beneficiary_mobile = COALESCE(?, beneficiary_mobile),
                           verified_at = CASE WHEN ? THEN COALESCE(verified_at, now()) ELSE verified_at END,
                           updated_at = now()
                     WHERE id = ?
                    """,
                    name, encAcc, last4, masked, ifsc, ifsc, providerBeneId,
                    blankToNull(bankIdRaw), blankToNull(bankNameRaw), blankToNull(beneMobileRaw),
                    verified, targetId);
        } else {
            targetId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO dmt_beneficiaries
                        (id, sender_id, name, account_number_enc, account_last4, account_masked,
                         ifsc, provider_bene_id, provider_bank_id, bank_name, beneficiary_mobile, verified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
                    """,
                    targetId, senderId, name, encAcc, last4, masked,
                    ifsc.isEmpty() ? "UNKNOWN0000" : ifsc,
                    providerBeneId, blankToNull(bankIdRaw), blankToNull(bankNameRaw), blankToNull(beneMobileRaw),
                    verified);
        }
        return toBeneficiaryView(jdbc.queryForMap("SELECT * FROM dmt_beneficiaries WHERE id = ?", targetId));
    }

    private List<Map<String, Object>> listBeneficiariesInternal(UUID senderId) {
        return jdbc.queryForList("""
                SELECT id, name, account_last4, account_masked, ifsc, provider_bank_id,
                       bank_name, beneficiary_mobile, provider_bene_id, verified_at,
                       (account_number_enc IS NOT NULL) AS ready_for_transfer
                  FROM dmt_beneficiaries
                 WHERE sender_id = ? AND deleted_at IS NULL
                 ORDER BY created_at DESC
                """, senderId)
                .stream()
                .map(this::toBeneficiaryView)
                .toList();
    }

    private Map<String, Object> toBeneficiaryView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("name", row.get("name"));
        out.put("accountLast4", row.get("account_last4"));
        out.put("accountMasked", firstNonBlank(str(row.get("account_masked")), "••••" + str(row.get("account_last4"))));
        out.put("ifsc", row.get("ifsc"));
        out.put("bankId", row.get("provider_bank_id"));
        out.put("bankName", row.get("bank_name"));
        out.put("beneficiaryMobile", row.get("beneficiary_mobile"));
        out.put("verified", row.get("verified_at") != null);
        out.put("verifiedAt", row.get("verified_at"));
        return out;
    }

    private String resolveFreshProfileRef(
            UUID userId,
            ProviderDispatch.RoutedDmt route,
            String mobile,
            Map<String, Object> senderRow) {
        if (senderRow != null) {
            String cachedRef = str(senderRow.get("profile_reference_key"));
            Timestamp expTs = (Timestamp) senderRow.get("profile_expires_at");
            if (!cachedRef.isEmpty() && expTs != null && expTs.toInstant().isAfter(Instant.now().plusSeconds(30))) {
                return cachedRef;
            }
        }
        ProviderModels.DmtRemitterProfile fresh;
        try (var ignored = EmeraldTraceContext.open(userId, "DMT_PROFILE_REFRESH", null)) {
            fresh = route.port().fetchRemitterProfile(route.channel(), mobile);
        }
        if (!fresh.referenceKey().isBlank()) {
            Instant exp = parseValidityOrDefault(fresh.validity(), SESSION_TTL);
            String wadh = firstNonBlank(fresh.pidOptionWadh(), InstantPayDmtAdapter.DEFAULT_EKYC_WADH);
            shortTx.required(() -> jdbc.update("""
                    UPDATE dmt_senders
                       SET profile_reference_key = ?,
                           profile_expires_at = ?,
                           pid_wadh = ?,
                           updated_at = now()
                     WHERE registered_by = ? AND mobile = ?
                    """, fresh.referenceKey(), Timestamp.from(exp), wadh, userId, mobile));
        }
        return fresh.referenceKey();
    }

    private void refreshSenderProfileQuietly(UUID userId, ProviderDispatch.RoutedDmt route, String mobile) {
        try {
            ProviderModels.DmtRemitterProfile fresh;
            try (var ignored = EmeraldTraceContext.open(userId, "DMT_PROFILE_SYNC", null)) {
                fresh = route.port().fetchRemitterProfile(route.channel(), mobile);
            }
            if ("ACTIVE".equalsIgnoreCase(fresh.status())) {
                shortTx.required(() -> syncSenderAndBuildView(userId, route.channel(), mobile, fresh));
            }
        } catch (Exception ex) {
            log.debug("Post-beneficiary profile sync skipped mobile={}: {}", mobile, ex.getMessage());
        }
    }

    private Map<String, Object> findOrCreateSender(
            UUID agentId,
            ServiceProviderRouter.RoutedChannel channel,
            String mobile) {
        return shortTx.required(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM dmt_senders WHERE registered_by = ? AND mobile = ?", agentId, mobile);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            // Inherit one-time eKYC flag if this remitter already verified anywhere in GFinPay
            List<Map<String, Object>> global = jdbc.queryForList("""
                    SELECT full_name, first_name, last_name, city, pincode, ovd_last4, ekyc_verified, ekyc_verified_at
                      FROM dmt_senders
                     WHERE mobile = ? AND ekyc_verified = TRUE
                     ORDER BY updated_at DESC
                     LIMIT 1
                    """, mobile);
            boolean globalVerified = !global.isEmpty();
            Map<String, Object> g = globalVerified ? global.get(0) : Map.of();

            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO dmt_senders
                        (id, mobile, full_name, first_name, last_name, city, pincode,
                         kyc_level, ekyc_verified, ekyc_verified_at, ovd_type, ovd_last4,
                         registered_by, provider_code, outlet_code, provider_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (registered_by, mobile) DO NOTHING
                    """,
                    id, mobile,
                    g.get("full_name"), g.get("first_name"), g.get("last_name"), g.get("city"), g.get("pincode"),
                    globalVerified ? "FULL_KYC" : "MIN_KYC",
                    globalVerified,
                    g.get("ekyc_verified_at"),
                    globalVerified ? "AADHAAR" : null,
                    g.get("ovd_last4"),
                    agentId,
                    channel.providerCode(),
                    channel.outletCode(),
                    globalVerified ? "ACTIVE" : "REGISTRATION_REQUIRED");
            return jdbc.queryForMap("SELECT * FROM dmt_senders WHERE registered_by = ? AND mobile = ?", agentId, mobile);
        });
    }

    private Map<String, Object> loadOwnedSender(UUID senderId, UUID agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM dmt_senders WHERE id = ? AND registered_by = ?", senderId, agentId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SENDER_NOT_FOUND", "Remitter profile was not found.");
        }
        return rows.get(0);
    }

    private void assertSenderOwned(UUID senderId, UUID agentId) {
        loadOwnedSender(senderId, agentId);
    }

    private Map<String, Object> loadOwnedBeneficiary(UUID beneficiaryId, UUID agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.*, s.mobile AS remitter_mobile
                  FROM dmt_beneficiaries b
                  JOIN dmt_senders s ON s.id = b.sender_id
                 WHERE b.id = ? AND s.registered_by = ? AND b.deleted_at IS NULL
                """, beneficiaryId, agentId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BENEFICIARY_NOT_FOUND", "Beneficiary was not found.");
        }
        return rows.get(0);
    }

    private Map<String, Object> loadRemitterOnboardingSession(UUID agentId, UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM dmt_remitter_onboarding_sessions
                 WHERE id = ? AND agent_user_id = ?
                """, sessionId, agentId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                    "Remitter onboarding session was not found. Look up the customer again.");
        }
        Map<String, Object> s = rows.get(0);
        Timestamp exp = (Timestamp) s.get("expires_at");
        if (exp != null && exp.toInstant().isBefore(Instant.now())) {
            shortTx.required(() -> jdbc.update(
                    "UPDATE dmt_remitter_onboarding_sessions SET stage = 'EXPIRED', updated_at = now() WHERE id = ?",
                    sessionId));
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_EXPIRED",
                    "Verification session expired. Look up the customer mobile again to continue.");
        }
        return s;
    }

    private Map<String, Object> loadBeneOtpSession(UUID agentId, UUID sessionId, String expectedAction) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM dmt_bene_otp_sessions
                 WHERE id = ? AND agent_user_id = ?
                """, sessionId, agentId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "OTP_SESSION_NOT_FOUND",
                    "Beneficiary OTP session was not found. Request a new OTP.");
        }
        Map<String, Object> s = rows.get(0);
        if (s.get("consumed_at") != null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_USED", "This OTP session was already used.");
        }
        if (!expectedAction.equalsIgnoreCase(str(s.get("action")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_ACTION_MISMATCH", "Invalid OTP session type.");
        }
        Timestamp exp = (Timestamp) s.get("expires_at");
        if (exp != null && exp.toInstant().isBefore(Instant.now())) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_EXPIRED",
                    "Beneficiary OTP expired. Request a new OTP.");
        }
        return s;
    }

    private BigDecimal validateTransferAmount(BigDecimal rawAmount, Map<String, Object> sender) {
        if (rawAmount == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_REQUIRED", "Enter a transfer amount.");
        }
        BigDecimal amount = rawAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal min = dmtProps.minAmount();
        BigDecimal perTxnCap = sender.get("per_txn_limit") instanceof BigDecimal p
                ? p.min(dmtProps.maxPerTxn())
                : dmtProps.maxPerTxn();
        if (amount.compareTo(min) < 0 || amount.compareTo(perTxnCap) > 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DMT_AMOUNT",
                    "Transfer amount must be between ₹" + min.toPlainString() + " and ₹" + perTxnCap.toPlainString() + ".");
        }
        if (sender.get("daily_limit_available") instanceof BigDecimal dailyAvail && amount.compareTo(dailyAvail) > 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DAILY_LIMIT_EXCEEDED",
                    "Transfer exceeds remitter's available daily limit (₹" + dailyAvail.setScale(2, RoundingMode.HALF_UP) + ").");
        }
        if (sender.get("monthly_limit_available") instanceof BigDecimal monthlyAvail && amount.compareTo(monthlyAvail) > 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MONTHLY_LIMIT_EXCEEDED",
                    "Transfer exceeds remitter's available monthly limit (₹" + monthlyAvail.setScale(2, RoundingMode.HALF_UP) + ").");
        }
        return amount;
    }

    private String validateTransferMode(String rawMode, Map<String, Object> sender, Map<String, Object> bene) {
        String mode = rawMode == null ? "IMPS" : rawMode.trim().toUpperCase(Locale.ROOT);
        if (!"IMPS".equals(mode) && !"NEFT".equals(mode)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER_MODE",
                    "Transfer mode must be IMPS or NEFT.");
        }
        if ("IMPS".equals(mode) && Boolean.FALSE.equals(sender.get("imps_allowed"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "IMPS_NOT_ALLOWED",
                    "IMPS is currently unavailable for this remitter. Select NEFT instead.");
        }
        if ("NEFT".equals(mode) && Boolean.FALSE.equals(sender.get("neft_allowed"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NEFT_NOT_ALLOWED",
                    "NEFT is currently unavailable for this remitter. Select IMPS instead.");
        }
        String bankId = str(bene.get("provider_bank_id"));
        if (!bankId.isEmpty()) {
            Map<String, Object> bankRow = bankList.findActiveByBankId(bankId);
            if (bankRow != null) {
                if ("IMPS".equals(mode) && Boolean.FALSE.equals(bankRow.get("imps_enabled"))) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_IMPS_DISABLED",
                            bankRow.get("bank_name") + " does not support IMPS right now. Choose NEFT.");
                }
                if ("NEFT".equals(mode) && Boolean.FALSE.equals(bankRow.get("neft_enabled"))) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_NEFT_DISABLED",
                            bankRow.get("bank_name") + " does not support NEFT right now. Choose IMPS.");
                }
            }
        }
        return mode;
    }

    private GeoCheck resolveAndVerifyGeoFence(UUID userId, String latRaw, String lngRaw) {
        List<Map<String, Object>> shopRows = jdbc.queryForList(
                "SELECT work_latitude, work_longitude FROM agent_kyc_profiles WHERE user_id = ?", userId);
        BigDecimal shopLat = !shopRows.isEmpty() ? (BigDecimal) shopRows.get(0).get("work_latitude") : null;
        BigDecimal shopLng = !shopRows.isEmpty() ? (BigDecimal) shopRows.get(0).get("work_longitude") : null;

        BigDecimal liveLat = parseCoordinate(latRaw, true);
        BigDecimal liveLng = parseCoordinate(lngRaw, false);

        if (liveLat == null || liveLng == null) {
            if (dmtProps.geofenceEnabled()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                        "Live browser location access is required to verify you are at your registered shop.");
            }
            if (shopLat != null && shopLng != null) {
                return new GeoCheck(shopLat, shopLng, BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
            }
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Allow location access in your browser to run a DMT transaction.");
        }

        if (shopLat != null && shopLng != null) {
            double distKm = haversineKm(
                    liveLat.doubleValue(), liveLng.doubleValue(),
                    shopLat.doubleValue(), shopLng.doubleValue());
            BigDecimal distBd = BigDecimal.valueOf(distKm).setScale(3, RoundingMode.HALF_UP);
            if (dmtProps.geofenceEnabled() && distKm > dmtProps.maxGeofenceKm()) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "DMT_GEOFENCE_VIOLATION",
                        String.format(Locale.ROOT,
                                "DMT transactions are restricted to your registered shop location (within %.1f km). Current distance: %.2f km.",
                                dmtProps.maxGeofenceKm(), distKm));
            }
            return new GeoCheck(liveLat, liveLng, distBd);
        }
        return new GeoCheck(liveLat, liveLng, null);
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        return r * c;
    }

    private static BigDecimal parseCoordinate(String raw, boolean latitude) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            double d = bd.doubleValue();
            if ((latitude && d >= -90 && d <= 90) || (!latitude && d >= -180 && d <= 180)) {
                return bd;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static boolean isStaleReferenceError(String msg, String actCode) {
        if ("REFEXP".equalsIgnoreCase(actCode) || "INVREF".equalsIgnoreCase(actCode)) {
            return true;
        }
        String lower = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        return lower.contains("reference") && (lower.contains("expired") || lower.contains("invalid"));
    }

    private static String buildProfileMessage(String status, String upstreamMsg) {
        if ("ACTIVE".equalsIgnoreCase(status)) {
            return "Remitter profile active (one-time KYC verified).";
        }
        if ("KYC_REQUIRED".equalsIgnoreCase(status)) {
            return "Mobile is verified. Complete one-time fingerprint eKYC to activate this remitter.";
        }
        if ("REGISTRATION_REQUIRED".equalsIgnoreCase(status)) {
            return "First-time remitter. Complete one-time Aadhaar OTP & fingerprint eKYC to activate.";
        }
        return EmeraldMessageSanitizer.sanitize(upstreamMsg, "Remitter lookup completed.");
    }

    private static Instant parseValidityOrDefault(String validityRaw, Duration fallbackTtl) {
        if (validityRaw != null && !validityRaw.isBlank()) {
            try {
                return LocalDateTime.parse(validityRaw.trim(), IPAY_DT).atZone(IST).toInstant();
            } catch (Exception ignored) {
            }
        }
        return Instant.now().plus(fallbackTtl);
    }

    private static String validateMobile10(String raw) {
        String digits = DmtService.digits(raw);
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        if (!digits.matches("[6-9][0-9]{9}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                    "Enter a valid 10-digit mobile number.");
        }
        return digits;
    }

    private static String validateOtp(String raw) {
        String otp = DmtService.digits(raw);
        if (otp.length() < 4 || otp.length() > 8) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OTP",
                    "Enter the numeric OTP sent to the customer's mobile number.");
        }
        return otp;
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a.trim() : (b == null ? "" : b.trim());
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
