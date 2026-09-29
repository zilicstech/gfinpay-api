package com.fintech.reports;

import com.fintech.identity.AdminAccess;
import com.fintech.platform.web.ApiException;
import com.fintech.sales.SaleNetworkSnapshot;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SalesAdminReportHandler implements AdminReportHandler {

    private static final int MAX_DAYS = 366;
    private static final int MAX_ROWS = 10_000;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;

    public SalesAdminReportHandler(JdbcTemplate jdbc, AdminAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Override
    public String reportType() {
        return "SALES";
    }

    @Override
    public String label() {
        return "Sales Report";
    }

    @Override
    public Map<String, Object> filters() {
        access.requireSuperAdmin();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saleTypes", jdbc.queryForList("""
                SELECT code, name FROM catalog_categories ORDER BY sort_order, name
                """));
        out.put("saleProviders", List.of(
                Map.of("code", "ZET", "name", "ZET"),
                Map.of("code", "PAYSPRINT", "name", "PaySprint"),
                Map.of("code", "GROWMORE", "name", "GrowMore"),
                Map.of("code", "OTHERS", "name", "Others")));
        out.put("statuses", List.of(
                "LINK_CREATED", "OPENED", "IN_PROGRESS", "CONVERTED", "ACTIVATED", "REJECTED", "EXPIRED"));
        out.put("retailers", jdbc.queryForList("""
                SELECT u.id, u.full_name, u.code, u.parent_id, u.hub_id
                  FROM users u
                 WHERE u.user_type = 'RETAILER'
                 ORDER BY u.full_name
                """));
        out.put("distributors", jdbc.queryForList("""
                SELECT u.id, u.full_name, u.code, u.hub_id
                  FROM users u
                 WHERE u.user_type = 'MASTER_DISTRIBUTOR'
                 ORDER BY u.full_name
                """));
        out.put("hubs", jdbc.queryForList("""
                SELECT id, name, code FROM hubs WHERE status = 'ACTIVE' ORDER BY name
                """));
        return out;
    }

    @Override
    public Map<String, Object> run(AdminReportQuery query) {
        access.requireSuperAdmin();
        requireRange(query.from(), query.to());
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.state, l.sale_type, l.sale_provider, l.budget,
                       l.created_at, l.updated_at, l.retailer_user_id, l.distributor_user_id, l.hub_id,
                       i.name AS item_name, i.code AS item_code,
                       cat.name AS sale_type_name,
                       c.full_name AS customer_name, c.mobile AS customer_mobile,
                       r.full_name AS retailer_name,
                       d.full_name AS distributor_name,
                       h.name AS hub_name
                  FROM sales_leads l
                  JOIN catalog_items i ON i.id = l.catalog_item_id
                  JOIN catalog_categories cat ON cat.code = l.sale_type
                  JOIN customers c ON c.id = l.customer_id
                  JOIN users r ON r.id = l.retailer_user_id
                  LEFT JOIN users d ON d.id = l.distributor_user_id
                  LEFT JOIN hubs h ON h.id = l.hub_id
                 WHERE (l.created_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN ? AND ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(query.from()));
        args.add(Date.valueOf(query.to()));
        appendFilters(sql, args, query);
        sql.append(" ORDER BY l.created_at DESC LIMIT ").append(MAX_ROWS + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }

        StringBuilder countSql = new StringBuilder("""
                SELECT count(*)::int
                  FROM sales_leads l
                 WHERE (l.created_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN ? AND ?
                """);
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(Date.valueOf(query.from()));
        countArgs.add(Date.valueOf(query.to()));
        appendFilters(countSql, countArgs, query);
        Integer total = jdbc.queryForObject(countSql.toString(), Integer.class, countArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportType", reportType());
        out.put("label", label());
        out.put("from", query.from().toString());
        out.put("to", query.to().toString());
        out.put("total", total == null ? rows.size() : total);
        out.put("truncated", truncated);
        out.put("rows", rows);
        return out;
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, AdminReportQuery query) {
        if (notBlank(query.saleType())) {
            sql.append(" AND l.sale_type = ? ");
            args.add(query.saleType().trim().toUpperCase(Locale.ROOT));
        }
        if (notBlank(query.saleProvider())) {
            sql.append(" AND l.sale_provider = ? ");
            args.add(SaleNetworkSnapshot.normalizeProvider(query.saleProvider()));
        }
        if (notBlank(query.status())) {
            sql.append(" AND l.state = ? ");
            args.add(query.status().trim().toUpperCase(Locale.ROOT));
        }
        if (query.retailerId() != null) {
            sql.append(" AND l.retailer_user_id = ? ");
            args.add(query.retailerId());
        }
        if (query.distributorId() != null) {
            sql.append(" AND l.distributor_user_id = ? ");
            args.add(query.distributorId());
        }
        if (query.hubId() != null) {
            sql.append(" AND l.hub_id = ? ");
            args.add(query.hubId());
        }
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DATE_RANGE_REQUIRED",
                    "Choose a from and to date");
        }
        if (to.isBefore(from)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DATE_RANGE",
                    "To date cannot be before from date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DATE_RANGE_TOO_LONG",
                    "Choose a range of at most " + MAX_DAYS + " days");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
