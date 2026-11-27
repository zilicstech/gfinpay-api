package com.fintech.instantpay;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.AepsPort;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Direct InstantPay implementation of {@link AepsPort} covering:
 * cached {@code GET /fi/aeps/banks}, {@code outletLoginStatus}, {@code outletLogin},
 * {@code >₹5,000 transactionOtp}, {@code cashWithdrawal}, {@code balanceInquiry},
 * {@code miniStatement}, {@code cashDeposit}, and {@code /reports/txnStatus}.
 */
@Component
public class InstantPayAepsAdapter implements AepsPort {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration MIN_INITIAL_DELAY = Duration.ofMinutes(30);
    private static final Duration MIN_POLL_INTERVAL = Duration.ofHours(4);
    private static final LocalTime MAINT_START = LocalTime.of(23, 30);
    private static final LocalTime MAINT_END = LocalTime.of(0, 30);

    private final InstantPayClient client;
    private final InstantPayProperties props;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    private volatile List<ProviderModels.AepsBank> cachedBanks = List.of();
    private volatile Instant cachedBanksExpiresAt = Instant.EPOCH;

    public InstantPayAepsAdapter(InstantPayClient client, InstantPayProperties props) {
        this(client, props, null, Clock.systemUTC());
    }

    @Autowired
    public InstantPayAepsAdapter(InstantPayClient client, InstantPayProperties props, JdbcTemplate jdbc) {
        this(client, props, jdbc, Clock.systemUTC());
    }

    public InstantPayAepsAdapter(InstantPayClient client, InstantPayProperties props, JdbcTemplate jdbc, Clock clock) {
        this.client = client;
        this.props = props;
        this.jdbc = jdbc;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String providerCode() {
        return "INSTANTPAY";
    }

    @Override
    public List<ProviderModels.AepsBank> listBanks(ServiceProviderRouter.RoutedChannel channel) {
        Instant now = Instant.now();
        List<ProviderModels.AepsBank> snapshot = cachedBanks;
        if (!snapshot.isEmpty() && now.isBefore(cachedBanksExpiresAt)) {
            return snapshot;
        }
        String outletId = resolveOutletId(channel);
        InstantPayClient.AepsBankListResponse res = client.listAepsBanks(outletId);
        if (res.success() && !res.banks().isEmpty()) {
            List<ProviderModels.AepsBank> fresh = res.banks().stream()
                    .filter(InstantPayClient.AepsBankItem::aepsEnabled)
                    .map(b -> new ProviderModels.AepsBank(
                            b.bankId(),
                            b.iin(),
                            b.name(),
                            b.aepsEnabled(),
                            b.cwOtpMin(),
                            b.failureRatePct()))
                    .toList();
            if (!fresh.isEmpty()) {
                cachedBanks = fresh;
                cachedBanksExpiresAt = now.plus(Duration.ofMinutes(props.aepsBankCacheMinutes()));
                return fresh;
            }
        }
        return snapshot;
    }

    public void evictBankCache() {
        cachedBanks = List.of();
        cachedBanksExpiresAt = Instant.EPOCH;
    }

    @Override
    public ProviderModels.AepsLoginStatus checkOutletLoginStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String serviceType) {
        String outletId = resolveOutletId(channel);
        InstantPayClient.AepsOutletLoginResponse res = client.checkAepsOutletLoginStatus(outletId, serviceType);
        return new ProviderModels.AepsLoginStatus(
                res.loggedIn(),
                res.loginRequired(),
                res.dormantOutlet(),
                res.aadhaarLastFour(),
                res.txnBioLoginRequired(),
                res.faceAuthAvailable(),
                res.irisAuthAvailable(),
                res.statusCode(),
                res.actCode(),
                res.message(),
                res.providerTxnId());
    }

    @Override
    public ProviderModels.AepsLoginStatus submitOutletLogin(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.AepsOutletLoginRequest request) {
        String outletId = resolveOutletId(channel);
        String encAadhaar = InstantPayCrypto.encrypt(request.merchantAadhaar12(), props.encryptionKey());
        Map<String, Object> bio25 = request.pid().toInstantPayAepsBiometricData(encAadhaar, request.captureType());
        InstantPayClient.AepsOutletLoginResponse res = client.submitAepsOutletLogin(
                outletId,
                request.loginType(),
                request.captureType(),
                request.latitude(),
                request.longitude(),
                request.externalRef(),
                bio25);
        return new ProviderModels.AepsLoginStatus(
                res.loggedIn(),
                res.loginRequired(),
                res.dormantOutlet(),
                res.aadhaarLastFour(),
                res.txnBioLoginRequired(),
                res.faceAuthAvailable(),
                res.irisAuthAvailable(),
                res.statusCode(),
                res.actCode(),
                res.message(),
                res.providerTxnId());
    }

    @Override
    public ProviderModels.AepsOtpChallenge requestTransactionOtp(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.AepsOtpRequest request) {
        String outletId = resolveOutletId(channel);
        InstantPayClient.AepsTxnOtpResponse res = client.requestAepsTransactionOtp(
                outletId,
                request.bankIin(),
                request.customerAadhaar12(),
                request.amountRupees(),
                request.customerMobile(),
                request.latitude(),
                request.longitude(),
                request.externalRef());
        return new ProviderModels.AepsOtpChallenge(
                res.success(),
                res.otpRequired(),
                res.loginRequired(),
                res.dormantOutlet(),
                res.referenceKey(),
                res.validity(),
                res.statusCode(),
                res.message());
    }

    @Override
    public ProviderModels.AepsResult transact(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.AepsTxnRequest request) {
        String outletId = resolveOutletId(channel);
        String encAadhaar = InstantPayCrypto.encrypt(request.customerAadhaar12(), props.encryptionKey());
        String captureType = (request.captureType() == null || request.captureType().isBlank())
                ? "FINGER"
                : request.captureType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> bio25 = request.pid().toInstantPayAepsBiometricData(encAadhaar, captureType);
        String type = request.aepsType() == null ? "" : request.aepsType().trim().toUpperCase(Locale.ROOT);
        String amtStr = request.amount() == null ? "0" : request.amount().toPlainString();

        InstantPayClient.AepsTxnResponse res = switch (type) {
            case "CASH_WITHDRAWAL" -> client.aepsCashWithdrawal(
                    outletId, request.bankIin(), request.customerMobile(), amtStr,
                    request.externalRef(), request.referenceKey(), captureType,
                    request.latitude(), request.longitude(), bio25);
            case "BALANCE_ENQUIRY" -> client.aepsBalanceInquiry(
                    outletId, request.bankIin(), request.customerMobile(),
                    request.externalRef(), captureType, request.latitude(), request.longitude(), bio25);
            case "MINI_STATEMENT" -> client.aepsMiniStatement(
                    outletId, request.bankIin(), request.customerMobile(),
                    request.externalRef(), captureType, request.latitude(), request.longitude(), bio25);
            case "CASH_DEPOSIT" -> client.aepsCashDeposit(
                    outletId, request.bankIin(), request.customerMobile(), amtStr,
                    request.externalRef(), captureType, request.latitude(), request.longitude(), bio25);
            default -> throw new IllegalArgumentException("Unsupported InstantPay AePS transaction type: " + type);
        };

        ProviderModels.Outcome outcome = switch (res.outcome()) {
            case "SUCCESS" -> ProviderModels.Outcome.SUCCESS;
            case "PENDING" -> ProviderModels.Outcome.PENDING;
            default -> ProviderModels.Outcome.FAILED;
        };

        String resolvedTxnId = !res.orderId().isBlank() ? res.orderId() : res.ipayId();
        Map<String, Object> canonicalData = new LinkedHashMap<>();
        canonicalData.put("txnId", resolvedTxnId);
        canonicalData.put("ipayId", res.ipayId());
        canonicalData.put("orderId", res.orderId());
        canonicalData.put("rrn", res.operatorId());
        canonicalData.put("bankName", res.bankName());
        canonicalData.put("accountNumber", res.accountNumber());
        canonicalData.put("availableBalance", res.bankAccountBalance());
        canonicalData.put("transactionValue", res.transactionValue());
        canonicalData.put("payableValue", res.payableValue());
        canonicalData.put("transactionMode", res.transactionMode());
        canonicalData.put("isOnusTxn", res.isOnusTxn());
        canonicalData.put("miniStatement", res.miniStatement());
        canonicalData.put("loginRequired", res.loginRequired());
        canonicalData.put("dormantOutlet", res.dormantOutlet());
        canonicalData.put("biometricMismatch", res.biometricMismatch());

        return new ProviderModels.AepsResult(
                outcome == ProviderModels.Outcome.SUCCESS,
                outcome,
                res.statusCode(),
                res.message(),
                canonicalData);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProviderModels.AepsResult transact(
            ServiceProviderRouter.RoutedChannel channel,
            Map<String, Object> payload) {
        String outletId = resolveOutletId(channel);
        String type = String.valueOf(payload.getOrDefault("txnType", "BALANCE_ENQUIRY")).toUpperCase(Locale.ROOT);
        String bankIin = String.valueOf(payload.getOrDefault("bankIin", ""));
        String mobile = String.valueOf(payload.getOrDefault("mobile", ""));
        String amount = String.valueOf(payload.getOrDefault("amount", "0"));
        String externalRef = String.valueOf(payload.getOrDefault("externalRef", ""));
        String referenceKey = String.valueOf(payload.getOrDefault("referenceKey", ""));
        String captureType = String.valueOf(payload.getOrDefault("captureType", "FINGER"));
        String lat = String.valueOf(payload.getOrDefault("latitude", "19.0760"));
        String lng = String.valueOf(payload.getOrDefault("longitude", "72.8777"));
        Map<String, Object> bio = payload.get("biometricData") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();
        Object rawAadhaar = bio.get("encryptedAadhaar");
        if (rawAadhaar != null && String.valueOf(rawAadhaar).matches("[0-9]{12}")) {
            bio.put("encryptedAadhaar", InstantPayCrypto.encrypt(String.valueOf(rawAadhaar), props.encryptionKey()));
        }

        InstantPayClient.AepsTxnResponse res = "CASH_WITHDRAWAL".equals(type)
                ? client.aepsCashWithdrawal(outletId, bankIin, mobile, amount, externalRef, referenceKey, captureType, lat, lng, bio)
                : ("MINI_STATEMENT".equals(type)
                        ? client.aepsMiniStatement(outletId, bankIin, mobile, externalRef, captureType, lat, lng, bio)
                        : client.aepsBalanceInquiry(outletId, bankIin, mobile, externalRef, captureType, lat, lng, bio));

        ProviderModels.Outcome outcome = "SUCCESS".equals(res.outcome())
                ? ProviderModels.Outcome.SUCCESS
                : ("PENDING".equals(res.outcome()) ? ProviderModels.Outcome.PENDING : ProviderModels.Outcome.FAILED);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("txnId", !res.orderId().isBlank() ? res.orderId() : res.ipayId());
        data.put("rrn", res.operatorId());
        data.put("availableBalance", res.bankAccountBalance());
        data.put("miniStatement", res.miniStatement());
        data.put("accountNumber", res.accountNumber());
        data.put("loginRequired", res.loginRequired());
        data.put("dormantOutlet", res.dormantOutlet());
        data.put("biometricMismatch", res.biometricMismatch());
        return new ProviderModels.AepsResult(outcome == ProviderModels.Outcome.SUCCESS, outcome, res.statusCode(), res.message(), data);
    }

    @Override
    public ProviderModels.AepsStatus checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String externalRef,
            LocalDate transactionDate) {
        Instant now = Instant.now(clock);
        LocalTime istTime = now.atZone(IST).toLocalTime();
        if (!istTime.isBefore(MAINT_START) || istTime.isBefore(MAINT_END)) {
            return new ProviderModels.AepsStatus(
                    ProviderModels.Outcome.PENDING,
                    "MAINTENANCE_WINDOW",
                    "Status check deferred during 23:30–00:30 IST bank reconciliation window",
                    "",
                    "");
        }

        String resolvedDate = (transactionDate != null ? transactionDate : LocalDate.now(clock.withZone(IST))).format(DATE_FMT);

        if (jdbc != null && externalRef != null && !externalRef.isBlank()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT created_at, last_status_polled_at
                      FROM aeps_transactions
                     WHERE external_ref = ?
                    """, externalRef.trim());
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                Timestamp createdTs = (Timestamp) row.get("created_at");
                Timestamp lastPolledTs = (Timestamp) row.get("last_status_polled_at");
                if (createdTs != null) {
                    resolvedDate = createdTs.toInstant().atZone(IST).toLocalDate().format(DATE_FMT);
                    if (Duration.between(createdTs.toInstant(), now).compareTo(MIN_INITIAL_DELAY) < 0) {
                        return new ProviderModels.AepsStatus(
                                ProviderModels.Outcome.PENDING,
                                "THROTTLED_30M",
                                "Awaiting 30-minute InstantPay cooling window before status query",
                                "",
                                "");
                    }
                }
                if (lastPolledTs != null
                        && Duration.between(lastPolledTs.toInstant(), now).compareTo(MIN_POLL_INTERVAL) < 0) {
                    return new ProviderModels.AepsStatus(
                            ProviderModels.Outcome.PENDING,
                            "THROTTLED_4H",
                            "Awaiting 4-hour InstantPay interval before next status query",
                            "",
                            "");
                }
                jdbc.update(
                        "UPDATE aeps_transactions SET last_status_polled_at = ? WHERE external_ref = ?",
                        Timestamp.from(now), externalRef.trim());
            }
        }

        InstantPayClient.TxnStatusResponse res = client.checkTransactionStatus(resolvedDate, externalRef);
        ProviderModels.Outcome outcome = switch (res.outcome()) {
            case "SUCCESS" -> ProviderModels.Outcome.SUCCESS;
            case "FAILED" -> ProviderModels.Outcome.FAILED;
            default -> ProviderModels.Outcome.PENDING;
        };
        String code = !res.transactionStatusCode().isBlank() ? res.transactionStatusCode() : res.statusCode();
        return new ProviderModels.AepsStatus(outcome, code, res.message(), res.utr(), res.txnId());
    }

    private static String resolveOutletId(ServiceProviderRouter.RoutedChannel channel) {
        if (channel.pipeOutletCode() != null && !channel.pipeOutletCode().isBlank()) {
            return channel.pipeOutletCode().trim();
        }
        return channel.outletCode() == null ? "" : channel.outletCode().trim();
    }
}
