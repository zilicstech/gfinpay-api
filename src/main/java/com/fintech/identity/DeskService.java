package com.fintech.identity;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.reports.DashboardSnapshotService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scoped desk operations for distributors and retailers. */
@Service
public class DeskService {

    private static final Logger log = LoggerFactory.getLogger(DeskService.class);
    private static final String IST_TODAY = "(now() AT TIME ZONE 'Asia/Kolkata')::date";
    private static final String IST_MONTH =
            "date_trunc('month', created_at AT TIME ZONE 'Asia/Kolkata') = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')";
    private static final String SERVICE_TYPES = """
            ARRAY['DMT','CASHOUT_UPI','CASHOUT_AEPS','FD_CARD_FEE','WALLET_TOPUP','BBPS']::text[]
            """;

    private final JdbcTemplate jdbc;
    private final AdminPlatformService admin;
    private final DashboardSnapshotService snapshot;

    public DeskService(JdbcTemplate jdbc, AdminPlatformService admin, DashboardSnapshotService snapshot) {
        this.jdbc = jdbc;
        this.admin = admin;
        this.snapshot = snapshot;
    }

    public Map<String, Object> overview(AuthPrincipal me) {
        long startMs = System.currentTimeMillis();
        Network net = agents(me, "t.agent_user_id");
        Map<String, Object> out = new LinkedHashMap<>();
        boolean dist = isDistributor(me);
        out.put("scope", dist ? "NETWORK" : "OUTLET");

        out.put("gmvToday", decimal("""
                SELECT COALESCE(sum(t.amount),0) FROM transactions t
                 WHERE %s AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                   AND (t.created_at AT TIME ZONE 'Asia/Kolkata')::date = %s
                """.formatted(net.sql, IST_TODAY), net.args));
        out.put("gmvMonth", decimal("""
                SELECT COALESCE(sum(t.amount),0) FROM transactions t
                 WHERE %s AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                   AND %s
                """.formatted(net.sql, IST_MONTH.replace("created_at", "t.created_at")), net.args));
        out.put("gmvAll", decimal("""
                SELECT COALESCE(sum(t.amount),0) FROM transactions t
                 WHERE %s AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                """.formatted(net.sql), net.args));
        out.put("txnToday", integer("""
                SELECT count(*) FROM transactions t
                 WHERE %s AND (t.created_at AT TIME ZONE 'Asia/Kolkata')::date = %s
                """.formatted(net.sql, IST_TODAY), net.args));
        out.put("txnMonth", integer("""
                SELECT count(*) FROM transactions t
                 WHERE %s AND %s
                """.formatted(net.sql, IST_MONTH.replace("created_at", "t.created_at")), net.args));

        Map<String, Object> rates = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE t.state = 'SUCCESS') AS success_count,
                       count(*) FILTER (WHERE t.state = 'FAILED') AS failed_count,
                       count(*) AS total_count
                  FROM transactions t
                 WHERE %s AND t.txn_type = 'DMT' AND %s
                """.formatted(net.sql, IST_MONTH.replace("created_at", "t.created_at")), net.args);
        long success = ((Number) rates.get("success_count")).longValue();
        long total = ((Number) rates.get("total_count")).longValue();
        long failed = ((Number) rates.get("failed_count")).longValue();
        out.put("successCountMonth", success);
        out.put("failedCountMonth", failed);
        out.put("successRateMonth", rate(success, total));
        out.put("feeMonth", decimal("""
                SELECT COALESCE(sum(t.fee),0) FROM transactions t
                 WHERE %s AND t.state = 'SUCCESS' AND t.txn_type = 'DMT' AND %s
                """.formatted(net.sql, IST_MONTH.replace("created_at", "t.created_at")), net.args));

        out.put("commissionEarned", decimal(
                "SELECT COALESCE(sum(amount),0) FROM commission_earnings WHERE beneficiary_user = ?",
                new Object[]{me.userId()}));
        out.put("commissionMonth", decimal("""
                SELECT COALESCE(sum(amount),0) FROM commission_earnings
                 WHERE beneficiary_user = ? AND %s
                """.formatted(IST_MONTH), new Object[]{me.userId()}));

        if (dist) {
            out.put("outletCount", integer(
                    "SELECT count(*) FROM users WHERE parent_id = ? AND user_type = 'RETAILER'",
                    new Object[]{me.userId()}));
            out.put("activeOutlets", integer("""
                    SELECT count(*) FROM users
                     WHERE parent_id = ? AND user_type = 'RETAILER' AND status = 'ACTIVE'
                    """, new Object[]{me.userId()}));
            out.put("pendingKyc", integer("""
                    SELECT count(*) FROM agent_kyc_profiles k
                      JOIN users u ON u.id = k.user_id
                     WHERE u.parent_id = ? AND k.kyc_status <> 'VERIFIED'
                    """, new Object[]{me.userId()}));
            out.put("walletFloat", decimal("""
                    SELECT COALESCE(sum(w.available_balance + w.hold_balance),0)
                      FROM wallets w JOIN users u ON u.id = w.user_id
                     WHERE u.parent_id = ?
                    """, new Object[]{me.userId()}));
            out.put("walletAvailable", decimal("""
                    SELECT COALESCE(sum(w.available_balance),0)
                      FROM wallets w JOIN users u ON u.id = w.user_id
                     WHERE u.parent_id = ?
                    """, new Object[]{me.userId()}));
            out.put("topRetailers", jdbc.queryForList("""
                    SELECT u.id, u.full_name, k.shop_name,
                           count(t.id) AS txn_count,
                           COALESCE(sum(t.amount) FILTER (WHERE t.state = 'SUCCESS'),0) AS volume,
                           COALESCE(sum(t.amount) FILTER (
                               WHERE t.state = 'SUCCESS'
                                 AND date_trunc('month', t.created_at AT TIME ZONE 'Asia/Kolkata')
                                   = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                           ),0) AS gmv_month
                      FROM users u
                      LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                      LEFT JOIN transactions t ON t.agent_user_id = u.id AND t.txn_type = 'DMT'
                     WHERE u.parent_id = ? AND u.user_type = 'RETAILER'
                     GROUP BY u.id, u.full_name, k.shop_name
                     ORDER BY volume DESC, u.full_name
                     LIMIT 8
                    """, me.userId()));
            out.put("attention", jdbc.queryForList("""
                    SELECT u.id, u.full_name, u.status::text AS status,
                           k.kyc_status::text AS kyc_status, w.status AS wallet_status,
                           CASE
                             WHEN u.status <> 'ACTIVE' THEN 'Outlet is not active'
                             WHEN k.kyc_status IS NOT NULL AND k.kyc_status <> 'VERIFIED' THEN 'KYC needs attention'
                             WHEN w.status = 'FROZEN' THEN 'Till is frozen'
                             ELSE 'Review'
                           END AS reason
                      FROM users u
                      LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                      LEFT JOIN wallets w ON w.user_id = u.id
                     WHERE u.parent_id = ? AND u.user_type = 'RETAILER'
                       AND (u.status <> 'ACTIVE'
                            OR COALESCE(k.kyc_status, 'VERIFIED') <> 'VERIFIED'
                            OR COALESCE(w.status, 'ACTIVE') = 'FROZEN')
                     ORDER BY u.full_name
                     LIMIT 10
                    """, me.userId()));
        } else {
            try {
                Map<String, Object> wallet = jdbc.queryForMap("""
                        SELECT available_balance, hold_balance, status
                          FROM wallets WHERE user_id = ?
                        """, me.userId());
                BigDecimal available = toMoney(wallet.get("available_balance"));
                BigDecimal hold = toMoney(wallet.get("hold_balance"));
                out.put("walletAvailable", available);
                out.put("walletHold", hold);
                out.put("walletStatus", wallet.get("status"));
                out.put("walletFloat", available.add(hold));
            } catch (Exception e) {
                out.put("walletAvailable", BigDecimal.ZERO);
                out.put("walletHold", BigDecimal.ZERO);
                out.put("walletStatus", "NONE");
                out.put("walletFloat", BigDecimal.ZERO);
            }
        }

        Network senders = agents(me, "s.registered_by");
        out.put("customerCount", integer("""
                SELECT count(*) FROM dmt_senders s WHERE %s
                """.formatted(senders.sql), senders.args));

        out.put("gmvByDay", jdbc.queryForList("""
                SELECT d::date AS day,
                       COALESCE(sum(t.amount) FILTER (WHERE t.id IS NOT NULL),0) AS gmv,
                       count(t.id) AS txn_count
                  FROM generate_series(
                          (now() AT TIME ZONE 'Asia/Kolkata')::date - 13,
                          (now() AT TIME ZONE 'Asia/Kolkata')::date,
                          interval '1 day') d
                  LEFT JOIN transactions t
                         ON (t.created_at AT TIME ZONE 'Asia/Kolkata')::date = d::date
                        AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                        AND %s
                 GROUP BY 1
                 ORDER BY 1
                """.formatted(net.sql), net.args));

        out.put("transactionsByState", jdbc.queryForList("""
                SELECT t.txn_type::text AS txn_type, t.state::text AS state,
                       count(*) AS count, COALESCE(sum(t.amount),0) AS amount
                  FROM transactions t
                 WHERE %s AND %s
                 GROUP BY t.txn_type, t.state
                 ORDER BY t.txn_type, t.state
                """.formatted(net.sql, IST_MONTH.replace("created_at", "t.created_at")), net.args));

        out.put("serviceTotals", serviceTotals(net));
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("daily", trend(net,
                "(now() AT TIME ZONE 'Asia/Kolkata')::date - 13",
                "1 day",
                "(t.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        trends.put("weekly", trend(net,
                "date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')::date - interval '11 weeks'",
                "1 week",
                "date_trunc('week', t.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        trends.put("monthly", trend(net,
                "date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')::date - interval '11 months'",
                "1 month",
                "date_trunc('month', t.created_at AT TIME ZONE 'Asia/Kolkata')::date = b.period"));
        out.put("trends", trends);

        out.put("recentTransactions", jdbc.queryForList("""
                SELECT t.id, t.txn_type::text AS txn_type, t.state::text AS state, t.amount, t.fee,
                       t.created_at, u.full_name AS agent_name, u.id AS agent_user_id
                  FROM transactions t
                  JOIN users u ON u.id = t.agent_user_id
                 WHERE %s
                 ORDER BY t.created_at DESC
                 LIMIT 8
                """.formatted(net.sql), net.args));

        out.putAll(snapshot.snapshot(me));

        log.info("DESK_OVERVIEW user={} scope={} in {} ms", me.userId(), out.get("scope"),
                System.currentTimeMillis() - startMs);
        return out;
    }

    public List<Map<String, Object>> listOutlets(AuthPrincipal me) {
        requireDistributor(me);
        return jdbc.queryForList("""
                SELECT u.id, u.user_type::text AS user_type, u.full_name, u.mobile, u.email, u.code,
                       u.status::text AS status, u.created_at, u.created_by, u.parent_id, u.hub_id,
                       c.full_name AS created_by_name, c.code AS created_by_code,
                       h.name AS hub_name, k.shop_name, k.kyc_status::text AS kyc_status,
                       k.shop_address->>'city' AS retailer_city,
                       k.shop_address->>'state' AS retailer_state,
                       k.shop_address->>'pincode' AS retailer_pincode,
                       w.available_balance, w.hold_balance, w.status AS wallet_status,
                       COALESCE((
                           SELECT sum(t.amount) FROM transactions t
                            WHERE t.agent_user_id = u.id AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                              AND date_trunc('month', t.created_at AT TIME ZONE 'Asia/Kolkata')
                                = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                       ),0) AS gmv_month,
                       COALESCE((
                           SELECT count(*) FROM transactions t
                            WHERE t.agent_user_id = u.id AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                       ),0) AS txn_success,
                       COALESCE((
                           SELECT count(*) FROM dmt_senders s WHERE s.registered_by = u.id
                       ),0) AS customer_count
                  FROM users u
                  LEFT JOIN users c ON c.id = u.created_by
                  LEFT JOIN hubs h ON h.id = u.hub_id
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                  LEFT JOIN wallets w ON w.user_id = u.id
                 WHERE u.parent_id = ? AND u.user_type = 'RETAILER'
                 ORDER BY u.full_name
                """, me.userId());
    }

    public Map<String, Object> getOutlet(AuthPrincipal me, UUID id) {
        Map<String, Object> user = admin.getUser(id);
        assertVisible(me, user);
        return user;
    }

    public Map<String, Object> outletPerformance(AuthPrincipal me, UUID outletId) {
        Map<String, Object> user = admin.getUser(outletId);
        assertVisible(me, user);
        if (!"RETAILER".equals(String.valueOf(user.get("user_type")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_AN_OUTLET", "Performance is for retailers");
        }
        Network net = new Network("t.agent_user_id = ?", new Object[]{outletId});
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outletId", outletId);
        out.put("gmvToday", decimal("""
                SELECT COALESCE(sum(t.amount),0) FROM transactions t
                 WHERE %s AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                   AND (t.created_at AT TIME ZONE 'Asia/Kolkata')::date = %s
                """.formatted(net.sql, IST_TODAY), net.args));
        out.put("gmvMonth", decimal("""
                SELECT COALESCE(sum(t.amount),0) FROM transactions t
                 WHERE %s AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                   AND %s
                """.formatted(net.sql, IST_MONTH.replace("created_at", "t.created_at")), net.args));
        out.put("gmvAll", decimal("""
                SELECT COALESCE(sum(t.amount),0) FROM transactions t
                 WHERE %s AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                """.formatted(net.sql), net.args));
        Map<String, Object> rates = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE t.state = 'SUCCESS') AS success_count,
                       count(*) FILTER (WHERE t.state = 'FAILED') AS failed_count,
                       count(*) AS total_count
                  FROM transactions t
                 WHERE %s AND t.txn_type = 'DMT' AND %s
                """.formatted(net.sql, IST_MONTH.replace("created_at", "t.created_at")), net.args);
        long success = ((Number) rates.get("success_count")).longValue();
        long total = ((Number) rates.get("total_count")).longValue();
        out.put("successCountMonth", success);
        out.put("failedCountMonth", rates.get("failed_count"));
        out.put("successRateMonth", rate(success, total));
        out.put("txnMonth", integer("""
                SELECT count(*) FROM transactions t WHERE %s AND %s
                """.formatted(net.sql, IST_MONTH.replace("created_at", "t.created_at")), net.args));
        out.put("customerCount", integer(
                "SELECT count(*) FROM dmt_senders WHERE registered_by = ?", new Object[]{outletId}));
        out.put("commissionEarned", decimal(
                "SELECT COALESCE(sum(amount),0) FROM commission_earnings WHERE beneficiary_user = ?",
                new Object[]{outletId}));
        out.put("gmvByDay", jdbc.queryForList("""
                SELECT d::date AS day,
                       COALESCE(sum(t.amount) FILTER (WHERE t.id IS NOT NULL),0) AS gmv,
                       count(t.id) AS txn_count
                  FROM generate_series(
                          (now() AT TIME ZONE 'Asia/Kolkata')::date - 13,
                          (now() AT TIME ZONE 'Asia/Kolkata')::date,
                          interval '1 day') d
                  LEFT JOIN transactions t
                         ON (t.created_at AT TIME ZONE 'Asia/Kolkata')::date = d::date
                        AND t.state = 'SUCCESS' AND t.txn_type = 'DMT'
                        AND t.agent_user_id = ?
                 GROUP BY 1
                 ORDER BY 1
                """, outletId));
        return out;
    }

    @Transactional
    public Map<String, Object> createOutlet(AuthPrincipal me, String fullName, String mobile, String email,
                                            String password, String city, String state, String pincode) {
        requireDistributor(me);
        String resolvedEmail = (email == null || email.isBlank()) ? mobile + "@gfinpay.com" : email;
        log.info("DESK_ONBOARD_OUTLET dist={} mobile={}", me.userId(), mobile);
        return admin.createRetailer(fullName, mobile, resolvedEmail, password, me.userId(), city, state, pincode,
                me.userId());
    }

    @Transactional
    public Map<String, Object> updateOutletStatus(AuthPrincipal me, UUID outletId, String status) {
        requireDistributor(me);
        Map<String, Object> user = admin.getUser(outletId);
        assertVisible(me, user);
        if (!"RETAILER".equals(String.valueOf(user.get("user_type")))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can only change outlet status");
        }
        if (!List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS",
                    "Outlets can be activated or suspended from the desk");
        }
        return admin.updateStatus(outletId, status);
    }

    @Transactional
    public Map<String, Object> resetOutletPassword(AuthPrincipal me, UUID outletId, String password) {
        requireDistributor(me);
        Map<String, Object> user = admin.getUser(outletId);
        assertVisible(me, user);
        if (!"RETAILER".equals(String.valueOf(user.get("user_type")))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can only reset outlet passwords");
        }
        return admin.resetPassword(outletId, password);
    }

    @Transactional
    public Map<String, Object> freezeOutletWallet(AuthPrincipal me, UUID outletId, boolean frozen) {
        requireDistributor(me);
        Map<String, Object> user = admin.getUser(outletId);
        assertVisible(me, user);
        if (!"RETAILER".equals(String.valueOf(user.get("user_type")))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can only freeze an outlet till");
        }
        return admin.setWalletFrozen(outletId, frozen);
    }

    @Transactional
    public Map<String, Object> adjustOutletWallet(AuthPrincipal me, UUID outletId, BigDecimal amount,
                                                    String direction, String narration) {
        requireDistributor(me);
        Map<String, Object> user = admin.getUser(outletId);
        assertVisible(me, user);
        if (!"RETAILER".equals(String.valueOf(user.get("user_type")))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can only adjust an outlet till");
        }
        admin.adjustWallet(outletId, amount, direction, narration);
        return admin.getUser(outletId);
    }

    public List<Map<String, Object>> listTransactions(AuthPrincipal me) {
        Network net = agents(me, "t.agent_user_id");
        return jdbc.queryForList("""
                SELECT t.id, t.txn_type::text AS txn_type, t.state::text AS state, t.amount, t.fee,
                       t.partner_ref, t.failure_reason, t.created_at,
                       u.full_name AS agent_name, u.id AS agent_user_id
                  FROM transactions t
                  JOIN users u ON u.id = t.agent_user_id
                 WHERE %s
                 ORDER BY t.created_at DESC
                 LIMIT 200
                """.formatted(net.sql), net.args);
    }

    public Map<String, Object> getTransaction(AuthPrincipal me, UUID id) {
        Map<String, Object> txn = admin.getTransaction(id);
        UUID agentId = (UUID) txn.get("agent_user_id");
        if (isDistributor(me)) {
            if (!me.userId().equals(agentId)) {
                UUID parent = jdbc.queryForObject("SELECT parent_id FROM users WHERE id = ?", UUID.class, agentId);
                if (parent == null || !me.userId().equals(parent)) {
                    throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Transaction is outside your network");
                }
            }
        } else if (!me.userId().equals(agentId)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Transaction is not yours");
        }
        return txn;
    }

    public List<Map<String, Object>> listKyc(AuthPrincipal me) {
        if (isDistributor(me)) {
            return admin.listKyc().stream()
                    .filter(row -> me.userId().equals(row.get("distributor_id"))
                            || me.userId().toString().equals(String.valueOf(row.get("distributor_id"))))
                    .toList();
        }
        return admin.listKyc().stream()
                .filter(row -> me.userId().equals(row.get("user_id")))
                .toList();
    }

    public Map<String, Object> getKyc(AuthPrincipal me, UUID userId) {
        Map<String, Object> row = admin.getKyc(userId);
        if (isDistributor(me)) {
            Object dist = row.get("distributor_id");
            if (!me.userId().equals(dist) && !me.userId().toString().equals(String.valueOf(dist))) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "KYC is outside your network");
            }
        } else if (!me.userId().equals(userId)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "KYC is not yours");
        }
        return row;
    }

    public Map<String, Object> earnings(AuthPrincipal me) {
        BigDecimal earned = decimal(
                "SELECT COALESCE(sum(amount),0) FROM commission_earnings WHERE beneficiary_user = ?",
                new Object[]{me.userId()});
        BigDecimal earnedMonth = decimal("""
                SELECT COALESCE(sum(amount),0) FROM commission_earnings
                 WHERE beneficiary_user = ? AND %s
                """.formatted(IST_MONTH), new Object[]{me.userId()});
        List<Map<String, Object>> byRole = jdbc.queryForList("""
                SELECT role_in_split, COALESCE(sum(amount),0) AS amount
                  FROM commission_earnings WHERE beneficiary_user = ?
                 GROUP BY role_in_split
                """, me.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("earned", earned);
        out.put("earnedMonth", earnedMonth);
        out.put("byRole", byRole);
        if (isDistributor(me)) {
            out.put("byOutlet", jdbc.queryForList("""
                    SELECT u.id, u.full_name,
                           COALESCE((
                               SELECT sum(e.amount)
                                 FROM commission_earnings e
                                 JOIN transactions t ON t.id = e.transaction_id
                                WHERE e.beneficiary_user = ?
                                  AND t.agent_user_id = u.id
                           ), 0) AS amount
                      FROM users u
                     WHERE u.parent_id = ? AND u.user_type = 'RETAILER'
                     ORDER BY amount DESC, u.full_name
                    """, me.userId(), me.userId()));
        }
        return out;
    }

    public List<Map<String, Object>> listCustomers(AuthPrincipal me, UUID outletId) {
        if (outletId != null) {
            Map<String, Object> outlet = admin.getUser(outletId);
            assertVisible(me, outlet);
            return customerRows("s.registered_by = ?", new Object[]{outletId});
        }
        Network net = agents(me, "s.registered_by");
        return customerRows(net.sql, net.args);
    }

    public Map<String, Object> getCustomer(AuthPrincipal me, UUID senderId) {
        List<Map<String, Object>> rows = customerRows("s.id = ?", new Object[]{senderId});
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer does not exist");
        }
        Map<String, Object> customer = new LinkedHashMap<>(rows.get(0));
        UUID registeredBy = (UUID) customer.get("outlet_id");
        if (registeredBy != null) {
            Map<String, Object> outlet = admin.getUser(registeredBy);
            try {
                assertVisible(me, outlet);
            } catch (ApiException e) {
                if (!senderUsedInNetwork(me, senderId)) {
                    throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Customer is outside your network");
                }
            }
        } else if (!senderUsedInNetwork(me, senderId)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Customer is outside your network");
        }
        customer.put("beneficiaries", jdbc.queryForList("""
                SELECT id, name, account_last4, ifsc, verified_at, created_at
                  FROM dmt_beneficiaries WHERE sender_id = ? ORDER BY created_at
                """, senderId));
        Network net = agents(me, "t.agent_user_id");
        List<Object> args = new ArrayList<>(List.of(senderId));
        args.addAll(List.of(net.args));
        customer.put("recentTransactions", jdbc.queryForList("""
                SELECT t.id, t.txn_type::text AS txn_type, t.state::text AS state, t.amount, t.fee,
                       t.created_at, u.full_name AS agent_name, u.id AS agent_user_id
                  FROM transactions t
                  JOIN users u ON u.id = t.agent_user_id
                 WHERE t.dmt_sender_id = ? AND %s
                 ORDER BY t.created_at DESC
                 LIMIT 20
                """.formatted(net.sql), args.toArray()));
        return customer;
    }

    private boolean senderUsedInNetwork(AuthPrincipal me, UUID senderId) {
        Network net = agents(me, "t.agent_user_id");
        List<Object> args = new ArrayList<>();
        args.add(senderId);
        args.addAll(List.of(net.args));
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM transactions t
                 WHERE t.dmt_sender_id = ? AND %s
                """.formatted(net.sql), args.toArray(), Integer.class);
        return n != null && n > 0;
    }

    private List<Map<String, Object>> customerRows(String where, Object[] args) {
        return jdbc.queryForList("""
                SELECT s.id, s.mobile, s.full_name, s.kyc_level, s.ovd_type, s.ovd_last4,
                       s.mobile_verified_at, s.created_at,
                       u.id AS outlet_id, u.full_name AS outlet_name, k.shop_name,
                       COALESCE((
                           SELECT count(*) FROM transactions t
                            WHERE t.dmt_sender_id = s.id AND t.state = 'SUCCESS'
                       ),0) AS txn_count,
                       COALESCE((
                           SELECT sum(t.amount) FROM transactions t
                            WHERE t.dmt_sender_id = s.id AND t.state = 'SUCCESS'
                       ),0) AS volume
                  FROM dmt_senders s
                  JOIN users u ON u.id = s.registered_by
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                 WHERE %s
                 ORDER BY s.created_at DESC
                 LIMIT 200
                """.formatted(where), args);
    }

    private static boolean isDistributor(AuthPrincipal me) {
        return "MASTER_DISTRIBUTOR".equals(me.userType());
    }

    static void requireDistributor(AuthPrincipal me) {
        if (!isDistributor(me)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Distributor desk only");
        }
    }

    static void assertVisible(AuthPrincipal me, Map<String, Object> user) {
        UUID id = (UUID) user.get("id");
        if (me.userId().equals(id)) {
            return;
        }
        if (isDistributor(me) && me.userId().equals(user.get("parent_id"))) {
            return;
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This outlet is not in your network");
    }

    private List<Map<String, Object>> serviceTotals(Network net) {
        return jdbc.queryForList("""
                SELECT t.txn_type::text AS txn_type,
                       COALESCE(sum(t.amount) FILTER (
                           WHERE (t.created_at AT TIME ZONE 'Asia/Kolkata')::date
                               = (now() AT TIME ZONE 'Asia/Kolkata')::date
                       ), 0) AS today,
                       COALESCE(sum(t.amount) FILTER (
                           WHERE date_trunc('week', t.created_at AT TIME ZONE 'Asia/Kolkata')
                               = date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')
                       ), 0) AS week,
                       COALESCE(sum(t.amount) FILTER (
                           WHERE date_trunc('month', t.created_at AT TIME ZONE 'Asia/Kolkata')
                               = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                       ), 0) AS month,
                       COALESCE(sum(t.amount), 0) AS all_time,
                       count(*) FILTER (
                           WHERE (t.created_at AT TIME ZONE 'Asia/Kolkata')::date
                               = (now() AT TIME ZONE 'Asia/Kolkata')::date
                       ) AS today_count,
                       count(*) FILTER (
                           WHERE date_trunc('week', t.created_at AT TIME ZONE 'Asia/Kolkata')
                               = date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata')
                       ) AS week_count,
                       count(*) FILTER (
                           WHERE date_trunc('month', t.created_at AT TIME ZONE 'Asia/Kolkata')
                               = date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')
                       ) AS month_count
                  FROM transactions t
                 WHERE t.state = 'SUCCESS'
                   AND t.txn_type = ANY (%s)
                   AND %s
                 GROUP BY t.txn_type
                 ORDER BY t.txn_type
                """.formatted(SERVICE_TYPES, net.sql), net.args);
    }

    private List<Map<String, Object>> trend(Network net, String startExpr, String step, String joinOn) {
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
                        AND %s
                 GROUP BY 1, 2
                 ORDER BY 1, 2
                """.formatted(startExpr, step, SERVICE_TYPES, joinOn, net.sql), net.args);
    }

    private Network agents(AuthPrincipal me, String column) {
        if (isDistributor(me)) {
            return new Network("(" + column + " = ? OR " + column + " IN (SELECT id FROM users WHERE parent_id = ?))",
                    new Object[]{me.userId(), me.userId()});
        }
        return new Network(column + " = ?", new Object[]{me.userId()});
    }

    private BigDecimal decimal(String sql, Object[] args) {
        BigDecimal v = jdbc.queryForObject(sql, args, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    private int integer(String sql, Object[] args) {
        Integer v = jdbc.queryForObject(sql, args, Integer.class);
        return v == null ? 0 : v;
    }

    private static BigDecimal rate(long success, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(success * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal toMoney(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private record Network(String sql, Object[] args) {}
}
