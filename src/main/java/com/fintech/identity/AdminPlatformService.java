package com.fintech.identity;

import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import com.fintech.recon.ReconJson;
import com.fintech.reports.DashboardSnapshotService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPlatformService {

    private static final Logger log = LoggerFactory.getLogger(AdminPlatformService.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;
    private final AdminAccess access;
    private final DashboardSnapshotService snapshot;
    private final ObjectMapper objectMapper;

    public AdminPlatformService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, WalletService walletService,
                                LedgerService ledgerService, TransactionService transactionService,
                                AdminAccess access, DashboardSnapshotService snapshot,
                                ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.access = access;
        this.snapshot = snapshot;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> listHubs() {
        StringBuilder sql = new StringBuilder("""
                SELECT h.id, h.code, h.name, h.city, h.state, h.status, h.notes, h.created_at, h.created_by,
                       creator.full_name AS created_by_name, creator.code AS created_by_code,
                       (SELECT count(*) FROM users u WHERE u.hub_id = h.id AND u.user_type = 'MASTER_DISTRIBUTOR') AS distributor_count,
                       (SELECT count(*) FROM hub_admin_assignments ha WHERE ha.hub_id = h.id) AS admin_count
                  FROM hubs h
                  LEFT JOIN users creator ON creator.id = h.created_by
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        access.appendHubFilter(sql, args, "h.id");
        sql.append(" ORDER BY h.name");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> hubDashboard() {
        if (!access.me().platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT h.id, h.code, h.name, h.city, h.state, h.status,
                       (SELECT count(*) FROM users d
                         WHERE d.hub_id = h.id AND d.user_type = 'MASTER_DISTRIBUTOR') AS distributors,
                       (SELECT count(*) FROM users d
                         WHERE d.hub_id = h.id AND d.user_type = 'MASTER_DISTRIBUTOR' AND d.status = 'ACTIVE') AS distributors_active,
                       (SELECT count(*) FROM users r
                         WHERE r.hub_id = h.id AND r.user_type = 'RETAILER') AS retailers,
                       (SELECT count(*) FROM users r
                         WHERE r.hub_id = h.id AND r.user_type = 'RETAILER' AND r.status = 'ACTIVE') AS retailers_active,
                       (SELECT count(*) FROM customers c
                          JOIN users u ON u.id = c.retailer_user_id
                         WHERE u.hub_id = h.id) AS customers,
                       (SELECT count(*) FROM customers c
                          JOIN users u ON u.id = c.retailer_user_id
                         WHERE u.hub_id = h.id AND c.ekyc_status = 'VERIFIED') AS customers_ekyc,
                       (SELECT COALESCE(sum(w.available_balance + w.hold_balance), 0)
                          FROM wallets w JOIN users u ON u.id = w.user_id
                         WHERE u.hub_id = h.id
                           AND u.user_type IN ('MASTER_DISTRIBUTOR', 'RETAILER')) AS wallet_float,
                       (SELECT COALESCE(sum(t.amount), 0)
                          FROM transactions t JOIN users u ON u.id = t.agent_user_id
                         WHERE u.hub_id = h.id AND t.state = 'SUCCESS') AS gmv,
                       (SELECT count(*) FROM sales_leads l
                          JOIN users u ON u.id = l.retailer_user_id
                         WHERE u.hub_id = h.id) AS sales
                  FROM hubs h
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        access.appendHubFilter(sql, args, "h.id");
        sql.append(" ORDER BY h.name");
        List<Map<String, Object>> hubs = jdbc.queryForList(sql.toString(), args.toArray());

        StringBuilder kycSql = new StringBuilder("""
                SELECT count(*)::int AS pending
                  FROM agent_kyc_profiles k
                  JOIN users u ON u.id = k.user_id
                 WHERE k.kyc_status IS DISTINCT FROM 'VERIFIED'
                """);
        List<Object> kycArgs = new ArrayList<>();
        access.appendHubFilter(kycSql, kycArgs, "u.hub_id");
        Integer kycPending = jdbc.queryForObject(kycSql.toString(), Integer.class, kycArgs.toArray());

        long distributors = 0;
        long distributorsActive = 0;
        long retailers = 0;
        long retailersActive = 0;
        long customers = 0;
        long customersEkyc = 0;
        BigDecimal wallet = BigDecimal.ZERO;
        BigDecimal gmv = BigDecimal.ZERO;
        long sales = 0;
        for (Map<String, Object> hub : hubs) {
            distributors += asLong(hub.get("distributors"));
            distributorsActive += asLong(hub.get("distributors_active"));
            retailers += asLong(hub.get("retailers"));
            retailersActive += asLong(hub.get("retailers_active"));
            customers += asLong(hub.get("customers"));
            customersEkyc += asLong(hub.get("customers_ekyc"));
            wallet = wallet.add(asDecimal(hub.get("wallet_float")));
            gmv = gmv.add(asDecimal(hub.get("gmv")));
            sales += asLong(hub.get("sales"));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("hubs", hubs.size());
        totals.put("distributors", distributors);
        totals.put("distributors_active", distributorsActive);
        totals.put("retailers", retailers);
        totals.put("retailers_active", retailersActive);
        totals.put("customers", customers);
        totals.put("customers_ekyc", customersEkyc);
        totals.put("kyc_pending", kycPending == null ? 0 : kycPending);
        totals.put("wallet_float", wallet);
        totals.put("gmv", gmv);
        totals.put("sales", sales);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("hubs", hubs);
        out.putAll(snapshot.snapshot(access.me()));
        return out;
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    public Map<String, Object> getHub(UUID id) {
        access.assertHub(id);
        Map<String, Object> hub = one("""
                SELECT h.id, h.code, h.name, h.city, h.state, h.status, h.notes,
                       h.created_at, h.updated_at, h.created_by,
                       creator.full_name AS created_by_name, creator.code AS created_by_code
                  FROM hubs h
                  LEFT JOIN users creator ON creator.id = h.created_by
                 WHERE h.id = ?
                """, id, "HUB_NOT_FOUND", "Hub does not exist");
        hub.put("distributors", jdbc.queryForList("""
                SELECT u.id, u.full_name, u.mobile, u.status::text AS status, u.created_at
                  FROM users u WHERE u.hub_id = ? AND u.user_type = 'MASTER_DISTRIBUTOR'
                 ORDER BY u.full_name
                """, id));
        hub.put("admins", jdbc.queryForList("""
                SELECT u.id, u.full_name, u.mobile, u.email, u.status::text AS status, u.created_at
                  FROM hub_admin_assignments ha
                  JOIN users u ON u.id = ha.user_id
                 WHERE ha.hub_id = ?
                 ORDER BY u.full_name
                """, id));
        return hub;
    }

    public List<Map<String, Object>> distributorDirectory(UUID hubId) {
        access.requireSuperAdmin();
        requireActiveHub(hubId);
        return jdbc.queryForList("""
                SELECT u.id, u.full_name, u.mobile, u.status::text AS status,
                       u.hub_id, h.name AS hub_name, h.code AS hub_code,
                       (u.hub_id = ?) AS assigned_here
                  FROM users u
                  LEFT JOIN hubs h ON h.id = u.hub_id
                 WHERE u.user_type = 'MASTER_DISTRIBUTOR'
                 ORDER BY u.full_name
                """, hubId);
    }

    public List<Map<String, Object>> adminDirectory(UUID hubId) {
        access.requireSuperAdmin();
        requireActiveHub(hubId);
        return jdbc.queryForList("""
                SELECT u.id, u.full_name, u.mobile, u.email, u.status::text AS status,
                       COALESCE((
                           SELECT string_agg(h2.name, ', ' ORDER BY h2.name)
                             FROM hub_admin_assignments ha3
                             JOIN hubs h2 ON h2.id = ha3.hub_id
                            WHERE ha3.user_id = u.id
                       ), '') AS hub_names,
                       EXISTS (
                           SELECT 1 FROM hub_admin_assignments ha2
                            WHERE ha2.hub_id = ? AND ha2.user_id = u.id
                       ) AS assigned_here
                  FROM users u
                 WHERE u.user_type = 'ADMIN'
                 ORDER BY u.full_name
                """, hubId);
    }

    @Transactional
    public Map<String, Object> assignDistributorsToHub(UUID hubId, List<UUID> userIds) {
        access.requireSuperAdmin();
        requireActiveHub(hubId);
        if (userIds == null || userIds.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "USERS_REQUIRED", "Select at least one distributor");
        }
        int assigned = 0;
        for (UUID userId : userIds) {
            String type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
            if (!"MASTER_DISTRIBUTOR".equals(type)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_USER",
                        "Only distributors can be assigned to a hub");
            }
            assignHub(userId, hubId);
            assigned++;
        }
        log.info("HUB_ASSIGN_DIST hubId={} count={}", hubId, assigned);
        return getHub(hubId);
    }

    @Transactional
    public Map<String, Object> assignAdminsToHub(UUID hubId, List<UUID> userIds) {
        access.requireSuperAdmin();
        requireActiveHub(hubId);
        if (userIds == null || userIds.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "USERS_REQUIRED", "Select at least one admin");
        }
        for (UUID userId : userIds) {
            String type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
            if (!"ADMIN".equals(type)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_USER",
                        "Only hub admins can be assigned");
            }
            jdbc.update("""
                    INSERT INTO hub_admin_assignments (hub_id, user_id)
                    VALUES (?, ?)
                    ON CONFLICT (hub_id, user_id) DO NOTHING
                    """, hubId, userId);
            jdbc.update("""
                    UPDATE users SET hub_id = COALESCE(hub_id, ?), updated_at = now() WHERE id = ?
                    """, hubId, userId);
        }
        log.info("HUB_ASSIGN_ADMIN hubId={} count={}", hubId, userIds.size());
        return getHub(hubId);
    }

    @Transactional
    public Map<String, Object> createHub(String name, String city, String state, String notes) {
        access.requireSuperAdmin();
        UUID id = UUID.randomUUID();
        String code = generateHubCode();
        try {
            jdbc.update("""
                    INSERT INTO hubs (id, code, name, city, state, notes, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id, code, name.trim(), city.trim(), state.trim(), notes, access.me().userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_CODE_TAKEN", "Could not assign a unique hub code");
        }
        return getHub(id);
    }

    private static final String HUB_CODE_PREFIX = "GFIN";
    private static final String HUB_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final java.security.SecureRandom HUB_CODE_RANDOM = new java.security.SecureRandom();

    private String generateHubCode() {
        return generateGfinCode("HUB_CODE_EXHAUSTED", "Could not generate a unique hub code");
    }

    private String generateRetailerCode() {
        return generateGfinCode("RETAILER_CODE_EXHAUSTED", "Could not generate a unique retailer code");
    }

    private String generateGfinCode(String errorCode, String message) {
        for (int attempt = 0; attempt < 32; attempt++) {
            StringBuilder suffix = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                suffix.append(HUB_CODE_CHARS.charAt(HUB_CODE_RANDOM.nextInt(HUB_CODE_CHARS.length())));
            }
            String code = HUB_CODE_PREFIX + suffix;
            if (gfinCodeAvailable(code)) {
                return code;
            }
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }

    private boolean gfinCodeAvailable(String code) {
        Integer hubTaken = jdbc.queryForObject("SELECT COUNT(*)::int FROM hubs WHERE code = ?", Integer.class, code);
        Integer userTaken = jdbc.queryForObject("SELECT COUNT(*)::int FROM users WHERE code = ?", Integer.class, code);
        Integer vendorTaken = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM vendors WHERE code = ?", Integer.class, code);
        Integer affiliateTaken = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM vendor_affiliates WHERE gfin_code = ?", Integer.class, code);
        return (hubTaken == null || hubTaken == 0)
                && (userTaken == null || userTaken == 0)
                && (vendorTaken == null || vendorTaken == 0)
                && (affiliateTaken == null || affiliateTaken == 0);
    }

    @Transactional
    public Map<String, Object> updateHub(UUID id, String name, String city, String state, String notes, String status) {
        access.requireSuperAdmin();
        int n = jdbc.update("""
                UPDATE hubs SET name = COALESCE(?, name), city = COALESCE(?, city), state = COALESCE(?, state),
                       notes = COALESCE(?, notes), status = COALESCE(?, status), updated_at = now()
                 WHERE id = ?
                """, name, city, state, notes, status, id);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "HUB_NOT_FOUND", "Hub does not exist");
        }
        return getHub(id);
    }

    public List<Map<String, Object>> listServices() {
        return jdbc.queryForList("""
                SELECT code, name, description, enabled, sort_order, updated_at
                  FROM platform_services ORDER BY sort_order
                """);
    }

    @Transactional
    public Map<String, Object> setServiceEnabled(String code, boolean enabled) {
        access.requireSuperAdmin();
        int n = jdbc.update("UPDATE platform_services SET enabled = ?, updated_at = now() WHERE code = ?", enabled, code);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "Unknown service code");
        }
        return jdbc.queryForMap("SELECT code, name, description, enabled, updated_at FROM platform_services WHERE code = ?", code);
    }

    public List<Map<String, Object>> listUsers(String userType) {
        if ("ADMIN".equals(userType) || "SUPER_ADMIN".equals(userType)) {
            access.requireSuperAdmin();
        }
        StringBuilder sql = new StringBuilder(userListSql(null));
        List<Object> args = new ArrayList<>();
        sql.append(" WHERE 1=1 ");
        if (userType != null && !userType.isBlank()) {
            sql.append(" AND u.user_type = ? ");
            args.add(userType);
        }
        if ("MASTER_DISTRIBUTOR".equals(userType) || "RETAILER".equals(userType) || userType == null || userType.isBlank()) {
            access.appendHubFilter(sql, args, "u.hub_id");
        }
        sql.append(" ORDER BY u.created_at DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> getUser(UUID id) {
        Map<String, Object> user = one(userListSql("WHERE u.id = ?"), id, "USER_NOT_FOUND", "User does not exist");
        String type = String.valueOf(user.get("user_type"));
        if (!id.equals(access.me().userId())) {
            if ("ADMIN".equals(type) || "SUPER_ADMIN".equals(type)) {
                access.requireSuperAdmin();
            } else {
                access.assertNetworkUser(id);
            }
        }
        if ("MASTER_DISTRIBUTOR".equals(type)) {
            user.put("retailers", jdbc.queryForList("""
                    SELECT u.id, u.full_name, u.mobile, u.code, u.status::text AS status,
                           k.kyc_status::text AS kyc_status,
                           k.shop_address->>'city' AS city,
                           k.shop_address->>'state' AS state,
                           k.shop_address->>'pincode' AS pincode
                      FROM users u
                      LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                     WHERE u.parent_id = ? AND u.user_type = 'RETAILER'
                     ORDER BY u.full_name
                    """, id));
        }
        if ("RETAILER".equals(type) || "MASTER_DISTRIBUTOR".equals(type)) {
            try {
                WalletService.WalletInfo w = walletService.getByUser(id);
                user.put("wallet", Map.of(
                        "walletId", w.walletId(),
                        "availableBalance", w.available(),
                        "holdBalance", w.hold(),
                        "status", jdbc.queryForObject("SELECT status FROM wallets WHERE user_id = ?", String.class, id)));
            } catch (ApiException e) {
                user.put("wallet", null);
            }
            user.put("recentTransactions", transactionService.listByAgent(id, 20));
            user.put("commissionEarned", jdbc.queryForObject("""
                    SELECT COALESCE(sum(amount),0) FROM commission_earnings WHERE beneficiary_user = ?
                    """, BigDecimal.class, id));
        }
        if ("RETAILER".equals(type) || "MASTER_DISTRIBUTOR".equals(type)) {
            user.put("kyc", loadAgentKyc(id));
        }
        return user;
    }

    @Transactional
    public Map<String, Object> createDistributor(String fullName, String mobile, String email, String password,
                                                 UUID hubId, UUID adminId) {
        access.assertHub(hubId);
        requireActiveHub(hubId);
        return createUser("MASTER_DISTRIBUTOR", 2, fullName, mobile, email, password, adminId, adminId, hubId,
                null, null, null);
    }

    @Transactional
    public Map<String, Object> createRetailer(String fullName, String mobile, String email, String password,
                                              UUID distributorId, String city, String state, String pincode,
                                              UUID adminId) {
        requireRetailerLocation(city, state, pincode);
        Map<String, Object> parent = one(
                "SELECT id, user_type::text AS user_type, hub_id, status::text AS status FROM users WHERE id = ?",
                distributorId, "DISTRIBUTOR_NOT_FOUND", "Distributor does not exist");
        if (!"MASTER_DISTRIBUTOR".equals(parent.get("user_type"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARENT",
                    "Retailers must be onboarded under a distributor");
        }
        if (!"ACTIVE".equals(parent.get("status"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PARENT_NOT_ACTIVE",
                    "Cannot place a retailer under a suspended distributor");
        }
        UUID hubId = (UUID) parent.get("hub_id");
        access.assertHub(hubId);
        return createUser("RETAILER", 3, fullName, mobile, email, password, distributorId, adminId, hubId,
                city, state, pincode);
    }

    @Transactional
    public Map<String, Object> createHubAdmin(String fullName, String mobile, String email, String password,
                                              UUID hubId, UUID createdBy) {
        access.requireSuperAdmin();
        requireActiveHub(hubId);
        return createUser("ADMIN", 4, fullName, mobile, email, password, createdBy, createdBy, hubId,
                null, null, null);
    }

    @Transactional
    public Map<String, Object> updateStatus(UUID userId, String status) {
        access.assertNetworkUser(userId);
        String type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
        if ("ADMIN".equals(type) || "SUPER_ADMIN".equals(type)) {
            access.requireSuperAdmin();
        }
        if (!List.of("ACTIVE", "SUSPENDED", "TERMINATED", "PENDING_KYC").contains(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "Unknown status");
        }
        int n = jdbc.update("UPDATE users SET status = ?, updated_at = now() WHERE id = ?", status, userId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        log.info("USER_STATUS userId={} status={}", userId, status);
        return getUser(userId);
    }

    @Transactional
    public Map<String, Object> assignHub(UUID userId, UUID hubId) {
        access.requireSuperAdmin();
        requireActiveHub(hubId);
        String type;
        try {
            type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        if ("ADMIN".equals(type)) {
            jdbc.update("""
                    INSERT INTO hub_admin_assignments (hub_id, user_id)
                    VALUES (?, ?)
                    ON CONFLICT (hub_id, user_id) DO NOTHING
                    """, hubId, userId);
            jdbc.update("UPDATE users SET hub_id = COALESCE(hub_id, ?), updated_at = now() WHERE id = ?", hubId, userId);
            return getUser(userId);
        }
        int n = jdbc.update("UPDATE users SET hub_id = ?, updated_at = now() WHERE id = ?", hubId, userId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        jdbc.update("UPDATE users SET hub_id = ?, updated_at = now() WHERE parent_id = ? AND user_type = 'RETAILER'",
                hubId, userId);
        return getUser(userId);
    }

    @Transactional
    public Map<String, Object> transferRetailer(UUID retailerId, UUID distributorId) {
        access.assertNetworkUser(retailerId);
        Map<String, Object> parent = one(
                "SELECT id, user_type::text AS user_type, hub_id FROM users WHERE id = ?",
                distributorId, "DISTRIBUTOR_NOT_FOUND", "Distributor does not exist");
        if (!"MASTER_DISTRIBUTOR".equals(parent.get("user_type"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARENT", "Target must be a distributor");
        }
        int n = jdbc.update("""
                UPDATE users SET parent_id = ?, hub_id = ?, updated_at = now()
                 WHERE id = ? AND user_type = 'RETAILER'
                """, distributorId, parent.get("hub_id"), retailerId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "RETAILER_NOT_FOUND", "Retailer does not exist");
        }
        return getUser(retailerId);
    }

    @Transactional
    public Map<String, Object> resetPassword(UUID userId, String password) {
        access.assertNetworkUser(userId);
        String type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
        if ("ADMIN".equals(type) || "SUPER_ADMIN".equals(type)) {
            access.requireSuperAdmin();
        }
        int n = jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?",
                passwordEncoder.encode(password), userId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        return Map.of("id", userId, "reset", true);
    }

    @Transactional
    public Map<String, Object> setWalletFrozen(UUID userId, boolean frozen) {
        access.assertNetworkUser(userId);
        String status = frozen ? "FROZEN" : "ACTIVE";
        int n = jdbc.update("UPDATE wallets SET status = ?, updated_at = now() WHERE user_id = ?", status, userId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "No wallet for this user");
        }
        return getUser(userId);
    }

    @Transactional
    public Map<String, Object> adjustWallet(UUID userId, BigDecimal amount, String direction, String narration) {
        access.assertNetworkUser(userId);
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Amount must be positive");
        }
        if (!"CREDIT".equals(direction) && !"DEBIT".equals(direction)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIRECTION", "Direction must be CREDIT or DEBIT");
        }
        WalletService.WalletInfo wallet = walletService.lockByUser(userId);
        UUID txnId = transactionService.create("ADJUSTMENT", userId, wallet.walletId(), amount, BigDecimal.ZERO,
                null, null, "ADMIN", null);
        String note = narration == null || narration.isBlank() ? "Admin wallet adjustment" : narration;
        if ("CREDIT".equals(direction)) {
            ledgerService.postGroup(txnId, List.of(
                    LedgerService.Entry.debit(LedgerService.PLATFORM_SETTLEMENT, amount, note),
                    LedgerService.Entry.credit(wallet.availableAccountId(), amount, note)));
            walletService.adjustCache(wallet.walletId(), amount, BigDecimal.ZERO);
        } else {
            if (wallet.available().compareTo(amount) < 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                        "Available float is less than the debit");
            }
            ledgerService.postGroup(txnId, List.of(
                    LedgerService.Entry.debit(wallet.availableAccountId(), amount, note),
                    LedgerService.Entry.credit(LedgerService.PLATFORM_SETTLEMENT, amount, note)));
            walletService.adjustCache(wallet.walletId(), amount.negate(), BigDecimal.ZERO);
        }
        transactionService.transition(txnId, "SUCCESS", null, null);
        log.info("WALLET_ADJUST userId={} dir={} amount={} txnId={}", userId, direction, amount, txnId);
        return getUser(userId);
    }

    public List<Map<String, Object>> listKyc() {
        StringBuilder sql = new StringBuilder("""
                SELECT k.user_id, u.full_name, u.mobile, u.status::text AS user_status,
                       k.shop_name, k.kyc_status::text AS kyc_status, k.rejection_reason,
                       k.aadhaar_last4, k.gstin, k.created_at, k.updated_at,
                       p.full_name AS distributor_name, u.parent_id AS distributor_id
                  FROM agent_kyc_profiles k
                  JOIN users u ON u.id = k.user_id
                  LEFT JOIN users p ON p.id = u.parent_id
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        access.appendHubFilter(sql, args, "u.hub_id");
        sql.append(" ORDER BY k.updated_at DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> getKyc(UUID userId) {
        access.assertNetworkUser(userId);
        Map<String, Object> row = one("""
                SELECT k.user_id, u.full_name, u.mobile, u.status::text AS user_status,
                       k.shop_name, k.gstin, k.kyc_status::text AS kyc_status, k.rejection_reason,
                       k.aadhaar_last4, k.aadhaar_verified_at, k.pan_verified_at,
                       k.liveness_score, k.documents, k.created_at, k.updated_at,
                       p.full_name AS distributor_name, u.parent_id AS distributor_id
                  FROM agent_kyc_profiles k
                  JOIN users u ON u.id = k.user_id
                  LEFT JOIN users p ON p.id = u.parent_id
                 WHERE k.user_id = ?
                """, userId, "KYC_NOT_FOUND", "No KYC profile for this outlet");
        return row;
    }

    @Transactional
    public Map<String, Object> reviewKyc(UUID userId, String decision, String reason) {
        access.assertNetworkUser(userId);
        if (!"VERIFIED".equals(decision) && !"REJECTED".equals(decision)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DECISION", "Decision must be VERIFIED or REJECTED");
        }
        int n = jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET kyc_status = ?,
                       rejection_reason = ?,
                       updated_at = now()
                 WHERE user_id = ?
                """, decision, reason, userId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "KYC_NOT_FOUND", "No KYC profile for this outlet");
        }
        if ("VERIFIED".equals(decision)) {
            jdbc.update("UPDATE users SET status = 'ACTIVE', updated_at = now() WHERE id = ?", userId);
        } else {
            jdbc.update("UPDATE users SET status = 'PENDING_KYC', updated_at = now() WHERE id = ?", userId);
        }
        return getKyc(userId);
    }

    public List<Map<String, String>> transactionTypes() {
        return AdminTxnTypes.catalog();
    }

    public List<Map<String, Object>> listTransactions(String txnType, LocalDate from, LocalDate to) {
        AdminTxnTypes.Group group = AdminTxnTypes.require(txnType);
        if (from == null || to == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DATE_RANGE_REQUIRED",
                    "Choose a from and to date");
        }
        if (to.isBefore(from)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DATE_RANGE",
                    "To date cannot be before from date");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DATE_RANGE_TOO_LONG",
                    "Choose a range of at most 366 days");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.txn_type::text AS txn_type, t.state::text AS state, t.amount, t.fee,
                       t.partner_ref, t.failure_reason, t.created_at,
                       u.full_name AS agent_name, u.user_type::text AS agent_type
                  FROM transactions t
                  JOIN users u ON u.id = t.agent_user_id
                 WHERE (t.created_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN ? AND ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to));
        sql.append(" AND t.txn_type IN (");
        for (int i = 0; i < group.txnTypes().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            args.add(group.txnTypes().get(i));
        }
        sql.append(") ");
        access.appendHubFilter(sql, args, "u.hub_id");
        sql.append(" ORDER BY t.created_at DESC LIMIT 10000");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> getTransaction(UUID id) {
        Map<String, Object> row = one("""
                SELECT t.id, t.txn_type::text AS txn_type, t.state::text AS state, t.amount, t.fee,
                       t.partner_code, t.partner_ref, t.failure_reason, t.state_history,
                       t.created_at, t.updated_at, t.agent_user_id,
                       u.full_name AS agent_name
                  FROM transactions t
                  JOIN users u ON u.id = t.agent_user_id
                 WHERE t.id = ?
                """, id, "TXN_NOT_FOUND", "Transaction not found");
        UUID agentId = (UUID) row.get("agent_user_id");
        if (agentId != null) {
            access.assertNetworkUser(agentId);
        }
        return row;
    }

    public List<Map<String, Object>> listRecon() {
        access.requireSuperAdmin();
        return jdbc.queryForList("""
                SELECT id, provider, business_date, mis_file_uri, total_rows, matched_rows,
                       unidentified_rows, status_counts, status, started_at, finished_at
                  FROM recon_batches
                 ORDER BY started_at DESC
                """).stream().map(this::hydrateBatchJson).toList();
    }

    public Map<String, Object> getRecon(UUID id) {
        access.requireSuperAdmin();
        Map<String, Object> batch = one("""
                SELECT id, provider, business_date, mis_file_uri, total_rows, matched_rows,
                       unidentified_rows, status_counts, status, started_at, finished_at
                  FROM recon_batches WHERE id = ?
                """, id, "RECON_NOT_FOUND", "Recon batch not found");
        hydrateBatchJson(batch);
        List<Map<String, Object>> mismatches = jdbc.queryForList("""
                SELECT id, mismatch_type, transaction_id, partner_ref, details, resolution, resolved_at, created_at
                  FROM recon_mismatches WHERE batch_id = ? ORDER BY id
                """, id);
        for (Map<String, Object> row : mismatches) {
            row.put("details", ReconJson.asMap(row.get("details"), objectMapper));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, customer_mobile, customer_name, lead_id,
                       retailer_user_id, distributor_user_id, hub_id,
                       retailer_label, distributor_label, hub_label,
                       identified, current_status, payload, created_at
                  FROM recon_rows WHERE batch_id = ? ORDER BY created_at, id
                """, id);
        for (Map<String, Object> row : rows) {
            row.put("payload", ReconJson.asMap(row.get("payload"), objectMapper));
        }
        batch.put("mismatches", mismatches);
        batch.put("rows", rows);
        batch.put("matched_count", batch.get("matched_rows"));
        batch.put("eligible_count", batch.get("total_rows"));
        return batch;
    }

    @Transactional
    public Map<String, Object> createRecon(String provider, String businessDate) {
        access.requireSuperAdmin();
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO recon_batches (id, provider, business_date, mis_file_uri, total_rows, matched_rows,
                                               unidentified_rows, status_counts, status, finished_at)
                    VALUES (?, ?, ?::date, ?, 0, 0, 0, '{}'::jsonb, 'COMPLETED', now())
                    """, id, provider, businessDate, "manual://" + provider + "/" + businessDate);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RECON_EXISTS",
                    "A recon batch already exists for this provider and date");
        }
        return getRecon(id);
    }

    @Transactional
    public Map<String, Object> resolveMismatch(long mismatchId, String resolution) {
        access.requireSuperAdmin();
        int n = jdbc.update("""
                UPDATE recon_mismatches SET resolution = ?, resolved_at = now() WHERE id = ?
                """, resolution, mismatchId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "MISMATCH_NOT_FOUND", "Mismatch not found");
        }
        UUID batchId = jdbc.queryForObject("SELECT batch_id FROM recon_mismatches WHERE id = ?", UUID.class, mismatchId);
        return getRecon(batchId);
    }

    public List<Map<String, Object>> listReportRuns() {
        access.requireSuperAdmin();
        return jdbc.queryForList("""
                SELECT r.id, r.report_type, r.title, r.created_at, u.full_name AS created_by_name
                  FROM admin_report_runs r
                  LEFT JOIN users u ON u.id = r.created_by
                 ORDER BY r.created_at DESC
                """);
    }

    public Map<String, Object> getReportRun(UUID id) {
        access.requireSuperAdmin();
        return one("""
                SELECT r.id, r.report_type, r.title, r.payload, r.created_at, u.full_name AS created_by_name
                  FROM admin_report_runs r
                  LEFT JOIN users u ON u.id = r.created_by
                 WHERE r.id = ?
                """, id, "REPORT_NOT_FOUND", "Report run not found");
    }

    @Transactional
    public Map<String, Object> createReportRun(String reportType, String title, UUID createdBy) {
        access.requireSuperAdmin();
        Map<String, Object> payload = switch (reportType) {
            case "EOD" -> snapshotEod();
            case "GMV" -> snapshotGmv();
            case "COMMISSIONS" -> snapshotCommissions();
            case "WALLETS" -> snapshotWallets();
            default -> throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_REPORT",
                    "Report type must be EOD, GMV, COMMISSIONS or WALLETS");
        };
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO admin_report_runs (id, report_type, title, payload, created_by)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, id, reportType, title, toJson(payload), createdBy);
        return getReportRun(id);
    }

    private Map<String, Object> snapshotEod() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byState", jdbc.queryForList("""
                SELECT txn_type::text AS txn_type, state::text AS state, count(*) AS count, COALESCE(sum(amount),0) AS amount
                  FROM transactions GROUP BY txn_type, state ORDER BY txn_type, state
                """));
        return out;
    }

    private Map<String, Object> snapshotGmv() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gmv", jdbc.queryForObject(
                "SELECT COALESCE(sum(amount),0) FROM transactions WHERE state='SUCCESS'", BigDecimal.class));
        out.put("byType", jdbc.queryForList("""
                SELECT txn_type::text AS txn_type, COALESCE(sum(amount),0) AS amount, count(*) AS count
                  FROM transactions WHERE state='SUCCESS' GROUP BY txn_type
                """));
        return out;
    }

    private Map<String, Object> snapshotCommissions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byRole", jdbc.queryForList("""
                SELECT role_in_split, COALESCE(sum(amount),0) AS amount FROM commission_earnings
                 GROUP BY role_in_split
                """));
        return out;
    }

    private Map<String, Object> snapshotWallets() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", jdbc.queryForList("""
                SELECT u.full_name, u.user_type::text AS user_type, w.available_balance, w.hold_balance, w.status
                  FROM wallets w JOIN users u ON u.id = w.user_id
                 ORDER BY (w.available_balance + w.hold_balance) DESC
                """));
        return out;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise report payload", e);
        }
    }

    private Map<String, Object> createUser(String userType, int roleId, String fullName, String mobile,
                                           String email, String password, UUID parentId, UUID createdBy,
                                           UUID hubId, String retailerCity, String retailerState, String retailerPincode) {
        long startMs = System.currentTimeMillis();
        log.info("ONBOARD started type={} mobile={}", userType, mask(mobile));
        try {
            assertMobileAvailableForOnboard(mobile, userType);
            UUID userId = UUID.randomUUID();
            String outletCode = switch (userType) {
                case "RETAILER", "MASTER_DISTRIBUTOR", "SUPER_ADMIN", "ADMIN" -> generateGfinCode(
                        "USER_CODE_EXHAUSTED", "Could not generate a unique user code");
                default -> null;
            };
            jdbc.update("""
                    INSERT INTO users (id, user_type, parent_id, created_by, full_name, mobile, email, password_hash, status, hub_id, code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """, userId, userType, parentId, createdBy, fullName, mobile, email,
                    passwordEncoder.encode(password), hubId, outletCode);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);
            if ("ADMIN".equals(userType) && hubId != null) {
                jdbc.update("""
                        INSERT INTO hub_admin_assignments (hub_id, user_id)
                        VALUES (?, ?)
                        ON CONFLICT (hub_id, user_id) DO NOTHING
                        """, hubId, userId);
            }
            if (!"SUPER_ADMIN".equals(userType) && !"ADMIN".equals(userType)) {
                walletService.createForUser(userId);
            }
            if ("MASTER_DISTRIBUTOR".equals(userType)) {
                jdbc.update("""
                        INSERT INTO agent_kyc_profiles (user_id, kyc_status)
                        VALUES (?, 'NOT_STARTED')
                        """, userId);
            }
            if ("RETAILER".equals(userType)) {
                requireRetailerLocation(retailerCity, retailerState, retailerPincode);
                String addressJson = toJson(Map.of(
                        "city", retailerCity.trim(),
                        "state", retailerState.trim(),
                        "pincode", retailerPincode.trim()));
                jdbc.update("""
                        INSERT INTO agent_kyc_profiles (user_id, shop_address, kyc_status)
                        VALUES (?, ?::jsonb, 'NOT_STARTED')
                        """, userId, addressJson);
            }
            log.info("ONBOARD success userId={} type={}", userId, userType);
            return getUser(userId);
        } catch (DuplicateKeyException e) {
            throw mobileOnboardConflict(userType);
        } finally {
            log.info("ONBOARD completed type={} in {} ms", userType, System.currentTimeMillis() - startMs);
        }
    }

    private static void requireRetailerLocation(String city, String state, String pincode) {
        if (city == null || city.trim().isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CITY", "City is required");
        }
        if (state == null || state.trim().isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE", "State is required");
        }
        String pin = pincode == null ? "" : pincode.replaceAll("\\D", "");
        if (pin.length() != 6) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PINCODE", "Enter a 6-digit pincode");
        }
    }

    private void assertMobileAvailableForOnboard(String mobile, String intendedUserType) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT 1 FROM users WHERE mobile = ? LIMIT 1", mobile);
        if (!rows.isEmpty()) {
            throw mobileOnboardConflict(intendedUserType);
        }
    }

    private static ApiException mobileOnboardConflict(String intendedUserType) {
        if ("MASTER_DISTRIBUTOR".equals(intendedUserType)) {
            return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DISTRIBUTOR_ALREADY_EXISTS",
                    "Distributor already exists");
        }
        if ("RETAILER".equals(intendedUserType)) {
            return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RETAILER_ALREADY_EXISTS",
                    "Retailer already exists");
        }
        return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MOBILE_ALREADY_REGISTERED",
                "This mobile number is already registered");
    }

    private void requireActiveHub(UUID hubId) {
        if (hubId == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_REQUIRED", "A hub is required");
        }
        String status;
        try {
            status = jdbc.queryForObject("SELECT status FROM hubs WHERE id = ?", String.class, hubId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "HUB_NOT_FOUND", "Hub does not exist");
        }
        if (!"ACTIVE".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_NOT_ACTIVE", "Hub is not active");
        }
    }

    private Map<String, Object> one(String sql, Object arg, String code, String message) {
        try {
            return jdbc.queryForMap(sql, arg);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, code, message);
        }
    }

    private Map<String, Object> loadAgentKyc(UUID userId) {
        List<Map<String, Object>> kyc = jdbc.queryForList("""
                SELECT shop_name, gstin, kyc_status::text AS kyc_status, rejection_reason,
                       ovd_type, aadhaar_last4, aadhaar_verified_at, pan_verified_at, created_at,
                       shop_address->>'city' AS city,
                       shop_address->>'state' AS state,
                       shop_address->>'pincode' AS pincode
                  FROM agent_kyc_profiles WHERE user_id = ?
                """, userId);
        return kyc.isEmpty() ? null : kyc.get(0);
    }

    private static String userListSql(String where) {
        return """
                SELECT u.id, u.user_type::text AS user_type, u.full_name, u.mobile, u.email, u.code,
                       u.status::text AS status, u.created_at, u.parent_id, u.hub_id, u.created_by,
                       p.full_name AS parent_name, c.full_name AS created_by_name, c.code AS created_by_code,
                       h.name AS hub_name, h.code AS hub_code,
                       k.shop_name, k.kyc_status::text AS kyc_status,
                       k.shop_address->>'city' AS retailer_city,
                       k.shop_address->>'state' AS retailer_state,
                       k.shop_address->>'pincode' AS retailer_pincode
                  FROM users u
                  LEFT JOIN users p ON p.id = u.parent_id
                  LEFT JOIN users c ON c.id = u.created_by
                  LEFT JOIN hubs h ON h.id = u.hub_id
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                """ + (where == null ? "" : " " + where);
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 4 ? "****" : "******" + mobile.substring(mobile.length() - 4);
    }

    private Map<String, Object> hydrateBatchJson(Map<String, Object> batch) {
        batch.put("status_counts", ReconJson.asMap(batch.get("status_counts"), objectMapper));
        return batch;
    }
}
