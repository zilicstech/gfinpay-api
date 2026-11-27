package com.fintech.aeps;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.biometric.RdPidXmlParser;
import com.fintech.commission.CommissionEngine;
import com.fintech.emerald.EmeraldMessageSanitizer;
import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.tx.ShortTransactions;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ChannelLimits;
import com.fintech.provider.ProviderDispatch;
import com.fintech.provider.ProviderRegistry;
import com.fintech.provider.RetailerOnboardingProfileAssembler;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.verification.AadhaarValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aadhaar Enabled Payment System (AePS) service supporting:
 * <ul>
 *   <li>Merchant Daily / Per-Txn 2FA ({@code outletLoginStatus} &amp; {@code outletLogin})</li>
 *   <li>Live cached AePS Bank Directory ({@code /fi/aeps/banks})</li>
 *   <li>{@code >₹5,000} Cash Withdrawal Off-Us / On-Us OTP challenges ({@code transactionOtp})</li>
 *   <li>Cash Withdrawal ({@code CW}), Balance Enquiry ({@code BE}), Mini Statement ({@code MS}), and optional Cash Deposit ({@code CD})</li>
 *   <li>3 km shop geo-fencing, 2x biometric mismatch velocity protection, {@code ShortTransactions}, and {@code TUP} reconciliation</li>
 * </ul>
 */
@Service
public class AepsService {

    private static final Logger log = LoggerFactory.getLogger(AepsService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration OTP_SESSION_TTL = Duration.ofMinutes(10);
    private static final long CW_2FA_WINDOW_SECONDS = 180L;

    private static final List<Map<String, Object>> NPCI_BANKS = List.of(
            Map.of("bankId", "109005", "iin", "607094", "name", "State Bank of India", "cwOtpMin", 5000.01),
            Map.of("bankId", "11263", "iin", "607152", "name", "HDFC Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "15910", "iin", "508534", "name", "ICICI Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "130878", "iin", "607153", "name", "Axis Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "74984", "iin", "607027", "name", "Punjab National Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "39287", "iin", "606985", "name", "Bank of Baroda", "cwOtpMin", 5000.01),
            Map.of("bankId", "53201", "iin", "607396", "name", "Canara Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "91606", "iin", "607161", "name", "Union Bank of India", "cwOtpMin", 5000.01),
            Map.of("bankId", "47267", "iin", "508505", "name", "Bank of India", "cwOtpMin", 5000.01),
            Map.of("bankId", "31197", "iin", "990309", "name", "Kotak Mahindra Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "64597", "iin", "607189", "name", "IndusInd Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "608117", "iin", "608117", "name", "IDFC First Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "607618", "iin", "607618", "name", "Yes Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "134157", "iin", "608314", "name", "India Post Payments Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "1", "iin", "990320", "name", "Airtel Payments Bank", "cwOtpMin", 5000.01),
            Map.of("bankId", "30728", "iin", "608001", "name", "Fino Payments Bank", "cwOtpMin", 5000.01)
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformServiceGate services;
    private final ProviderDispatch dispatch;
    private final ProviderRegistry providers;
    private final RetailerOnboardingProfileAssembler profileAssembler;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;
    private final CommissionEngine commissionEngine;
    private final ShortTransactions shortTx;
    private final AepsProperties aepsProps;
    private final String piiKey;
    private final String rdEnv;

    @Autowired
    public AepsService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformServiceGate services,
            ProviderDispatch dispatch,
            ProviderRegistry providers,
            RetailerOnboardingProfileAssembler profileAssembler,
            WalletService walletService,
            LedgerService ledgerService,
            TransactionService transactionService,
            CommissionEngine commissionEngine,
            ShortTransactions shortTx,
            AepsProperties aepsProps,
            @Value("${app.pii.key:dev-pii-key-32-bytes-minimum-key!}") String piiKey,
            @Value("${app.biometric.rd-env:P}") String rdEnv) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.services = services;
        this.dispatch = dispatch;
        this.providers = providers;
        this.profileAssembler = profileAssembler;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.commissionEngine = commissionEngine;
        this.shortTx = shortTx;
        this.aepsProps = aepsProps != null ? aepsProps : new AepsProperties();
        this.piiKey = (piiKey == null || piiKey.isBlank()) ? "dev-pii-key-32-bytes-minimum-key!" : piiKey;
        this.rdEnv = (rdEnv == null || rdEnv.isBlank()) ? "P" : rdEnv.trim().toUpperCase(Locale.ROOT);
    }

    public List<Map<String, Object>> listBanks(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        if (me == null) {
            return NPCI_BANKS;
        }
        try {
            ProviderDispatch.RoutedAeps route = dispatch.aeps(me.userId());
            List<ProviderModels.AepsBank> live;
            try (var ignored = EmeraldTraceContext.open(me.userId(), "STEP3_AEPS_BANKS", null)) {
                live = route.port().listBanks(route.channel());
            }
            if (live != null && !live.isEmpty()) {
                List<Map<String, Object>> out = new ArrayList<>(live.size());
                for (ProviderModels.AepsBank b : live) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bankId", b.bankId());
                    m.put("iin", b.iin());
                    m.put("name", b.name());
                    m.put("cwOtpMin", b.cwOtpMin() != null ? b.cwOtpMin() : new BigDecimal("5000.01"));
                    if (b.failureRatePct() != null) {
                        m.put("failureRatePct", b.failureRatePct());
                    }
                    out.add(m);
                }
                return out;
            }
        } catch (Exception ex) {
            log.debug("AEPS dynamic bank list fallback for user={}: {}", me.userId(), ex.getMessage());
        }
        return NPCI_BANKS;
    }

    public List<Map<String, Object>> listBanks() {
        return NPCI_BANKS;
    }

    /**
     * Checks whether the merchant has completed daily AePS 2FA for today (IST),
     * avoiding redundant upstream calls once {@code LOGGED_IN} unless {@code refresh == true}.
     */
    public Map<String, Object> getStatus(AuthPrincipal me, boolean refresh) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        UUID userId = me.userId();
        requireUnlockedMerchant(userId);

        ProviderDispatch.RoutedAeps route = dispatch.aeps(userId);
        ServiceProviderRouter.RoutedChannel channel = route.channel();
        LocalDate todayIst = LocalDate.now(IST);

        Map<String, Object> session = find2faSession(userId, channel.channelId(), todayIst);
        boolean alreadyLoggedIn = session != null
                && "LOGGED_IN".equalsIgnoreCase(str(session.get("status")))
                && !Boolean.TRUE.equals(session.get("is_txn_bio_login_required"));

        if (refresh || !alreadyLoggedIn) {
            ProviderModels.AepsLoginStatus live;
            try (var ignored = EmeraldTraceContext.open(userId, "STEP3_AEPS_LOGIN_STATUS", null)) {
                live = route.port().checkOutletLoginStatus(channel, null);
            }
            if (live.dormantOutlet()) {
                markOutletDormant(userId, channel.channelId(), live.message());
            }
            String nextStatus = live.dormantOutlet()
                    ? "DORMANT_OUTLET"
                    : (live.loggedIn() ? "LOGGED_IN" : "LOGIN_REQUIRED");
            upsert2faSession(
                    userId,
                    channel.channelId(),
                    channel.outletCode(),
                    todayIst,
                    nextStatus,
                    live.aadhaarLast4(),
                    live.txnBioLoginRequired(),
                    live.faceAuthAvailable(),
                    live.irisAuthAvailable(),
                    live.providerTxnId(),
                    live.statusCode(),
                    live.message(),
                    live.loggedIn() && (session == null || session.get("last_logged_in_at") == null));
            session = find2faSession(userId, channel.channelId(), todayIst);
        }

        return buildStatusView(channel, session);
    }

    /**
     * Submits the merchant's own L1 fingerprint to unlock daily / per-transaction AePS 2FA.
     * Decrypts the merchant's (or Company Authorized Signatory's) stored Aadhaar from the vault.
     */
    public Map<String, Object> submitOutletLogin(
            AuthPrincipal me,
            String pidXml,
            String captureTypeRaw,
            String latitudeRaw,
            String longitudeRaw) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        UUID userId = me.userId();
        requireUnlockedMerchant(userId);

        GeoCheck geo = resolveAndVerifyGeoFence(userId, latitudeRaw, longitudeRaw);
        RdPidXmlParser.ParsedPidData parsedPid = RdPidXmlParser.parse(pidXml);
        String merchantAadhaar12 = resolveMerchantAadhaar12(userId);

        ProviderDispatch.RoutedAeps route = dispatch.aeps(userId);
        ServiceProviderRouter.RoutedChannel channel = route.channel();
        LocalDate todayIst = LocalDate.now(IST);
        String captureType = normalizeCaptureType(captureTypeRaw);
        String externalRef = "AEPS2FA" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));

        ProviderModels.AepsLoginStatus res;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP3_AEPS_OUTLET_LOGIN", externalRef)) {
            res = route.port().submitOutletLogin(channel, new ProviderModels.AepsOutletLoginRequest(
                    "DAILY_LOGIN",
                    captureType,
                    geo.lat().toPlainString(),
                    geo.lng().toPlainString(),
                    externalRef,
                    merchantAadhaar12,
                    parsedPid));
        }

        if (res.dormantOutlet()) {
            markOutletDormant(userId, channel.channelId(), res.message());
            upsert2faSession(userId, channel.channelId(), channel.outletCode(), todayIst,
                    "DORMANT_OUTLET", res.aadhaarLast4(), res.txnBioLoginRequired(),
                    res.faceAuthAvailable(), res.irisAuthAvailable(), res.providerTxnId(),
                    res.statusCode(), res.message(), false);
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OUTLET_DORMANT",
                    "Your AePS merchant terminal requires re-registration due to inactivity. Please contact support.");
        }

        if (!res.loggedIn()) {
            upsert2faSession(userId, channel.channelId(), channel.outletCode(), todayIst,
                    "LOGIN_REQUIRED", res.aadhaarLast4(), res.txnBioLoginRequired(),
                    res.faceAuthAvailable(), res.irisAuthAvailable(), res.providerTxnId(),
                    res.statusCode(), res.message(), false);
            String cleanMsg = EmeraldMessageSanitizer.sanitize(
                    res.message(),
                    "Merchant fingerprint did not match. Please clean the sensor and scan your finger again.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_2FA_FAILED", cleanMsg);
        }

        String last4 = !res.aadhaarLast4().isBlank() ? res.aadhaarLast4() : merchantAadhaar12.substring(8);
        upsert2faSession(userId, channel.channelId(), channel.outletCode(), todayIst,
                "LOGGED_IN", last4, res.txnBioLoginRequired(),
                res.faceAuthAvailable(), res.irisAuthAvailable(), res.providerTxnId(),
                res.statusCode(), res.message(), true);

        Map<String, Object> session = find2faSession(userId, channel.channelId(), todayIst);
        return buildStatusView(channel, session);
    }

    /**
     * Initiates a customer OTP challenge required for AePS Cash Withdrawals above ₹5,000.
     * Supports both Off-Us ({@code otpRequired = true}) and On-Us ({@code otpRequired = false}) responses.
     */
    public Map<String, Object> requestTransactionOtp(
            AuthPrincipal me,
            String bankIinRaw,
            String aadhaarRaw,
            String customerMobileRaw,
            Integer amountRupees,
            String latitudeRaw,
            String longitudeRaw) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        UUID userId = me.userId();
        requireUnlockedMerchant(userId);

        String cleanAadhaar = AadhaarValidator.validateFormat(aadhaarRaw);
        String aadhaarHmac = hmacSha256Hex(cleanAadhaar);
        assertNotBlockedByBiometricMismatch(userId, aadhaarHmac);

        String bankIin = normalizeBankIin(bankIinRaw);
        String cleanMobile = validateCustomerMobile(customerMobileRaw);
        GeoCheck geo = resolveAndVerifyGeoFence(userId, latitudeRaw, longitudeRaw);

        ProviderDispatch.RoutedAeps route = dispatch.aeps(userId);
        ServiceProviderRouter.RoutedChannel channel = route.channel();
        requireMerchant2faReady(userId, channel, route.port());

        ChannelLimits.MoneyRule rule = ChannelLimits.aeps(channel.configJson());
        if (amountRupees == null || amountRupees <= 5000 || amountRupees > rule.max()
                || (rule.step() > 0 && amountRupees % rule.step() != 0)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AEPS_OTP_AMOUNT",
                    "Customer OTP is only applicable for Cash Withdrawals above ₹5,000 (up to ₹" + rule.max() + ").");
        }

        String externalRef = "AEPSOTP" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));

        ProviderModels.AepsOtpChallenge challenge;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP4_AEPS_TXN_OTP", externalRef)) {
            challenge = route.port().requestTransactionOtp(channel, new ProviderModels.AepsOtpRequest(
                    bankIin,
                    cleanAadhaar,
                    cleanMobile,
                    amountRupees,
                    geo.lat().toPlainString(),
                    geo.lng().toPlainString(),
                    externalRef));
        }

        if (challenge.dormantOutlet()) {
            markOutletDormant(userId, channel.channelId(), challenge.message());
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OUTLET_DORMANT",
                    "Your AePS merchant terminal requires re-registration due to inactivity. Please contact support.");
        }
        if (challenge.loginRequired()) {
            mark2faLoginRequiredToday(userId, channel.channelId(), challenge.message());
            throw ApiException.of(HttpStatus.CONFLICT, "AEPS_2FA_REQUIRED",
                    "Merchant daily biometric sign-in has expired. Please scan your merchant finger first.");
        }
        if (!challenge.success()) {
            String cleanErr = EmeraldMessageSanitizer.sanitize(
                    challenge.message(),
                    "Unable to initiate customer OTP for this bank right now. Please retry.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OTP_FAILED", cleanErr);
        }

        UUID otpSessionId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(OTP_SESSION_TTL);
        BigDecimal amt = BigDecimal.valueOf(amountRupees).setScale(2, RoundingMode.HALF_UP);
        shortTx.required(() -> {
            jdbc.update("""
                    INSERT INTO aeps_otp_sessions
                        (id, user_id, channel_id, outlet_code, bank_iin, aadhaar_hmac, customer_mobile,
                         amount, reference_key, otp_required, validity_text, status_code, provider_message, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    otpSessionId,
                    userId,
                    channel.channelId(),
                    channel.outletCode(),
                    bankIin,
                    aadhaarHmac,
                    cleanMobile,
                    amt,
                    challenge.referenceKey(),
                    challenge.otpRequired(),
                    challenge.validity(),
                    challenge.statusCode(),
                    challenge.message(),
                    Timestamp.from(expiresAt));
            return null;
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("otpSessionId", otpSessionId);
        out.put("otpRequired", challenge.otpRequired());
        out.put("validity", challenge.validity());
        out.put("expiresAt", expiresAt);
        out.put("message", challenge.otpRequired()
                ? "6-digit OTP sent to customer mobile number. Enter the OTP before scanning customer fingerprint."
                : "Bank confirmed On-Us transaction — no OTP required. Proceed to scan customer fingerprint.");
        return out;
    }

    public Map<String, Object> transact(
            AuthPrincipal me,
            String modeRaw,
            String aadhaarRaw,
            String bankIinRaw,
            String customerMobileRaw,
            Integer amountRupees,
            String pidXml,
            String latitudeRaw,
            String longitudeRaw) {
        return transact(me, modeRaw, aadhaarRaw, bankIinRaw, customerMobileRaw, amountRupees,
                pidXml, "FINGER", null, latitudeRaw, longitudeRaw);
    }

    public Map<String, Object> transact(
            AuthPrincipal me,
            String modeRaw,
            String aadhaarRaw,
            String bankIinRaw,
            String customerMobileRaw,
            Integer amountRupees,
            String pidXml,
            String captureTypeRaw,
            UUID otpSessionId,
            String latitudeRaw,
            String longitudeRaw) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        UUID userId = me.userId();
        requireUnlockedMerchant(userId);

        String aepsType = normalizeAepsType(modeRaw);
        if ("CASH_DEPOSIT".equals(aepsType) && !aepsProps.cashDepositEnabled()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_CASH_DEPOSIT_DISABLED",
                    "AePS Cash Deposit is not enabled on this desk.");
        }

        String cleanAadhaar = AadhaarValidator.validateFormat(aadhaarRaw);
        String aadhaarLast4 = cleanAadhaar.substring(8);
        String aadhaarHmac = hmacSha256Hex(cleanAadhaar);
        assertNotBlockedByBiometricMismatch(userId, aadhaarHmac);

        String bankIin = normalizeBankIin(bankIinRaw);
        String cleanMobile = validateCustomerMobile(customerMobileRaw);
        GeoCheck geo = resolveAndVerifyGeoFence(userId, latitudeRaw, longitudeRaw);
        RdPidXmlParser.ParsedPidData parsedPid = RdPidXmlParser.parse(pidXml);
        String captureType = normalizeCaptureType(captureTypeRaw);

        ProviderDispatch.RoutedAeps route = dispatch.aeps(userId);
        ServiceProviderRouter.RoutedChannel channel = route.channel();
        requireMerchant2faReady(userId, channel, route.port());

        List<Map<String, Object>> bankCatalog = listBanks(me);
        Map<String, Object> matchedBank = bankCatalog.stream()
                .filter(b -> bankIin.equals(str(b.get("iin"))))
                .findFirst()
                .orElse(null);
        String bankName = matchedBank != null ? str(matchedBank.get("name")) : "Bank (" + bankIin + ")";
        BigDecimal cwOtpThreshold = matchedBank != null && matchedBank.get("cwOtpMin") != null
                ? parseBigDecimalOrDefault(matchedBank.get("cwOtpMin"), new BigDecimal("5000.01"))
                : new BigDecimal("5000.01");

        BigDecimal amount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if ("CASH_WITHDRAWAL".equals(aepsType) || "CASH_DEPOSIT".equals(aepsType)) {
            ChannelLimits.MoneyRule rule = ChannelLimits.aeps(channel.configJson());
            if (amountRupees == null || amountRupees < rule.min() || amountRupees > rule.max()
                    || rule.step() <= 0 || amountRupees % rule.step() != 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AEPS_AMOUNT",
                        "AePS amount must be between ₹" + rule.min() + " and ₹" + rule.max()
                                + " in multiples of ₹" + rule.step() + ".");
            }
            amount = BigDecimal.valueOf(amountRupees).setScale(2, RoundingMode.HALF_UP);
        }

        String externalRef = "AEPS" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        boolean needsCwOtp = "CASH_WITHDRAWAL".equals(aepsType) && amount.compareTo(cwOtpThreshold) >= 0;

        BigDecimal finalAmount = amount;
        PreparedAeps prepared = shortTx.required(() -> prepareAepsTransaction(
                userId, channel, aepsType, bankIin, bankName, cleanMobile,
                aadhaarLast4, aadhaarHmac, finalAmount, geo, externalRef, needsCwOtp, otpSessionId));

        ProviderModels.AepsResult res;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP4_AEPS_" + aepsType, externalRef)) {
            res = route.port().transact(channel, new ProviderModels.AepsTxnRequest(
                    aepsType,
                    bankIin,
                    cleanMobile,
                    finalAmount,
                    externalRef,
                    prepared.referenceKey(),
                    captureType,
                    geo.lat().toPlainString(),
                    geo.lng().toPlainString(),
                    cleanAadhaar,
                    parsedPid));
        } catch (RuntimeException ex) {
            log.warn("AEPS provider call inconclusive id={} ref={}: {}", prepared.aepsId(), externalRef, ex.getMessage());
            res = new ProviderModels.AepsResult(
                    false,
                    ProviderModels.Outcome.PENDING,
                    "TIMEOUT",
                    "Awaiting bank confirmation",
                    Map.of());
        }

        ProviderModels.AepsResult finalRes = res;
        AppliedAeps applied = shortTx.required(() -> applyAepsOutcome(prepared, userId, channel, aepsType, finalAmount, finalRes));
        if (applied.error() != null) {
            throw applied.error();
        }
        return applied.view();
    }

    private record GeoCheck(BigDecimal lat, BigDecimal lng, BigDecimal distanceKm) {}

    private record PreparedAeps(
            UUID aepsId,
            UUID txnId,
            UUID walletId,
            String referenceKey
    ) {}

    private record AppliedAeps(
            Map<String, Object> view,
            ApiException error
    ) {}

    private PreparedAeps prepareAepsTransaction(
            UUID userId,
            ServiceProviderRouter.RoutedChannel channel,
            String aepsType,
            String bankIin,
            String bankName,
            String cleanMobile,
            String aadhaarLast4,
            String aadhaarHmac,
            BigDecimal amount,
            GeoCheck geo,
            String externalRef,
            boolean needsCwOtp,
            UUID otpSessionId) {
        String referenceKey = "";
        if (needsCwOtp) {
            if (otpSessionId == null) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OTP_REQUIRED",
                        "Cash Withdrawals above ₹5,000 require customer OTP verification first.");
            }
            List<Map<String, Object>> otpRows = jdbc.queryForList("""
                    SELECT * FROM aeps_otp_sessions
                     WHERE id = ? AND user_id = ?
                     FOR UPDATE
                    """, otpSessionId, userId);
            if (otpRows.isEmpty()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OTP_INVALID",
                        "Customer OTP session was not found. Please request a fresh OTP.");
            }
            Map<String, Object> otpRow = otpRows.get(0);
            if (otpRow.get("consumed_at") != null) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OTP_CONSUMED",
                        "This customer OTP session has already been used. Request a new OTP to retry.");
            }
            Timestamp expTs = (Timestamp) otpRow.get("expires_at");
            if (expTs != null && expTs.toInstant().isBefore(Instant.now())) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OTP_EXPIRED",
                        "Customer OTP has expired. Please send a fresh OTP.");
            }
            if (!bankIin.equals(str(otpRow.get("bank_iin")))
                    || !aadhaarHmac.equals(str(otpRow.get("aadhaar_hmac")))
                    || amount.compareTo((BigDecimal) otpRow.get("amount")) != 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OTP_MISMATCH",
                        "Customer bank, Aadhaar, or amount changed after OTP was sent. Request a fresh OTP.");
            }
            referenceKey = str(otpRow.get("reference_key"));
            jdbc.update("UPDATE aeps_otp_sessions SET consumed_at = now() WHERE id = ?", otpSessionId);
        }

        WalletService.WalletInfo wallet = walletService.lockByUser(userId);
        if (!wallet.active()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "WALLET_FROZEN", "Your operating wallet is frozen.");
        }

        UUID txnId = null;
        String initialStatus = "PENDING";
        if ("CASH_WITHDRAWAL".equals(aepsType)) {
            txnId = transactionService.create(
                    "CASHOUT_AEPS",
                    userId,
                    wallet.walletId(),
                    amount,
                    BigDecimal.ZERO,
                    null,
                    null,
                    channel.providerCode(),
                    null);
            transactionService.transition(txnId, "PENDING", externalRef, null);
        } else if ("CASH_DEPOSIT".equals(aepsType)) {
            if (wallet.available().compareTo(amount) < 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                        "Insufficient operating wallet balance for AePS Cash Deposit.");
            }
            txnId = transactionService.create(
                    "AEPS_DEPOSIT",
                    userId,
                    wallet.walletId(),
                    amount,
                    BigDecimal.ZERO,
                    null,
                    null,
                    channel.providerCode(),
                    null);
            transactionService.transition(txnId, "HOLD", externalRef, null);
            ledgerService.post(txnId, wallet.walletId(), "DEBIT", amount, "AePS Cash Deposit (" + bankName + ")");
            walletService.adjustCache(wallet.walletId(), amount.negate(), amount);
            initialStatus = "HOLD";
        }

        UUID aepsId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO aeps_transactions
                    (id, transaction_id, user_id, channel_id, provider_code, pipe_code, outlet_code,
                     external_ref, aeps_type, bank_iin, bank_name, customer_mobile, aadhaar_last4, aadhaar_hmac,
                     amount, latitude, longitude, distance_from_shop_km, otp_reference_key, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                aepsId,
                txnId,
                userId,
                channel.channelId(),
                channel.providerCode(),
                channel.pipeCode(),
                channel.outletCode(),
                externalRef,
                aepsType,
                bankIin,
                bankName,
                cleanMobile,
                aadhaarLast4,
                aadhaarHmac,
                amount,
                geo.lat(),
                geo.lng(),
                geo.distanceKm(),
                blankToNull(referenceKey),
                initialStatus);

        return new PreparedAeps(aepsId, txnId, wallet.walletId(), referenceKey);
    }

    private AppliedAeps applyAepsOutcome(
            PreparedAeps prep,
            UUID userId,
            ServiceProviderRouter.RoutedChannel channel,
            String aepsType,
            BigDecimal amount,
            ProviderModels.AepsResult res) {
        UUID aepsId = prep.aepsId();
        UUID txnId = prep.txnId();

        if ("AEPS_PATH_UNCONFIGURED".equals(res.statusCode())) {
            String msg = "AePS banking switch route is awaiting upstream activation on this environment.";
            markFailed(aepsId, txnId, res.statusCode(), msg, null, false);
            return new AppliedAeps(null, ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "AEPS_SWITCH_PENDING", msg));
        }

        Map<String, Object> data = res.data() == null ? Map.of() : res.data();
        String providerTxnId = firstNonBlank(str(data.get("txnId")), str(data.get("transactionId")), str(data.get("ipayId")));
        String rrn = firstNonBlank(str(data.get("rrn")), str(data.get("bankRrn")), str(data.get("utr")), providerTxnId);
        BigDecimal availBal = parseBigDecimalOrNull(data.get("availableBalance"));
        BigDecimal payableVal = parseBigDecimalOrNull(data.get("payableValue"));
        String maskedAccount = blankToNull(str(data.get("accountNumber")));
        Boolean isOnus = data.get("isOnusTxn") instanceof Boolean b ? b : null;
        Object miniStmt = data.get("miniStatement");
        boolean loginRequired = Boolean.TRUE.equals(data.get("loginRequired"));
        boolean dormantOutlet = Boolean.TRUE.equals(data.get("dormantOutlet"));
        boolean bioMismatch = Boolean.TRUE.equals(data.get("biometricMismatch"));

        if (dormantOutlet) {
            markOutletDormant(userId, channel.channelId(), res.message());
            String msg = "Your AePS merchant terminal requires re-registration due to inactivity. Please contact support.";
            markFailed(aepsId, txnId, res.statusCode(), msg, providerTxnId, false);
            return new AppliedAeps(null, ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OUTLET_DORMANT", msg));
        }

        if (loginRequired) {
            mark2faLoginRequiredToday(userId, channel.channelId(), res.message());
            String msg = "Merchant biometric sign-in window expired. Please scan your merchant finger to re-verify and retry.";
            markFailed(aepsId, txnId, "LOGINREQUIRED", msg, providerTxnId, false);
            return new AppliedAeps(null, ApiException.of(HttpStatus.CONFLICT, "AEPS_2FA_REQUIRED", msg));
        }

        if (res.outcome() == ProviderModels.Outcome.SUCCESS) {
            settleSuccess(aepsId, txnId, userId, aepsType, amount, providerTxnId, rrn,
                    availBal, payableVal, maskedAccount, isOnus, miniStmt, res.statusCode(), res.message());
            // If per-transaction bio login is flagged by upstream, require fresh 2FA before the next CW
            jdbc.update("""
                    UPDATE aeps_merchant_2fa_sessions
                       SET status = CASE WHEN is_txn_bio_login_required THEN 'LOGIN_REQUIRED' ELSE status END,
                           updated_at = now()
                     WHERE user_id = ? AND channel_id = ? AND login_date_ist = ?
                    """, userId, channel.channelId(), Date.valueOf(LocalDate.now(IST)));
            return new AppliedAeps(view(loadById(aepsId)), null);
        }

        if (res.outcome() == ProviderModels.Outcome.PENDING) {
            jdbc.update("""
                    UPDATE aeps_transactions
                       SET provider_txn_id = COALESCE(?, provider_txn_id),
                           provider_status_code = ?,
                           provider_message = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, blankToNull(providerTxnId), res.statusCode(), res.message(), aepsId);
            return new AppliedAeps(view(loadById(aepsId)), null);
        }

        String cleanReason = EmeraldMessageSanitizer.sanitize(
                res.message(),
                "AePS transaction was declined by the customer's bank.");
        markFailed(aepsId, txnId, res.statusCode(), cleanReason, providerTxnId, bioMismatch);
        if (bioMismatch) {
            log.warn("AEPS_BIOMETRIC_MISMATCH userId={} aepsId={} statusCode={} msg={}",
                    userId, aepsId, res.statusCode(), cleanReason);
        }
        return new AppliedAeps(null, ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_DECLINED", cleanReason));
    }

    public Map<String, Object> refreshTransaction(AuthPrincipal me, UUID aepsId) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM aeps_transactions WHERE id = ? AND user_id = ?", aepsId, me.userId());
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AEPS_TXN_NOT_FOUND", "AePS transaction not found.");
        }
        Map<String, Object> row = rows.get(0);
        UUID txnId = (UUID) row.get("transaction_id");
        if (txnId != null) {
            return reconcileByTransactionId(txnId);
        }
        return view(row);
    }

    public Map<String, Object> reconcileByTransactionId(UUID transactionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM aeps_transactions WHERE transaction_id = ?", transactionId);
        if (rows.isEmpty()) {
            return Map.of("matched", false);
        }
        Map<String, Object> row = rows.get(0);
        String curStatus = str(row.get("status"));
        if (!"PENDING".equalsIgnoreCase(curStatus) && !"HOLD".equalsIgnoreCase(curStatus)) {
            return view(row);
        }

        String providerCode = str(row.get("provider_code"));
        String externalRef = str(row.get("external_ref"));
        Timestamp createdTs = (Timestamp) row.get("created_at");
        LocalDate txnDate = createdTs != null ? createdTs.toInstant().atZone(IST).toLocalDate() : LocalDate.now(IST);

        ProviderModels.AepsStatus upstream;
        try (var ignored = EmeraldTraceContext.open((UUID) row.get("user_id"), "STEP6_AEPS_STATUS", externalRef)) {
            ServiceProviderRouter.RoutedChannel ch = new ServiceProviderRouter.RoutedChannel(
                    (UUID) row.get("channel_id"), "", providerCode, str(row.get("pipe_code")),
                    str(row.get("outlet_code")), "", "", "", "{}", "");
            upstream = providers.aeps(providerCode).checkStatus(ch, externalRef, txnDate);
        } catch (Exception ex) {
            log.warn("AEPS status poll warning id={}: {}", row.get("id"), ex.getMessage());
            return view(loadById((UUID) row.get("id")));
        }

        if (upstream == null || upstream.outcome() == ProviderModels.Outcome.PENDING) {
            return view(loadById((UUID) row.get("id")));
        }

        return shortTx.required(() -> {
            List<Map<String, Object>> locked = jdbc.queryForList(
                    "SELECT * FROM aeps_transactions WHERE transaction_id = ? FOR UPDATE", transactionId);
            if (locked.isEmpty()) {
                return Map.of("matched", false);
            }
            Map<String, Object> cur = locked.get(0);
            String st = str(cur.get("status"));
            if ("PENDING".equalsIgnoreCase(st) || "HOLD".equalsIgnoreCase(st)) {
                UUID id = (UUID) cur.get("id");
                UUID uid = (UUID) cur.get("user_id");
                String type = str(cur.get("aeps_type"));
                BigDecimal amt = (BigDecimal) cur.get("amount");
                if (upstream.outcome() == ProviderModels.Outcome.SUCCESS) {
                    settleSuccess(id, transactionId, uid, type, amt, upstream.txnId(), upstream.rrn(),
                            null, null, null, null, null, upstream.statusCode(), upstream.message());
                } else {
                    String cleanReason = EmeraldMessageSanitizer.sanitize(
                            upstream.message(), "AePS transaction failed at customer bank.");
                    markFailed(id, transactionId, upstream.statusCode(), cleanReason, upstream.txnId(), false);
                }
            }
            return view(loadById((UUID) cur.get("id")));
        });
    }

    @Transactional
    public boolean adminResolveByTransactionId(
            UUID transactionId,
            String targetStatus,
            String partnerRef,
            String reason) {
        List<Map<String, Object>> locked = jdbc.queryForList(
                "SELECT * FROM aeps_transactions WHERE transaction_id = ? FOR UPDATE", transactionId);
        if (locked.isEmpty()) {
            return false;
        }
        Map<String, Object> cur = locked.get(0);
        String st = str(cur.get("status"));
        if (!"PENDING".equalsIgnoreCase(st) && !"HOLD".equalsIgnoreCase(st) && !"INITIATED".equalsIgnoreCase(st)) {
            throw ApiException.of(HttpStatus.CONFLICT, "AEPS_ALREADY_RESOLVED",
                    "AePS transaction is already in terminal state: " + st);
        }
        UUID id = (UUID) cur.get("id");
        UUID uid = (UUID) cur.get("user_id");
        String type = str(cur.get("aeps_type"));
        BigDecimal amt = (BigDecimal) cur.get("amount");
        if ("SUCCESS".equalsIgnoreCase(targetStatus)) {
            settleSuccess(id, transactionId, uid, type, amt, partnerRef, partnerRef,
                    null, null, null, null, null, "ADMIN_CONFIRM",
                    reason == null || reason.isBlank() ? "Manually confirmed by Admin" : reason.trim());
        } else {
            markFailed(id, transactionId, "ADMIN_REVERSE",
                    reason == null || reason.isBlank() ? "Manually reversed by Admin" : reason.trim(),
                    partnerRef, false);
        }
        return true;
    }

    public List<Map<String, Object>> history(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        return jdbc.queryForList("""
                SELECT * FROM aeps_transactions
                 WHERE user_id = ?
                 ORDER BY created_at DESC
                 LIMIT 25
                """, me.userId())
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public UUID finalizeFromCallback(
            String externalRef,
            String providerTxnId,
            String callbackStatus,
            String rawMessage,
            String rrn,
            BigDecimal availableBalance,
            Object miniStatement) {
        Map<String, Object> row = findForUpdate(externalRef, providerTxnId);
        if (row == null) {
            log.warn("AEPS callback unmatched externalRef={} providerTxnId={}", externalRef, providerTxnId);
            return null;
        }
        UUID aepsId = (UUID) row.get("id");
        UUID txnId = (UUID) row.get("transaction_id");
        UUID userId = (UUID) row.get("user_id");
        String aepsType = str(row.get("aeps_type"));
        BigDecimal amount = (BigDecimal) row.get("amount");
        String curStatus = str(row.get("status"));
        String norm = callbackStatus == null ? "" : callbackStatus.trim().toUpperCase(Locale.ROOT);

        if ("SUCCESS".equals(norm)) {
            settleSuccess(aepsId, txnId, userId, aepsType, amount, providerTxnId, rrn,
                    availableBalance, null, null, null, miniStatement, "CALLBACK", rawMessage);
        } else if ("FAILED".equals(norm) || "EXPIRED".equals(norm)) {
            String cleanReason = EmeraldMessageSanitizer.sanitize(rawMessage, "AePS transaction failed at bank.");
            markFailed(aepsId, txnId, norm, cleanReason, providerTxnId, false);
        } else if ("REVERSED".equals(norm)) {
            String reason = EmeraldMessageSanitizer.sanitize(rawMessage, "AePS transaction reversed by bank.");
            if (txnId != null) {
                Map<String, Object> txn = transactionService.getById(txnId);
                UUID walletId = (UUID) txn.get("wallet_id");
                if ("SUCCESS".equalsIgnoreCase(curStatus)) {
                    if (transactionService.transition(txnId, "REVERSED", blankToNull(rrn), reason)) {
                        WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                        if ("CASH_WITHDRAWAL".equalsIgnoreCase(aepsType)) {
                            ledgerService.post(txnId, w.walletId(), "DEBIT", amount, "AePS Cashout bank reversal");
                            walletService.adjustCache(walletId, amount.negate(), BigDecimal.ZERO);
                        } else if ("CASH_DEPOSIT".equalsIgnoreCase(aepsType)) {
                            ledgerService.post(txnId, w.walletId(), "CREDIT", amount, "AePS Cash Deposit refund");
                            walletService.adjustCache(walletId, amount, BigDecimal.ZERO);
                        }
                    }
                } else if ("HOLD".equalsIgnoreCase(curStatus)) {
                    if (transactionService.transition(txnId, "REVERSED", blankToNull(rrn), reason)) {
                        WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                        ledgerService.post(txnId, w.walletId(), "CREDIT", amount, "AePS Cash Deposit hold release");
                        walletService.adjustCache(walletId, amount, amount.negate());
                    }
                } else {
                    transactionService.transition(txnId, "REVERSED", blankToNull(rrn), reason);
                }
            }
            jdbc.update("""
                    UPDATE aeps_transactions
                       SET status = 'REVERSED', provider_message = ?, updated_at = now()
                     WHERE id = ?
                    """, reason, aepsId);
        }
        return txnId;
    }

    private void settleSuccess(
            UUID aepsId,
            UUID txnId,
            UUID userId,
            String aepsType,
            BigDecimal amount,
            String providerTxnId,
            String rrn,
            BigDecimal availableBalance,
            BigDecimal payableValue,
            String maskedAccountNumber,
            Boolean isOnus,
            Object miniStatement,
            String statusCode,
            String message) {
        Map<String, Object> cur = loadById(aepsId);
        if ("SUCCESS".equalsIgnoreCase(str(cur.get("status")))) {
            return;
        }
        String resolvedRrn = firstNonBlank(rrn, providerTxnId, str(cur.get("external_ref")));

        if ("CASH_WITHDRAWAL".equalsIgnoreCase(aepsType) && txnId != null && amount != null && amount.signum() > 0) {
            if (transactionService.transition(txnId, "SUCCESS", resolvedRrn, null)) {
                Map<String, Object> txn = transactionService.getById(txnId);
                UUID walletId = (UUID) txn.get("wallet_id");
                WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                ledgerService.post(txnId, w.walletId(), "CREDIT", amount, "AePS Cash Withdrawal settlement");
                walletService.adjustCache(walletId, amount, BigDecimal.ZERO);
                commissionEngine.creditRewards(txnId, userId, "CASHOUT_AEPS", amount);
            }
        } else if ("CASH_DEPOSIT".equalsIgnoreCase(aepsType) && txnId != null && amount != null && amount.signum() > 0) {
            if (transactionService.transition(txnId, "SUCCESS", resolvedRrn, null)) {
                Map<String, Object> txn = transactionService.getById(txnId);
                UUID walletId = (UUID) txn.get("wallet_id");
                walletService.lockByWalletId(walletId);
                walletService.adjustCache(walletId, BigDecimal.ZERO, amount.negate());
            }
        }

        String miniJson = null;
        if (miniStatement != null) {
            try {
                miniJson = mapper.writeValueAsString(miniStatement);
            } catch (JsonProcessingException ignored) {
            }
        }

        jdbc.update("""
                UPDATE aeps_transactions
                   SET status = 'SUCCESS',
                       provider_txn_id = COALESCE(?, provider_txn_id),
                       bank_rrn = COALESCE(?, bank_rrn),
                       available_balance = COALESCE(?, available_balance),
                       payable_value = COALESCE(?, payable_value),
                       masked_account_number = COALESCE(?, masked_account_number),
                       is_onus = COALESCE(?, is_onus),
                       mini_statement = COALESCE(?::jsonb, mini_statement),
                       provider_status_code = ?,
                       provider_message = ?,
                       updated_at = now()
                 WHERE id = ?
                """,
                blankToNull(providerTxnId),
                blankToNull(resolvedRrn),
                availableBalance,
                payableValue,
                maskedAccountNumber,
                isOnus,
                miniJson,
                statusCode,
                message,
                aepsId);
    }

    private void markFailed(
            UUID aepsId,
            UUID txnId,
            String code,
            String cleanReason,
            String providerTxnId,
            boolean biometricMismatch) {
        Map<String, Object> cur = loadById(aepsId);
        String curStatus = str(cur.get("status"));
        if ("SUCCESS".equalsIgnoreCase(curStatus) || "FAILED".equalsIgnoreCase(curStatus)) {
            return;
        }
        String aepsType = str(cur.get("aeps_type"));
        BigDecimal amount = (BigDecimal) cur.get("amount");
        if (txnId != null) {
            if (transactionService.transition(txnId, "FAILED", blankToNull(providerTxnId), cleanReason)) {
                if ("CASH_DEPOSIT".equalsIgnoreCase(aepsType) && "HOLD".equalsIgnoreCase(curStatus)
                        && amount != null && amount.signum() > 0) {
                    Map<String, Object> txn = transactionService.getById(txnId);
                    UUID walletId = (UUID) txn.get("wallet_id");
                    WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                    ledgerService.post(txnId, w.walletId(), "CREDIT", amount, "AePS Cash Deposit reversal");
                    walletService.adjustCache(walletId, amount, amount.negate());
                }
            }
        }
        jdbc.update("""
                UPDATE aeps_transactions
                   SET status = 'FAILED',
                       provider_txn_id = COALESCE(?, provider_txn_id),
                       biometric_mismatch = biometric_mismatch OR ?,
                       provider_status_code = ?,
                       provider_message = ?,
                       updated_at = now()
                 WHERE id = ?
                """, blankToNull(providerTxnId), biometricMismatch, code, cleanReason, aepsId);
    }

    private void requireMerchant2faReady(
            UUID userId,
            ServiceProviderRouter.RoutedChannel channel,
            com.fintech.provider.port.AepsPort port) {
        LocalDate todayIst = LocalDate.now(IST);
        Map<String, Object> session = find2faSession(userId, channel.channelId(), todayIst);
        boolean loggedIn = session != null && "LOGGED_IN".equalsIgnoreCase(str(session.get("status")));
        if (!loggedIn) {
            // Check upstream once in case the merchant completed 2FA earlier today before local row existed
            ProviderModels.AepsLoginStatus live;
            try (var ignored = EmeraldTraceContext.open(userId, "STEP3_AEPS_LOGIN_STATUS", null)) {
                live = port.checkOutletLoginStatus(channel, null);
            }
            if (live.dormantOutlet()) {
                markOutletDormant(userId, channel.channelId(), live.message());
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OUTLET_DORMANT",
                        "Your AePS merchant terminal requires re-registration due to inactivity. Please contact support.");
            }
            upsert2faSession(
                    userId, channel.channelId(), channel.outletCode(), todayIst,
                    live.loggedIn() ? "LOGGED_IN" : "LOGIN_REQUIRED",
                    live.aadhaarLast4(), live.txnBioLoginRequired(),
                    live.faceAuthAvailable(), live.irisAuthAvailable(),
                    live.providerTxnId(), live.statusCode(), live.message(),
                    live.loggedIn());
            if (!live.loggedIn()) {
                throw ApiException.of(HttpStatus.CONFLICT, "AEPS_2FA_REQUIRED",
                        "Complete your daily merchant fingerprint sign-in before running an AePS transaction.");
            }
        }
    }

    private Map<String, Object> find2faSession(UUID userId, UUID channelId, LocalDate dayIst) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM aeps_merchant_2fa_sessions
                 WHERE user_id = ? AND channel_id = ? AND login_date_ist = ?
                """, userId, channelId, Date.valueOf(dayIst));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void upsert2faSession(
            UUID userId,
            UUID channelId,
            String outletCode,
            LocalDate dayIst,
            String status,
            String aadhaarLast4,
            boolean txnBioLoginRequired,
            boolean faceAuthAvailable,
            boolean irisAuthAvailable,
            String providerTxnId,
            String statusCode,
            String message,
            boolean stampLoggedInNow) {
        Timestamp loginNow = stampLoggedInNow ? Timestamp.from(Instant.now()) : null;
        jdbc.update("""
                INSERT INTO aeps_merchant_2fa_sessions
                    (id, user_id, channel_id, outlet_code, login_date_ist, status, aadhaar_last4,
                     is_txn_bio_login_required, face_auth_available, iris_auth_available,
                     provider_txn_id, provider_status_code, provider_message, last_logged_in_at, last_checked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id, channel_id, login_date_ist) DO UPDATE
                   SET status = EXCLUDED.status,
                       aadhaar_last4 = COALESCE(EXCLUDED.aadhaar_last4, aeps_merchant_2fa_sessions.aadhaar_last4),
                       is_txn_bio_login_required = EXCLUDED.is_txn_bio_login_required,
                       face_auth_available = EXCLUDED.face_auth_available,
                       iris_auth_available = EXCLUDED.iris_auth_available,
                       provider_txn_id = COALESCE(EXCLUDED.provider_txn_id, aeps_merchant_2fa_sessions.provider_txn_id),
                       provider_status_code = EXCLUDED.provider_status_code,
                       provider_message = EXCLUDED.provider_message,
                       last_logged_in_at = COALESCE(EXCLUDED.last_logged_in_at, aeps_merchant_2fa_sessions.last_logged_in_at),
                       last_checked_at = now(),
                       updated_at = now()
                """,
                UUID.randomUUID(),
                userId,
                channelId,
                blankToNull(outletCode),
                Date.valueOf(dayIst),
                status,
                aadhaarLast4 != null && aadhaarLast4.matches("[0-9]{4}") ? aadhaarLast4 : null,
                txnBioLoginRequired,
                faceAuthAvailable,
                irisAuthAvailable,
                blankToNull(providerTxnId),
                blankToNull(statusCode),
                blankToNull(message),
                loginNow);
    }

    private void mark2faLoginRequiredToday(UUID userId, UUID channelId, String message) {
        jdbc.update("""
                UPDATE aeps_merchant_2fa_sessions
                   SET status = 'LOGIN_REQUIRED',
                       provider_message = COALESCE(?, provider_message),
                       updated_at = now()
                 WHERE user_id = ? AND channel_id = ? AND login_date_ist = ?
                """, blankToNull(message), userId, channelId, Date.valueOf(LocalDate.now(IST)));
    }

    private void markOutletDormant(UUID userId, UUID channelId, String upstreamMessage) {
        String note = "Dormant AePS outlet (OUI) — re-onboard merchant to InstantPay: "
                + firstNonBlank(upstreamMessage, "No transactions in 180 days");
        jdbc.update("""
                UPDATE provider_pipe_activations
                   SET last_error = ?, updated_at = now()
                 WHERE user_id = ? AND channel_id = ?
                """, note, userId, channelId);
    }

    private Map<String, Object> buildStatusView(
            ServiceProviderRouter.RoutedChannel channel,
            Map<String, Object> session) {
        String status = session == null ? "LOGIN_REQUIRED" : str(session.get("status"));
        boolean loggedIn = "LOGGED_IN".equalsIgnoreCase(status);
        boolean dormant = "DORMANT_OUTLET".equalsIgnoreCase(status);
        boolean txnBioReq = session != null && Boolean.TRUE.equals(session.get("is_txn_bio_login_required"));
        String last4 = session == null ? "" : str(session.get("aadhaar_last4"));
        Timestamp lastLoginTs = session != null ? (Timestamp) session.get("last_logged_in_at") : null;
        long cwWindowRemainingSec = 0L;
        if (lastLoginTs != null) {
            long elapsed = Duration.between(lastLoginTs.toInstant(), Instant.now()).getSeconds();
            cwWindowRemainingSec = Math.max(0L, CW_2FA_WINDOW_SECONDS - elapsed);
        }

        ChannelLimits.MoneyRule rule = ChannelLimits.aeps(channel.configJson());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("min", rule.min());
        limits.put("max", rule.max());
        limits.put("step", rule.step());
        limits.put("cwOtpThreshold", 5000);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aepsReady", loggedIn && !dormant);
        out.put("loggedInToday", loggedIn);
        out.put("loginRequired", !loggedIn && !dormant);
        out.put("dormantOutlet", dormant);
        out.put("isTxnBioLoginRequired", txnBioReq);
        out.put("faceAuthAvailable", session != null && Boolean.TRUE.equals(session.get("face_auth_available")));
        out.put("irisAuthAvailable", session != null && Boolean.TRUE.equals(session.get("iris_auth_available")));
        out.put("aadhaarMasked", last4.isEmpty() ? null : "XXXX-XXXX-" + last4);
        out.put("lastLoggedInAt", lastLoginTs != null ? lastLoginTs.toInstant() : null);
        out.put("cwWindowRemainingSec", cwWindowRemainingSec);
        out.put("rdEnv", rdEnv);
        out.put("limits", limits);
        out.put("geofenceEnabled", aepsProps.geofenceEnabled());
        out.put("geofenceMaxKm", aepsProps.maxGeofenceKm());
        out.put("cashDepositEnabled", aepsProps.cashDepositEnabled());
        return out;
    }

    private String resolveMerchantAadhaar12(UUID userId) {
        try {
            RetailerOnboardingProfileAssembler.InstantPayOnboardingProfile ip =
                    profileAssembler.assembleForInstantPay(userId);
            if (ip.aadhaar12() != null && ip.aadhaar12().matches("[0-9]{12}")) {
                return ip.aadhaar12();
            }
        } catch (Exception ignored) {
        }
        try {
            RetailerOnboardingProfileAssembler.RetailerProviderDossier d = profileAssembler.assemble(userId);
            if (d.aadhaar() != null && d.aadhaar().matches("[0-9]{12}")) {
                return d.aadhaar();
            }
        } catch (Exception ignored) {
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MERCHANT_AADHAAR_MISSING",
                "Verified merchant Aadhaar record is required for daily AePS 2FA. Please contact support.");
    }

    private GeoCheck resolveAndVerifyGeoFence(UUID userId, String latRaw, String lngRaw) {
        List<Map<String, Object>> shopRows = jdbc.queryForList(
                "SELECT work_latitude, work_longitude FROM agent_kyc_profiles WHERE user_id = ?", userId);
        BigDecimal shopLat = !shopRows.isEmpty() ? (BigDecimal) shopRows.get(0).get("work_latitude") : null;
        BigDecimal shopLng = !shopRows.isEmpty() ? (BigDecimal) shopRows.get(0).get("work_longitude") : null;

        BigDecimal liveLat = parseCoordinate(latRaw, true);
        BigDecimal liveLng = parseCoordinate(lngRaw, false);

        if (liveLat == null || liveLng == null) {
            if (aepsProps.geofenceEnabled()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                        "Live browser location access is required to verify you are at your registered shop.");
            }
            if (shopLat != null && shopLng != null) {
                return new GeoCheck(shopLat, shopLng, BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
            }
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Allow location access in your browser to run an AePS transaction.");
        }

        if (shopLat != null && shopLng != null) {
            double distKm = haversineKm(
                    liveLat.doubleValue(), liveLng.doubleValue(),
                    shopLat.doubleValue(), shopLng.doubleValue());
            BigDecimal distBd = BigDecimal.valueOf(distKm).setScale(3, RoundingMode.HALF_UP);
            if (aepsProps.geofenceEnabled() && distKm > aepsProps.maxGeofenceKm()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_OUTSIDE_GEOFENCE",
                        String.format(Locale.ROOT,
                                "AePS transactions are restricted to your registered shop location (within %.1f km). Current distance: %.2f km.",
                                aepsProps.maxGeofenceKm(), distKm));
            }
            return new GeoCheck(liveLat, liveLng, distBd);
        }
        return new GeoCheck(liveLat, liveLng, null);
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        return r * c;
    }

    private void assertNotBlockedByBiometricMismatch(UUID userId, String aadhaarHmac) {
        int maxMismatches = aepsProps.maxBiometricMismatches();
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofHours(aepsProps.mismatchBlockHours())));
        List<Map<String, Object>> recent = jdbc.queryForList("""
                SELECT status, biometric_mismatch
                  FROM aeps_transactions
                 WHERE user_id = ?
                   AND aadhaar_hmac = ?
                   AND created_at >= ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """, userId, aadhaarHmac, since, maxMismatches);
        if (recent.size() < maxMismatches) {
            return;
        }
        boolean allMismatched = recent.stream().allMatch(r ->
                "FAILED".equalsIgnoreCase(str(r.get("status")))
                        && Boolean.TRUE.equals(r.get("biometric_mismatch")));
        if (allMismatched) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_BIOMETRIC_MISMATCH_BLOCKED",
                    "This Aadhaar has reached the maximum consecutive biometric mismatch limit (" + maxMismatches
                            + "). For customer protection, retry after " + aepsProps.mismatchBlockHours()
                            + " hours or verify physical Aadhaar details.");
        }
    }

    String hmacSha256Hex(String aadhaar12) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(piiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(aadhaar12.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private Map<String, Object> findForUpdate(String externalRef, String providerTxnId) {
        if (externalRef != null && !externalRef.isBlank()) {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT * FROM aeps_transactions WHERE external_ref = ? FOR UPDATE", externalRef.trim());
            if (!r.isEmpty()) return r.get(0);
        }
        if (providerTxnId != null && !providerTxnId.isBlank()) {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT * FROM aeps_transactions WHERE provider_txn_id = ? FOR UPDATE", providerTxnId.trim());
            if (!r.isEmpty()) return r.get(0);
        }
        return null;
    }

    private Map<String, Object> loadById(UUID id) {
        return jdbc.queryForMap("SELECT * FROM aeps_transactions WHERE id = ?", id);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("transactionId", row.get("transaction_id"));
        out.put("aepsType", row.get("aeps_type"));
        out.put("bankIin", row.get("bank_iin"));
        out.put("bankName", row.get("bank_name"));
        out.put("customerMobile", row.get("customer_mobile"));
        out.put("aadhaarMasked", "XXXX-XXXX-" + str(row.get("aadhaar_last4")));
        out.put("maskedAccountNumber", row.get("masked_account_number"));
        out.put("amount", row.get("amount"));
        out.put("bankRrn", row.get("bank_rrn"));
        out.put("availableBalance", row.get("available_balance"));
        out.put("isOnus", row.get("is_onus"));
        out.put("miniStatement", parseJsonOrNull(row.get("mini_statement")));
        out.put("status", row.get("status"));
        out.put("message", EmeraldMessageSanitizer.sanitizeNullable(str(row.get("provider_message"))));
        out.put("reference", row.get("external_ref"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    private Object parseJsonOrNull(Object raw) {
        if (raw == null) return null;
        try {
            return mapper.readValue(String.valueOf(raw), Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void requireUnlockedMerchant(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.status AS user_status,
                       COALESCE(p.business_verified, FALSE) AS business_verified,
                       p.business_status,
                       p.agreement_status
                  FROM users u
                  LEFT JOIN agent_kyc_profiles p ON p.user_id = u.id
                 WHERE u.id = ?
                """, userId);
        if (rows.isEmpty() || !"ACTIVE".equalsIgnoreCase(str(rows.get(0).get("user_status")))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Your account is not active.");
        }
        Map<String, Object> r = rows.get(0);
        boolean bizOk = Boolean.TRUE.equals(r.get("business_verified"))
                || "BUSINESS_VERIFIED".equalsIgnoreCase(str(r.get("business_status")));
        boolean agrOk = "SIGNED".equalsIgnoreCase(str(r.get("agreement_status")));
        if (!bizOk || !agrOk) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "ONBOARDING_INCOMPLETE",
                    "Complete business verification and sign your partner agreement first.");
        }
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

    private static String normalizeBankIin(String bankIinRaw) {
        String bankIin = bankIinRaw == null ? "" : bankIinRaw.replaceAll("\\D", "");
        if (bankIin.length() < 6) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BANK_IIN",
                    "Select a valid customer bank.");
        }
        return bankIin;
    }

    private static String validateCustomerMobile(String customerMobileRaw) {
        String cleanMobile = normalizeMobile(customerMobileRaw);
        if (!cleanMobile.matches("[6-9][0-9]{9}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                    "Enter a valid 10-digit customer mobile number.");
        }
        return cleanMobile;
    }

    private static String normalizeCaptureType(String raw) {
        if (raw == null || raw.isBlank()) return "FINGER";
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "FINGER", "FACE", "IRIS" -> u;
            default -> "FINGER";
        };
    }

    private static String normalizeAepsType(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "CW", "CASH_WITHDRAWAL" -> "CASH_WITHDRAWAL";
            case "BE", "BALANCE_ENQUIRY", "BALANCE_INQUIRY" -> "BALANCE_ENQUIRY";
            case "MS", "MINI_STATEMENT" -> "MINI_STATEMENT";
            case "CD", "CASH_DEPOSIT" -> "CASH_DEPOSIT";
            default -> throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AEPS_MODE",
                    "AePS mode must be CW (Cash Withdrawal), BE (Balance Enquiry), or MS (Mini Statement).");
        };
    }

    private static BigDecimal parseBigDecimalOrNull(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseBigDecimalOrDefault(Object raw, BigDecimal fallback) {
        BigDecimal v = parseBigDecimalOrNull(raw);
        return v != null ? v : fallback;
    }

    private static String normalizeMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        return (digits.length() == 12 && digits.startsWith("91")) ? digits.substring(2) : digits;
    }

    private static String blankToNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
