package com.fintech.instantpay;

import com.fintech.dmt.DmtProperties;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.DmtPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Direct InstantPay Domestic Money Transfer ({@code DMT v2}) adapter implementing {@link DmtPort}.
 *
 * <p>Covers all 11 {@code /fi/remit/out/domestic/v2/*} endpoints plus webhook-free
 * {@code POST /reports/txnStatus} reconciliation &amp; post-settlement reconfirmation.
 */
@Component
public class InstantPayDmtAdapter implements DmtPort {

    public static final String DEFAULT_EKYC_WADH = "E0jzJ/P8UopUHAieZn8CKqS4WPMi5ZSYXgfnlfkWjrc=";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MAINT_START = LocalTime.of(23, 30);
    private static final LocalTime MAINT_END = LocalTime.of(0, 30);
    private static final Duration MIN_SUCCESS_RECONFIRM_GAP = Duration.ofMinutes(5);

    private final InstantPayClient client;
    private final InstantPayProperties props;
    private final DmtProperties dmtProps;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    private volatile List<ProviderModels.DmtBankItem> cachedBanks = List.of();
    private volatile Instant cachedBanksExpiresAt = Instant.EPOCH;

    @Autowired
    public InstantPayDmtAdapter(
            InstantPayClient client,
            InstantPayProperties props,
            DmtProperties dmtProps,
            JdbcTemplate jdbc) {
        this(client, props, dmtProps, jdbc, Clock.systemUTC());
    }

    public InstantPayDmtAdapter(
            InstantPayClient client,
            InstantPayProperties props,
            DmtProperties dmtProps,
            JdbcTemplate jdbc,
            Clock clock) {
        this.client = client;
        this.props = props != null ? props : new InstantPayProperties();
        this.dmtProps = dmtProps != null ? dmtProps : new DmtProperties();
        this.jdbc = jdbc;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public String providerCode() {
        return "INSTANTPAY";
    }

    @Override
    public List<ProviderModels.DmtBankItem> listBanks(
            ServiceProviderRouter.RoutedChannel channel,
            boolean forceRefresh) {
        Instant now = Instant.now(clock);
        List<ProviderModels.DmtBankItem> snap = this.cachedBanks;
        if (!forceRefresh && !snap.isEmpty() && now.isBefore(this.cachedBanksExpiresAt)) {
            return snap;
        }
        String outletCode = channel != null ? channel.outletCode() : null;
        InstantPayClient.DmtBankListResponse res = client.listDmtBanks(outletCode);
        if (!res.success() || res.banks().isEmpty()) {
            return snap;
        }
        List<ProviderModels.DmtBankItem> mapped = new ArrayList<>(res.banks().size());
        for (InstantPayClient.DmtBankItemResponse b : res.banks()) {
            mapped.add(new ProviderModels.DmtBankItem(
                    b.bankId(),
                    b.name(),
                    b.ifscAlias(),
                    b.ifscGlobal(),
                    b.impsEnabled(),
                    b.neftEnabled(),
                    b.impsFailureRate(),
                    b.neftFailureRate()));
        }
        List<ProviderModels.DmtBankItem> immutable = List.copyOf(mapped);
        this.cachedBanks = immutable;
        this.cachedBanksExpiresAt = now.plus(Duration.ofMinutes(props.dmtBankCacheMinutes()));
        return immutable;
    }

    @Override
    public ProviderModels.DmtRemitterProfile fetchRemitterProfile(
            ServiceProviderRouter.RoutedChannel channel,
            String mobileNumber) {
        InstantPayClient.DmtRemitterProfileResponse res =
                client.fetchDmtRemitterProfile(channel.outletCode(), mobileNumber, "ALL", "YES");

        String code = res.statusCode();
        String act = res.actCode();
        String lowerMsg = res.message() == null ? "" : res.message().toLowerCase(Locale.ROOT);

        String resolvedStatus;
        if ("TXN".equalsIgnoreCase(code)) {
            resolvedStatus = "ACTIVE";
        } else if ("KYC".equalsIgnoreCase(code)
                || "KYC".equalsIgnoreCase(act)
                || lowerMsg.contains("proceed for kyc")
                || lowerMsg.contains("biometric kyc")
                || lowerMsg.contains("ekyc")) {
            resolvedStatus = "KYC_REQUIRED";
        } else if ("RNF".equalsIgnoreCase(code)
                || "RAR".equalsIgnoreCase(code)
                || lowerMsg.contains("not registered")
                || lowerMsg.contains("not found")
                || lowerMsg.contains("register remitter")
                || (!res.referenceKey().isBlank() && !"ERR".equalsIgnoreCase(code) && !"ISE".equalsIgnoreCase(code))) {
            resolvedStatus = "REGISTRATION_REQUIRED";
        } else {
            resolvedStatus = "ERROR";
        }

        List<ProviderModels.DmtBeneficiaryItem> benes = new ArrayList<>(res.beneficiaries().size());
        for (InstantPayClient.DmtBeneficiaryData b : res.beneficiaries()) {
            benes.add(new ProviderModels.DmtBeneficiaryItem(
                    b.id(),
                    b.name(),
                    b.account(),
                    b.ifsc(),
                    b.bank(),
                    b.beneficiaryMobileNumber(),
                    b.verificationDt()));
        }

        String wadh = res.pidOptionWadh() == null || res.pidOptionWadh().isBlank()
                ? DEFAULT_EKYC_WADH
                : res.pidOptionWadh().trim();

        return new ProviderModels.DmtRemitterProfile(
                resolvedStatus,
                code,
                act,
                res.message(),
                res.mobileNumber(),
                res.firstName(),
                res.lastName(),
                res.city(),
                res.pincode(),
                res.limitPerTransaction(),
                res.maximumDailyLimit(),
                res.availableDailyLimit(),
                res.maximumMonthlyLimit(),
                res.availableMonthlyLimit(),
                res.isTxnOtpRequired(),
                res.isTxnBioAuthRequired(),
                res.isImpsAllowed(),
                res.isNeftAllowed(),
                res.isFaceAuthAvailable(),
                res.validity(),
                res.referenceKey(),
                wadh,
                benes);
    }

    @Override
    public ProviderModels.DmtOtpChallenge registerRemitter(
            ServiceProviderRouter.RoutedChannel channel,
            String mobileNumber,
            String plainAadhaar12,
            String referenceKey) {
        InstantPayClient.DmtChallengeResponse res = client.registerDmtRemitter(
                channel.outletCode(), mobileNumber, plainAadhaar12, referenceKey);
        String code = res.statusCode();
        boolean otpReq = "OTP".equalsIgnoreCase(code);
        boolean kycNext = "KYC".equalsIgnoreCase(code) || "TXN".equalsIgnoreCase(code);
        String nextStatus = otpReq ? "AWAITING_OTP" : (kycNext ? "AWAITING_EKYC" : "FAILED");
        return new ProviderModels.DmtOtpChallenge(
                otpReq || kycNext,
                otpReq,
                nextStatus,
                code,
                res.actCode(),
                res.message(),
                res.referenceKey(),
                res.beneficiaryId(),
                res.validity());
    }

    @Override
    public ProviderModels.DmtOtpChallenge verifyRemitterRegistration(
            ServiceProviderRouter.RoutedChannel channel,
            String mobileNumber,
            String otp,
            String referenceKey) {
        InstantPayClient.DmtChallengeResponse res = client.verifyDmtRemitterRegistration(
                channel.outletCode(), mobileNumber, otp, referenceKey);
        String code = res.statusCode();
        boolean kycRequired = "KYC".equalsIgnoreCase(code) || "KYC".equalsIgnoreCase(res.actCode());
        boolean activeNow = "TXN".equalsIgnoreCase(code) && !kycRequired;
        String nextStatus = kycRequired ? "AWAITING_EKYC" : (activeNow ? "ACTIVE" : "FAILED");
        return new ProviderModels.DmtOtpChallenge(
                kycRequired || activeNow,
                false,
                nextStatus,
                code,
                res.actCode(),
                res.message(),
                res.referenceKey(),
                res.beneficiaryId(),
                res.validity());
    }

    @Override
    public ProviderModels.DmtRemitterEkycResult submitRemitterEkyc(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.DmtRemitterEkycRequest request) {
        Map<String, String> bio12 = request.pid().toInstantPayRemitterKycBiometricData();
        InstantPayClient.DmtChallengeResponse res = client.submitDmtRemitterKyc(
                channel.outletCode(),
                request.mobileNumber(),
                request.referenceKey(),
                request.latitude(),
                request.longitude(),
                request.externalRef(),
                request.captureType(),
                bio12);
        boolean ok = "TXN".equalsIgnoreCase(res.statusCode());
        String poolRef = str(res.data().get("poolReferenceId"));
        BigDecimal poolAmt = null;
        if (res.data().get("pool") instanceof Map<?, ?> poolMap) {
            poolAmt = parseDecimalOrNull(poolMap.get("amount"));
        }
        return new ProviderModels.DmtRemitterEkycResult(
                ok,
                res.statusCode(),
                res.actCode(),
                res.message(),
                firstNonBlank(res.orderId(), poolRef),
                poolRef,
                poolAmt);
    }

    @Override
    public ProviderModels.DmtOtpChallenge registerBeneficiary(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.DmtBeneRegisterRequest req) {
        InstantPayClient.DmtChallengeResponse res = client.registerDmtBeneficiary(
                channel.outletCode(),
                req.remitterMobile(),
                req.beneficiaryMobile(),
                req.accountNumber(),
                req.ifsc(),
                req.bankId(),
                req.name());
        String code = res.statusCode();
        boolean otpReq = "OTP".equalsIgnoreCase(code);
        boolean immediateOk = "TXN".equalsIgnoreCase(code);
        return new ProviderModels.DmtOtpChallenge(
                otpReq || immediateOk,
                otpReq,
                otpReq ? "AWAITING_OTP" : (immediateOk ? "VERIFIED" : "FAILED"),
                code,
                res.actCode(),
                res.message(),
                res.referenceKey(),
                res.beneficiaryId(),
                res.validity());
    }

    @Override
    public ProviderModels.DmtOtpChallenge verifyBeneficiaryRegistration(
            ServiceProviderRouter.RoutedChannel channel,
            String remitterMobile,
            String beneficiaryId,
            String otp,
            String referenceKey) {
        InstantPayClient.DmtChallengeResponse res = client.verifyDmtBeneficiaryRegistration(
                channel.outletCode(), remitterMobile, beneficiaryId, otp, referenceKey);
        boolean ok = "TXN".equalsIgnoreCase(res.statusCode());
        return new ProviderModels.DmtOtpChallenge(
                ok,
                false,
                ok ? "VERIFIED" : "FAILED",
                res.statusCode(),
                res.actCode(),
                res.message(),
                res.referenceKey(),
                firstNonBlank(res.beneficiaryId(), beneficiaryId),
                res.validity());
    }

    @Override
    public ProviderModels.DmtOtpChallenge deleteBeneficiary(
            ServiceProviderRouter.RoutedChannel channel,
            String remitterMobile,
            String beneficiaryId) {
        InstantPayClient.DmtChallengeResponse res = client.deleteDmtBeneficiary(
                channel.outletCode(), remitterMobile, beneficiaryId);
        String code = res.statusCode();
        boolean otpReq = "OTP".equalsIgnoreCase(code);
        boolean immediateOk = "TXN".equalsIgnoreCase(code);
        return new ProviderModels.DmtOtpChallenge(
                otpReq || immediateOk,
                otpReq,
                otpReq ? "AWAITING_OTP" : (immediateOk ? "DELETED" : "FAILED"),
                code,
                res.actCode(),
                res.message(),
                res.referenceKey(),
                firstNonBlank(res.beneficiaryId(), beneficiaryId),
                res.validity());
    }

    @Override
    public ProviderModels.DmtOtpChallenge verifyBeneficiaryDeletion(
            ServiceProviderRouter.RoutedChannel channel,
            String remitterMobile,
            String beneficiaryId,
            String otp,
            String referenceKey) {
        InstantPayClient.DmtChallengeResponse res = client.verifyDmtBeneficiaryDeletion(
                channel.outletCode(), remitterMobile, beneficiaryId, otp, referenceKey);
        boolean ok = "TXN".equalsIgnoreCase(res.statusCode());
        return new ProviderModels.DmtOtpChallenge(
                ok,
                false,
                ok ? "DELETED" : "FAILED",
                res.statusCode(),
                res.actCode(),
                res.message(),
                res.referenceKey(),
                firstNonBlank(res.beneficiaryId(), beneficiaryId),
                res.validity());
    }

    @Override
    public ProviderModels.DmtOtpChallenge generateTransactionOtp(
            ServiceProviderRouter.RoutedChannel channel,
            String remitterMobile,
            BigDecimal amount,
            String referenceKey) {
        InstantPayClient.DmtChallengeResponse res = client.generateDmtTransactionOtp(
                channel.outletCode(),
                remitterMobile,
                amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                referenceKey);
        String code = res.statusCode();
        boolean otpSent = "OTP".equalsIgnoreCase(code);
        boolean noOtpNeeded = "TXN".equalsIgnoreCase(code) && !res.referenceKey().isBlank();
        return new ProviderModels.DmtOtpChallenge(
                otpSent || noOtpNeeded,
                otpSent,
                otpSent ? "AWAITING_OTP" : (noOtpNeeded ? "READY" : "FAILED"),
                code,
                res.actCode(),
                res.message(),
                res.referenceKey(),
                null,
                res.validity());
    }

    @Override
    public ProviderModels.DmtTransferResult executeTransfer(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.DmtTransferRequest req) {
        InstantPayClient.DmtTxnResponse res = client.executeDmtTransaction(
                channel.outletCode(),
                new InstantPayClient.DmtTxnPayRequest(
                        req.remitterMobile(),
                        req.accountNumber(),
                        req.ifsc(),
                        req.transferMode(),
                        req.amount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        req.latitude(),
                        req.longitude(),
                        req.referenceKey(),
                        req.otp(),
                        req.externalRef()));

        if (res.networkError()) {
            return new ProviderModels.DmtTransferResult(
                    ProviderModels.DmtTransferOutcome.PENDING,
                    "TIMEOUT",
                    "",
                    "Transfer status is pending bank confirmation. Use Check Status to reconfirm.",
                    "",
                    "",
                    null);
        }

        String code = res.statusCode();
        String act = res.actCode();
        String msg = res.message();
        String lowerMsg = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        String providerTxnId = firstNonBlank(res.orderId(), res.poolReferenceId());
        String utr = firstNonBlank(res.txnReferenceId(), providerTxnId);
        BigDecimal poolAmt = parseDecimalOrNull(res.poolAmount());

        if ("TXN".equalsIgnoreCase(code)) {
            return new ProviderModels.DmtTransferResult(
                    ProviderModels.DmtTransferOutcome.SUCCESS,
                    code,
                    act,
                    msg,
                    providerTxnId,
                    utr,
                    poolAmt);
        }

        if ("TUP".equalsIgnoreCase(code) || code.isEmpty()) {
            return new ProviderModels.DmtTransferResult(
                    ProviderModels.DmtTransferOutcome.PENDING,
                    code.isEmpty() ? "TUP" : code,
                    act,
                    msg == null || msg.isBlank() ? "Transfer is in process with destination bank." : msg,
                    providerTxnId,
                    utr,
                    poolAmt);
        }

        if ("OTPGENREF".equalsIgnoreCase(act)) {
            return new ProviderModels.DmtTransferResult(
                    ProviderModels.DmtTransferOutcome.OTP_REGENERATE_REQUIRED,
                    code,
                    act,
                    msg,
                    providerTxnId,
                    "",
                    null);
        }

        if ("OTP".equalsIgnoreCase(code)
                || "INVOTP".equalsIgnoreCase(act)
                || lowerMsg.contains("invalid otp")
                || lowerMsg.contains("incorrect otp")
                || lowerMsg.contains("wrong otp")) {
            return new ProviderModels.DmtTransferResult(
                    ProviderModels.DmtTransferOutcome.INVALID_OTP_RETRYABLE,
                    code,
                    act,
                    msg,
                    providerTxnId,
                    "",
                    null);
        }

        return new ProviderModels.DmtTransferResult(
                ProviderModels.DmtTransferOutcome.FAILED,
                code,
                act,
                msg,
                providerTxnId,
                "",
                null);
    }

    @Override
    public ProviderModels.DmtTransferStatus checkTransferStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String externalRef,
            String providerTxnId,
            String txnDate,
            boolean force) {
        Instant now = Instant.now(clock);
        LocalTime istTime = now.atZone(IST).toLocalTime();
        if (!istTime.isBefore(MAINT_START) || istTime.isBefore(MAINT_END)) {
            return new ProviderModels.DmtTransferStatus(
                    ProviderModels.Outcome.PENDING,
                    true,
                    "MAINTENANCE_WINDOW",
                    "Bank status reconfirmation is paused during the 23:30–00:30 IST nightly reconciliation window.",
                    "",
                    providerTxnId == null ? "" : providerTxnId);
        }

        String resolvedDate = (txnDate == null || txnDate.isBlank())
                ? LocalDate.now(clock.withZone(IST)).toString()
                : txnDate.trim();

        Duration minInitialDelay = Duration.ofMinutes(dmtProps.statusInitialDelayMinutes());
        Duration minPollInterval = Duration.ofMinutes(dmtProps.statusPollIntervalMinutes());

        if (jdbc != null && externalRef != null && !externalRef.isBlank()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT status, created_at, last_status_polled_at
                      FROM dmt_transfers
                     WHERE external_ref = ?
                    """, externalRef.trim());
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String curStatus = str(row.get("status"));
                Timestamp createdTs = (Timestamp) row.get("created_at");
                Timestamp lastPolledTs = (Timestamp) row.get("last_status_polled_at");
                if (createdTs != null) {
                    resolvedDate = createdTs.toInstant().atZone(IST).toLocalDate().toString();
                    if (!force && Duration.between(createdTs.toInstant(), now).compareTo(minInitialDelay) < 0) {
                        long waitMins = Math.max(1L,
                                minInitialDelay.minus(Duration.between(createdTs.toInstant(), now)).toMinutes() + 1L);
                        return new ProviderModels.DmtTransferStatus(
                                ProviderModels.Outcome.PENDING,
                                true,
                                "THROTTLED_INITIAL",
                                "Bank status reconfirmation becomes available " + dmtProps.statusInitialDelayMinutes()
                                        + " minutes after transfer initiation (try again in ~" + waitMins + " min).",
                                "",
                                providerTxnId == null ? "" : providerTxnId);
                    }
                }
                if (!force && lastPolledTs != null) {
                    Duration requiredGap = "SUCCESS".equalsIgnoreCase(curStatus)
                            ? MIN_SUCCESS_RECONFIRM_GAP
                            : minPollInterval;
                    if (Duration.between(lastPolledTs.toInstant(), now).compareTo(requiredGap) < 0) {
                        return new ProviderModels.DmtTransferStatus(
                                ProviderModels.Outcome.PENDING,
                                true,
                                "THROTTLED_INTERVAL",
                                "Status was checked recently. Next live bank status query is allowed after the InstantPay interval.",
                                "",
                                providerTxnId == null ? "" : providerTxnId);
                    }
                }
                jdbc.update("""
                        UPDATE dmt_transfers
                           SET last_status_polled_at = ?,
                               status_poll_count = COALESCE(status_poll_count, 0) + 1
                         WHERE external_ref = ?
                        """, Timestamp.from(now), externalRef.trim());
            }
        }

        InstantPayClient.TxnStatusResponse res = client.checkTransactionStatus(resolvedDate, externalRef);
        ProviderModels.Outcome mapped = InstantPayPayoutAdapter.mapOutcome(res.outcome());
        return new ProviderModels.DmtTransferStatus(
                mapped,
                false,
                firstNonBlank(res.transactionStatusCode(), res.statusCode()),
                res.message(),
                res.utr(),
                firstNonBlank(res.txnId(), providerTxnId));
    }

    private static BigDecimal parseDecimalOrNull(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
