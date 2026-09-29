package com.fintech.reports;

import com.fintech.identity.AdminAccess;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminEarningsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_DAYS = 366;
    private static final int MAX_ROWS = 10_000;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;

    public AdminEarningsService(JdbcTemplate jdbc, AdminAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Map<String, Object> filters() {
        requirePlatformStaff();
        Map<String, Object> out = new LinkedHashMap<>();
        StringBuilder hubSql = new StringBuilder("""
                SELECT id, name, code FROM hubs WHERE status = 'ACTIVE'
                """);
        List<Object> hubArgs = new ArrayList<>();
        List<UUID> hubs = access.hubIdsOrNull();
        if (hubs != null) {
            if (hubs.isEmpty()) {
                out.put("hubs", List.of());
                out.put("distributors", List.of());
                out.put("retailers", List.of());
                return out;
            }
            hubSql.append(" AND id IN (");
            for (int i = 0; i < hubs.size(); i++) {
                if (i > 0) {
                    hubSql.append(", ");
                }
                hubSql.append("?");
                hubArgs.add(hubs.get(i));
            }
            hubSql.append(") ");
        }
        hubSql.append(" ORDER BY name ");
        out.put("hubs", jdbc.queryForList(hubSql.toString(), hubArgs.toArray()));

        StringBuilder distSql = new StringBuilder("""
                SELECT u.id, u.full_name, u.code, u.hub_id
                  FROM users u
                 WHERE u.user_type = 'MASTER_DISTRIBUTOR'
                """);
        List<Object> distArgs = new ArrayList<>();
        if (hubs != null) {
            distSql.append(" AND u.hub_id IN (");
            for (int i = 0; i < hubs.size(); i++) {
                if (i > 0) {
                    distSql.append(", ");
                }
                distSql.append("?");
                distArgs.add(hubs.get(i));
            }
            distSql.append(") ");
        }
        distSql.append(" ORDER BY u.full_name ");
        out.put("distributors", jdbc.queryForList(distSql.toString(), distArgs.toArray()));

        StringBuilder retSql = new StringBuilder("""
                SELECT u.id, u.full_name, u.code, u.parent_id, u.hub_id
                  FROM users u
                 WHERE u.user_type = 'RETAILER'
                """);
        List<Object> retArgs = new ArrayList<>();
        if (hubs != null) {
            retSql.append(" AND u.hub_id IN (");
            for (int i = 0; i < hubs.size(); i++) {
                if (i > 0) {
                    retSql.append(", ");
                }
                retSql.append("?");
                retArgs.add(hubs.get(i));
            }
            retSql.append(") ");
        }
        retSql.append(" ORDER BY u.full_name ");
        out.put("retailers", jdbc.queryForList(retSql.toString(), retArgs.toArray()));
        return out;
    }

    public Map<String, Object> query(LocalDate from, LocalDate to,
                                     UUID retailerId, UUID distributorId, UUID hubId) {
        requirePlatformStaff();
        if (from == null || to == null) {
            from = LocalDate.now(IST);
            to = from;
        }
        requireRange(from, to);
        if (hubId != null) {
            access.assertHub(hubId);
        }
        if (retailerId != null) {
            access.assertNetworkUser(retailerId);
        }
        if (distributorId != null) {
            access.assertNetworkUser(distributorId);
        }

        StringBuilder base = new StringBuilder("""
                FROM commission_earnings e
                JOIN transactions t ON t.id = e.transaction_id
                LEFT JOIN users ret ON ret.id = e.retailer_user_id
                LEFT JOIN users dist ON dist.id = e.distributor_user_id
                LEFT JOIN hubs h ON h.id = e.hub_id
                LEFT JOIN users ben ON ben.id = e.beneficiary_user
               WHERE (e.created_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN ? AND ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to));
        access.appendHubFilter(base, args, "e.hub_id");
        if (retailerId != null) {
            base.append(" AND e.retailer_user_id = ? ");
            args.add(retailerId);
        }
        if (distributorId != null) {
            base.append(" AND e.distributor_user_id = ? ");
            args.add(distributorId);
        }
        if (hubId != null) {
            base.append(" AND e.hub_id = ? ");
            args.add(hubId);
        }

        List<Map<String, Object>> totalsRows = jdbc.queryForList("""
                SELECT e.role_in_split, COALESCE(sum(e.amount), 0) AS amount
                """ + base + " GROUP BY e.role_in_split", args.toArray());
        BigDecimal all = BigDecimal.ZERO;
        BigDecimal retailer = BigDecimal.ZERO;
        BigDecimal distributor = BigDecimal.ZERO;
        BigDecimal platform = BigDecimal.ZERO;
        for (Map<String, Object> row : totalsRows) {
            BigDecimal amt = (BigDecimal) row.get("amount");
            all = all.add(amt);
            switch (String.valueOf(row.get("role_in_split"))) {
                case "RETAILER" -> retailer = amt;
                case "DISTRIBUTOR" -> distributor = amt;
                case "PLATFORM" -> platform = amt;
                default -> { }
            }
        }

        String rowSql = """
                SELECT e.id, e.created_at, e.amount, e.role_in_split,
                       t.txn_type, t.id AS transaction_id,
                       e.retailer_user_id, ret.full_name AS retailer_name,
                       e.distributor_user_id, dist.full_name AS distributor_name,
                       e.hub_id, h.name AS hub_name,
                       e.beneficiary_user, ben.full_name AS beneficiary_name
                """ + base + " ORDER BY e.created_at DESC LIMIT " + (MAX_ROWS + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(rowSql, args.toArray());
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("all", all);
        totals.put("retailer", retailer);
        totals.put("distributor", distributor);
        totals.put("platform", platform);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("totals", totals);
        out.put("rows", rows);
        out.put("truncated", truncated);
        return out;
    }

    private void requirePlatformStaff() {
        if (!access.me().platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DATE_RANGE",
                    "To date cannot be before from date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DATE_RANGE_TOO_LONG",
                    "Choose a range of at most " + MAX_DAYS + " days");
        }
    }
}
