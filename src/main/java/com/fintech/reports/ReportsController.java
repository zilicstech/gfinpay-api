package com.fintech.reports;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only aggregations over the internal ledger — reports never query partner APIs. */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class ReportsController {

    private static final String SERVICE_TYPES = """
            ARRAY['DMT','CASHOUT_UPI','CASHOUT_AEPS','FD_CARD_FEE','WALLET_TOPUP','BBPS']::text[]
            """;

    private final JdbcTemplate jdbc;
    private final AdminReportFactory reports;
    private final DashboardSnapshotService snapshot;

    public ReportsController(JdbcTemplate jdbc, AdminReportFactory reports, DashboardSnapshotService snapshot) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.snapshot = snapshot;
    }

    @GetMapping("/types")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, String>>> types() {
        return ApiResponse.ok(reports.types());
    }

    @GetMapping("/{reportType}/filters")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> filters(@PathVariable String reportType) {
        return ApiResponse.ok(reports.forType(reportType).filters());
    }

    @GetMapping("/query")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> query(
            @RequestParam String reportType,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String saleType,
            @RequestParam(required = false) String saleProvider,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String retailerId,
            @RequestParam(required = false) String distributorId,
            @RequestParam(required = false) String hubId) {
        AdminReportQuery q = new AdminReportQuery(
                parseDate(from, "from"),
                parseDate(to, "to"),
                saleType,
                saleProvider,
                status,
                parseUuid(retailerId),
                parseUuid(distributorId),
                parseUuid(hubId));
        return ApiResponse.ok(reports.forType(reportType).run(q));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> summary(@AuthenticationPrincipal AuthPrincipal me) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gmv", jdbc.queryForObject(
                "SELECT COALESCE(sum(amount),0) FROM transactions WHERE txn_type='DMT' AND state='SUCCESS'",
                BigDecimal.class));
        out.put("feeIncome", jdbc.queryForObject(
                "SELECT COALESCE(sum(fee),0) FROM transactions WHERE state='SUCCESS'",
                BigDecimal.class));
        out.put("walletFloat", jdbc.queryForObject(
                "SELECT COALESCE(sum(available_balance + hold_balance),0) FROM wallets", BigDecimal.class));
        out.put("transactionsByState", jdbc.queryForList("""
                SELECT txn_type::text AS txn_type, state::text AS state, count(*) AS count, COALESCE(sum(amount),0) AS amount
                  FROM transactions GROUP BY txn_type, state ORDER BY txn_type, state
                """));
        out.put("commissionByRole", jdbc.queryForList("""
                SELECT role_in_split, COALESCE(sum(amount),0) AS amount, count(*) AS entries
                  FROM commission_earnings GROUP BY role_in_split ORDER BY role_in_split
                """));
        out.put("topRetailers", jdbc.queryForList("""
                SELECT u.full_name, count(t.id) AS txn_count, COALESCE(sum(t.amount),0) AS volume
                  FROM transactions t JOIN users u ON u.id = t.agent_user_id
                 WHERE t.state = 'SUCCESS'
                 GROUP BY u.full_name ORDER BY volume DESC LIMIT 5
                """));
        out.put("serviceTotals", jdbc.queryForList("""
                SELECT txn_type::text AS txn_type,
                       COALESCE(sum(amount) FILTER (
                           WHERE (created_at AT TIME ZONE 'Asia/Kolkata')::date
                               = (now() AT TIME ZONE 'Asia/Kolkata')::date
                       ), 0) AS today,
                       COALESCE(sum(amount) FILTER (
                           WHERE date_trunc('week', created_at AT TIME ZONE 'Asia/Kolkata')
                               = date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')
                       ), 0) AS week,
                       COALESCE(sum(amount) FILTER (
                           WHERE date_trunc('month', created_at AT TIME ZONE 'Asia/Kolkata')
                               = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                       ), 0) AS month,
                       COALESCE(sum(amount), 0) AS all_time,
                       count(*) FILTER (
                           WHERE (created_at AT TIME ZONE 'Asia/Kolkata')::date
                               = (now() AT TIME ZONE 'Asia/Kolkata')::date
                       ) AS today_count,
                       count(*) FILTER (
                           WHERE date_trunc('week', created_at AT TIME ZONE 'Asia/Kolkata')
                               = date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')
                       ) AS week_count,
                       count(*) FILTER (
                           WHERE date_trunc('month', created_at AT TIME ZONE 'Asia/Kolkata')
                               = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                       ) AS month_count
                  FROM transactions
                 WHERE state = 'SUCCESS'
                   AND txn_type = ANY (%s)
                 GROUP BY txn_type
                 ORDER BY txn_type
                """.formatted(SERVICE_TYPES)));
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("daily", trend(
                "(now() AT TIME ZONE 'Asia/Kolkata')::date - 13",
                "1 day",
                "(t.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        trends.put("weekly", trend(
                "date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')::date - interval '11 weeks'",
                "1 week",
                "date_trunc('week', t.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        trends.put("monthly", trend(
                "date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')::date - interval '11 months'",
                "1 month",
                "date_trunc('month', t.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        out.put("trends", trends);
        out.putAll(snapshot.snapshot(me));
        return ApiResponse.ok(out);
    }

    private List<Map<String, Object>> trend(String startExpr, String step, String joinOn) {
        return jdbc.queryForList("""
                WITH buckets AS (
                    SELECT generate_series(%s, (now() AT TIME ZONE 'Asia/Kolkata')::date, interval '%s')::date AS period
                ),
                types AS (
                    SELECT unnest(%s) AS txn_type
                )
                SELECT b.period, types.txn_type::text AS txn_type,
                       COALESCE(sum(t.amount), 0) AS amount,
                       count(t.id) AS txn_count
                  FROM buckets b
                  CROSS JOIN types
                  LEFT JOIN transactions t
                         ON t.txn_type = types.txn_type
                        AND t.state = 'SUCCESS'
                        AND %s
                 GROUP BY 1, 2
                 ORDER BY 1, 2
                """.formatted(startExpr, step, SERVICE_TYPES, joinOn));
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DATE_RANGE_REQUIRED",
                    "Choose a " + field + " date");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DATE",
                    "Use YYYY-MM-DD for " + field);
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ID", "Invalid filter id");
        }
    }
}
