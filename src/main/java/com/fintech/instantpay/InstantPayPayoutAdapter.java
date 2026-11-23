package com.fintech.instantpay;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.PayoutPort;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Direct InstantPay Domestic Bank Settlement adapter implementing {@link PayoutPort}.
 *
 * <p>Enforces InstantPay's mandatory {@code /reports/txnStatus} guardrails:
 * <ul>
 *   <li>Pause polling during the {@code 23:30–00:30 IST} nightly bank reconciliation window</li>
 *   <li>Wait at least {@code 30 minutes} after transaction initiation before first status query</li>
 *   <li>Maintain at least {@code 4 hours} between consecutive status queries for the same transaction</li>
 * </ul>
 */
@Component
public class InstantPayPayoutAdapter implements PayoutPort {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MAINT_START = LocalTime.of(23, 30);
    private static final LocalTime MAINT_END = LocalTime.of(0, 30);
    private static final Duration MIN_INITIAL_DELAY = Duration.ofMinutes(30);
    private static final Duration MIN_POLL_INTERVAL = Duration.ofHours(4);

    private final InstantPayClient client;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public InstantPayPayoutAdapter(InstantPayClient client, JdbcTemplate jdbc) {
        this(client, jdbc, Clock.systemUTC());
    }

    public InstantPayPayoutAdapter(InstantPayClient client, JdbcTemplate jdbc, Clock clock) {
        this.client = client;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public String providerCode() {
        return "INSTANTPAY";
    }

    @Override
    public ProviderModels.PayoutResult submit(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.PayoutRequest request) {
        InstantPayClient.PayoutResponse res = client.executePayout(
                channel.outletCode(),
                new InstantPayClient.PayoutPayRequest(
                        request.accountHolder(),
                        request.accountNumber(),
                        request.ifsc(),
                        request.mode(),
                        request.amount(),
                        request.externalRef(),
                        request.latitude(),
                        request.longitude(),
                        request.remarks()));
        return new ProviderModels.PayoutResult(
                mapOutcome(res.outcome()),
                res.statusCode(),
                res.message(),
                res.txnId(),
                res.utr());
    }

    @Override
    public ProviderModels.PayoutStatus checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String externalRef,
            String providerTxnId,
            String txnDate) {
        Instant now = Instant.now(clock);
        LocalTime istTime = now.atZone(IST).toLocalTime();
        if (!istTime.isBefore(MAINT_START) || istTime.isBefore(MAINT_END)) {
            return new ProviderModels.PayoutStatus(
                    ProviderModels.Outcome.PENDING,
                    "MAINTENANCE_WINDOW",
                    "Status check deferred during 23:30–00:30 IST bank reconciliation window",
                    "",
                    providerTxnId == null ? "" : providerTxnId);
        }

        String resolvedDate = (txnDate == null || txnDate.isBlank())
                ? LocalDate.now(clock.withZone(IST)).toString()
                : txnDate.trim();

        if (jdbc != null && externalRef != null && !externalRef.isBlank()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT created_at, last_status_polled_at
                      FROM payout_transfers
                     WHERE external_ref = ?
                    """, externalRef.trim());
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                Timestamp createdTs = (Timestamp) row.get("created_at");
                Timestamp lastPolledTs = (Timestamp) row.get("last_status_polled_at");
                if (createdTs != null) {
                    resolvedDate = createdTs.toInstant().atZone(IST).toLocalDate().toString();
                    if (Duration.between(createdTs.toInstant(), now).compareTo(MIN_INITIAL_DELAY) < 0) {
                        return new ProviderModels.PayoutStatus(
                                ProviderModels.Outcome.PENDING,
                                "THROTTLED_30M",
                                "Awaiting 30-minute InstantPay cooling window before status query",
                                "",
                                providerTxnId == null ? "" : providerTxnId);
                    }
                }
                if (lastPolledTs != null
                        && Duration.between(lastPolledTs.toInstant(), now).compareTo(MIN_POLL_INTERVAL) < 0) {
                    return new ProviderModels.PayoutStatus(
                            ProviderModels.Outcome.PENDING,
                            "THROTTLED_4H",
                            "Awaiting 4-hour InstantPay interval before next status query",
                            "",
                            providerTxnId == null ? "" : providerTxnId);
                }
                jdbc.update(
                        "UPDATE payout_transfers SET last_status_polled_at = ? WHERE external_ref = ?",
                        Timestamp.from(now), externalRef.trim());
            }
        }

        InstantPayClient.TxnStatusResponse res = client.checkTransactionStatus(resolvedDate, externalRef);
        return new ProviderModels.PayoutStatus(
                mapOutcome(res.outcome()),
                firstNonBlank(res.transactionStatusCode(), res.statusCode()),
                res.message(),
                res.utr(),
                firstNonBlank(res.txnId(), providerTxnId));
    }

    public static ProviderModels.Outcome mapOutcome(String raw) {
        String u = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "SUCCESS", "TXN" -> ProviderModels.Outcome.SUCCESS;
            case "FAILED", "REVERSED", "REFUND" -> ProviderModels.Outcome.FAILED;
            default -> ProviderModels.Outcome.PENDING;
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }
}
