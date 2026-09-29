package com.fintech.reports;

import com.fintech.identity.AdminAccess;
import com.fintech.platform.security.AuthPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Sales and earnings headlines + trends, scoped to the signed-in principal. */
@Service
public class DashboardSnapshotService {

    private final JdbcTemplate jdbc;
    private final AdminAccess access;

    public DashboardSnapshotService(JdbcTemplate jdbc, AdminAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Map<String, Object> snapshot(AuthPrincipal me) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("network", network(me));
        out.put("sales", salesHeadline(me));
        out.put("earnings", earningsHeadline(me, false));
        out.put("you", earningsHeadline(me, true));
        out.put("salesTrends", periodTrends(me, "sales"));
        out.put("earningsTrends", periodTrends(me, "earnings"));
        return out;
    }

    private Map<String, Object> network(AuthPrincipal me) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (me.platformStaff()) {
            Clause hubs = hubClause("h.id", me);
            out.put("hubs", count("SELECT count(*) FROM hubs h WHERE " + hubs.sql, hubs.args));
            Clause dist = hubClause("hub_id", me);
            out.put("distributors", count(
                    "SELECT count(*) FROM users WHERE user_type = 'MASTER_DISTRIBUTOR' AND " + dist.sql, dist.args));
            out.put("distributors_active", count(
                    "SELECT count(*) FROM users WHERE user_type = 'MASTER_DISTRIBUTOR' AND status = 'ACTIVE' AND "
                            + dist.sql, dist.args));
            out.put("retailers", count(
                    "SELECT count(*) FROM users WHERE user_type = 'RETAILER' AND " + dist.sql, dist.args));
            out.put("retailers_active", count(
                    "SELECT count(*) FROM users WHERE user_type = 'RETAILER' AND status = 'ACTIVE' AND " + dist.sql,
                    dist.args));
            Clause customers = hubClause("u.hub_id", me);
            out.put("customers", count("""
                    SELECT count(*) FROM customers c JOIN users u ON u.id = c.retailer_user_id WHERE %s
                    """.formatted(customers.sql), customers.args));
            return out;
        }
        if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            out.put("hubs", 0);
            out.put("distributors", 1);
            out.put("distributors_active", 1);
            out.put("retailers", count(
                    "SELECT count(*) FROM users WHERE parent_id = ? AND user_type = 'RETAILER'",
                    new Object[]{me.userId()}));
            out.put("retailers_active", count("""
                    SELECT count(*) FROM users
                     WHERE parent_id = ? AND user_type = 'RETAILER' AND status = 'ACTIVE'
                    """, new Object[]{me.userId()}));
            out.put("customers", count("""
                    SELECT count(*) FROM customers c JOIN users u ON u.id = c.retailer_user_id
                     WHERE u.parent_id = ? OR c.retailer_user_id = ?
                    """, new Object[]{me.userId(), me.userId()}));
            return out;
        }
        out.put("hubs", 0);
        out.put("distributors", 0);
        out.put("distributors_active", 0);
        out.put("retailers", 1);
        out.put("retailers_active", 1);
        out.put("customers", count("SELECT count(*) FROM customers WHERE retailer_user_id = ?",
                new Object[]{me.userId()}));
        return out;
    }

    private Map<String, Object> salesHeadline(AuthPrincipal me) {
        Clause scope = salesClause(me);
        return jdbc.queryForMap("""
                SELECT
                    count(*) FILTER (
                        WHERE (l.created_at AT TIME ZONE 'Asia/Kolkata')::date
                            = (now() AT TIME ZONE 'Asia/Kolkata')::date
                    ) AS created_today,
                    count(*) FILTER (
                        WHERE date_trunc('week', l.created_at AT TIME ZONE 'Asia/Kolkata')
                            = date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')
                    ) AS created_week,
                    count(*) FILTER (
                        WHERE date_trunc('month', l.created_at AT TIME ZONE 'Asia/Kolkata')
                            = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                    ) AS created_month,
                    count(*) AS created_all,
                    count(*) FILTER (
                        WHERE l.state = 'ACTIVATED'
                          AND (l.updated_at AT TIME ZONE 'Asia/Kolkata')::date
                              = (now() AT TIME ZONE 'Asia/Kolkata')::date
                    ) AS activated_today,
                    count(*) FILTER (
                        WHERE l.state = 'ACTIVATED'
                          AND date_trunc('week', l.updated_at AT TIME ZONE 'Asia/Kolkata')
                              = date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')
                    ) AS activated_week,
                    count(*) FILTER (
                        WHERE l.state = 'ACTIVATED'
                          AND date_trunc('month', l.updated_at AT TIME ZONE 'Asia/Kolkata')
                              = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                    ) AS activated_month,
                    count(*) FILTER (WHERE l.state = 'ACTIVATED') AS activated_all
                  FROM sales_leads l
                 WHERE %s
                """.formatted(scope.sql), scope.args);
    }

    private Map<String, Object> earningsHeadline(AuthPrincipal me, boolean ownOnly) {
        Clause scope = ownOnly ? ownEarningsClause(me) : earningsClause(me);
        return jdbc.queryForMap("""
                SELECT
                    COALESCE(sum(e.amount) FILTER (
                        WHERE (e.created_at AT TIME ZONE 'Asia/Kolkata')::date
                            = (now() AT TIME ZONE 'Asia/Kolkata')::date
                    ), 0) AS today,
                    COALESCE(sum(e.amount) FILTER (
                        WHERE date_trunc('week', e.created_at AT TIME ZONE 'Asia/Kolkata')
                            = date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')
                    ), 0) AS week,
                    COALESCE(sum(e.amount) FILTER (
                        WHERE date_trunc('month', e.created_at AT TIME ZONE 'Asia/Kolkata')
                            = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                    ), 0) AS month,
                    COALESCE(sum(e.amount), 0) AS all_time,
                    COALESCE(sum(e.amount) FILTER (WHERE e.role_in_split = 'RETAILER'), 0) AS retailer,
                    COALESCE(sum(e.amount) FILTER (WHERE e.role_in_split = 'DISTRIBUTOR'), 0) AS distributor,
                    COALESCE(sum(e.amount) FILTER (WHERE e.role_in_split = 'PLATFORM'), 0) AS platform
                  FROM commission_earnings e
                 WHERE %s
                """.formatted(scope.sql), scope.args);
    }

    private Map<String, Object> periodTrends(AuthPrincipal me, String kind) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("daily", kind.equals("sales")
                ? salesTrend(me, "(now() AT TIME ZONE 'Asia/Kolkata')::date - 13", "1 day",
                        "(l.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period",
                        "l.state = 'ACTIVATED' AND (l.updated_at AT TIME ZONE 'Asia/Kolkata')::date = b.period")
                : earningsTrend(me, "(now() AT TIME ZONE 'Asia/Kolkata')::date - 13", "1 day",
                        "(e.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        out.put("weekly", kind.equals("sales")
                ? salesTrend(me, "date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')::date - interval '11 weeks'", "1 week",
                        "date_trunc('week', l.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period",
                        "l.state = 'ACTIVATED' AND date_trunc('week', l.updated_at AT TIME ZONE 'Asia/Kolkata')::date = b.period")
                : earningsTrend(me, "date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')::date - interval '11 weeks'", "1 week",
                        "date_trunc('week', e.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        out.put("monthly", kind.equals("sales")
                ? salesTrend(me, "date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')::date - interval '11 months'", "1 month",
                        "date_trunc('month', l.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period",
                        "l.state = 'ACTIVATED' AND date_trunc('month', l.updated_at AT TIME ZONE 'Asia/Kolkata')::date = b.period")
                : earningsTrend(me, "date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')::date - interval '11 months'", "1 month",
                        "date_trunc('month', e.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        return out;
    }

    private List<Map<String, Object>> salesTrend(AuthPrincipal me, String startExpr, String step,
                                                 String createdOn, String activatedOn) {
        Clause scope = salesClause(me);
        return jdbc.queryForList("""
                WITH buckets AS (
                    SELECT generate_series(%s, (now() AT TIME ZONE 'Asia/Kolkata')::date, interval '%s')::date AS period
                )
                SELECT b.period,
                       count(l.id) FILTER (WHERE %s) AS created,
                       count(l.id) FILTER (WHERE %s) AS activated
                  FROM buckets b
                  LEFT JOIN sales_leads l
                         ON ((%s) OR (%s)) AND (%s)
                 GROUP BY 1
                 ORDER BY 1
                """.formatted(startExpr, step, createdOn, activatedOn, createdOn, activatedOn, scope.sql), scope.args);
    }

    private List<Map<String, Object>> earningsTrend(AuthPrincipal me, String startExpr, String step, String joinOn) {
        Clause scope = earningsClause(me);
        return jdbc.queryForList("""
                WITH buckets AS (
                    SELECT generate_series(%s, (now() AT TIME ZONE 'Asia/Kolkata')::date, interval '%s')::date AS period
                )
                SELECT b.period,
                       COALESCE(sum(e.amount), 0) AS amount,
                       COALESCE(sum(e.amount) FILTER (WHERE e.role_in_split = 'RETAILER'), 0) AS retailer,
                       COALESCE(sum(e.amount) FILTER (WHERE e.role_in_split = 'DISTRIBUTOR'), 0) AS distributor,
                       COALESCE(sum(e.amount) FILTER (WHERE e.role_in_split = 'PLATFORM'), 0) AS platform
                  FROM buckets b
                  LEFT JOIN commission_earnings e ON (%s) AND (%s)
                 GROUP BY 1
                 ORDER BY 1
                """.formatted(startExpr, step, joinOn, scope.sql), scope.args);
    }

    private Clause salesClause(AuthPrincipal me) {
        if (me.platformStaff()) {
            return hubClause("l.hub_id", me);
        }
        if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            return new Clause("l.distributor_user_id = ? OR l.retailer_user_id = ?",
                    new Object[]{me.userId(), me.userId()});
        }
        return new Clause("l.retailer_user_id = ?", new Object[]{me.userId()});
    }

    private Clause earningsClause(AuthPrincipal me) {
        if (me.platformStaff()) {
            return hubClause("e.hub_id", me);
        }
        if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            return new Clause("e.distributor_user_id = ?", new Object[]{me.userId()});
        }
        return new Clause("e.retailer_user_id = ?", new Object[]{me.userId()});
    }

    private Clause ownEarningsClause(AuthPrincipal me) {
        return new Clause("e.beneficiary_user = ?", new Object[]{me.userId()});
    }

    private Clause hubClause(String column, AuthPrincipal me) {
        if (me.superAdmin()) {
            return Clause.ALWAYS;
        }
        List<UUID> hubs = access.hubIdsOrNull();
        if (hubs == null) {
            return Clause.ALWAYS;
        }
        if (hubs.isEmpty()) {
            return Clause.NEVER;
        }
        StringBuilder sql = new StringBuilder(column).append(" IN (");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < hubs.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            args.add(hubs.get(i));
        }
        sql.append(")");
        return new Clause(sql.toString(), args.toArray());
    }

    private long count(String sql, Object[] args) {
        Long value = args.length == 0 ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, args, Long.class);
        return value == null ? 0 : value;
    }

    private record Clause(String sql, Object[] args) {
        static final Clause ALWAYS = new Clause("TRUE", new Object[]{});
        static final Clause NEVER = new Clause("FALSE", new Object[]{});
    }
}
