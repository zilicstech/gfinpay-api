package com.fintech.identity;

import com.fintech.finance.FinanceService;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.persistence.config.PlatformServiceRepository;
import com.fintech.persistence.finance.AgentKycProfileRepository;
import com.fintech.persistence.identity.UserRepository;
import com.fintech.persistence.ledger.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.security.SessionService;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PageWindow;
import com.fintech.recon.ReconJson;
import com.fintech.reports.DashboardSnapshotService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private final FinanceService finance;
    private final SessionService sessions;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PlatformServiceRepository platformServices;
    private final AgentKycProfileRepository kycProfiles;

    public AdminPlatformService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, WalletService walletService,
                                LedgerService ledgerService, TransactionService transactionService,
                                AdminAccess access, DashboardSnapshotService snapshot,
                                ObjectMapper objectMapper, FinanceService finance, SessionService sessions,
                                UserRepository userRepository, WalletRepository walletRepository,
                                PlatformServiceRepository platformServices, AgentKycProfileRepository kycProfiles) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.access = access;
        this.snapshot = snapshot;
        this.objectMapper = objectMapper;
        this.finance = finance;
        this.sessions = sessions;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.platformServices = platformServices;
        this.kycProfiles = kycProfiles;
    }

    public List<Map<String, Object>> listHubs() {
        StringBuilder sql = new StringBuilder("""
                SELECT h.id, h.code, h.name, h.city, h.state, h.status, h.notes, h.created_at, h.created_by,
                       creator.full_name AS created_by_name, creator.code AS created_by_code,
                       COALESCE(dist.distributor_count, 0) AS distributor_count,
                       COALESCE(admins.admin_count, 0) AS admin_count
                  FROM hubs h
                  LEFT JOIN users creator ON creator.id = h.created_by
                  LEFT JOIN (
                        SELECT hub_id, count(*) AS distributor_count
                          FROM users
                         WHERE user_type = 'MASTER_DISTRIBUTOR'
                         GROUP BY hub_id
                  ) dist ON dist.hub_id = h.id
                  LEFT JOIN (
                        SELECT hub_id, count(*) AS admin_count
                          FROM hub_admin_assignments
                         GROUP BY hub_id
                  ) admins ON admins.hub_id = h.id
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
                       COALESCE(hu.distributors, 0) AS distributors,
                       COALESCE(hu.distributors_active, 0) AS distributors_active,
                       COALESCE(hu.retailers, 0) AS retailers,
                       COALESCE(hu.retailers_active, 0) AS retailers_active,
                       COALESCE(hc.customers, 0) AS customers,
                       COALESCE(hc.customers_ekyc, 0) AS customers_ekyc,
                       COALESCE(hs.sales, 0) AS sales
                  FROM hubs h
                  LEFT JOIN (
                        SELECT hub_id,
                               count(*) FILTER (WHERE user_type = 'MASTER_DISTRIBUTOR') AS distributors,
                               count(*) FILTER (WHERE user_type = 'MASTER_DISTRIBUTOR' AND status = 'ACTIVE') AS distributors_active,
                               count(*) FILTER (WHERE user_type = 'RETAILER') AS retailers,
                               count(*) FILTER (WHERE user_type = 'RETAILER' AND status = 'ACTIVE') AS retailers_active
                          FROM users
                         GROUP BY hub_id
                  ) hu ON hu.hub_id = h.id
                  LEFT JOIN (
                        SELECT u.hub_id,
                               count(*) AS customers,
                               count(*) FILTER (WHERE c.ekyc_status = 'VERIFIED') AS customers_ekyc
                          FROM customers c
                          JOIN users u ON u.id = c.retailer_user_id
                         GROUP BY u.hub_id
                  ) hc ON hc.hub_id = h.id
                  LEFT JOIN (
                        SELECT u.hub_id, count(*) AS sales
                          FROM sales_leads l
                          JOIN users u ON u.id = l.retailer_user_id
                         GROUP BY u.hub_id
                  ) hs ON hs.hub_id = h.id
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
                 WHERE k.kyc_status IS DISTINCT FROM 'COMPLETED'
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
        long sales = 0;
        for (Map<String, Object> hub : hubs) {
            distributors += asLong(hub.get("distributors"));
            distributorsActive += asLong(hub.get("distributors_active"));
            retailers += asLong(hub.get("retailers"));
            retailersActive += asLong(hub.get("retailers_active"));
            customers += asLong(hub.get("customers"));
            customersEkyc += asLong(hub.get("customers_ekyc"));
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
                SELECT u.id, u.code, u.full_name, u.mobile, u.status::text AS status, u.created_at
                  FROM users u WHERE u.hub_id = ? AND u.user_type = 'MASTER_DISTRIBUTOR'
                 ORDER BY u.full_name
                """, id));
        hub.put("admins", jdbc.queryForList("""
                SELECT u.id, u.code, u.full_name, u.mobile, u.email, u.status::text AS status, u.created_at
                  FROM hub_admin_assignments ha
                  JOIN users u ON u.id = ha.user_id
                 WHERE ha.hub_id = ?
                 ORDER BY u.full_name
                """, id));
        return hub;
    }

    public List<Map<String, Object>> distributorDirectory(UUID hubId) {
        access.requireSuperAdminOrPlatformAdmin();
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
        access.requireSuperAdminOrPlatformAdmin();
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
                 WHERE u.user_type = 'HUBADMIN'
                 ORDER BY u.full_name
                """, hubId);
    }

    @Transactional
    public Map<String, Object> assignDistributorsToHub(UUID hubId, List<UUID> userIds) {
        access.requireSuperAdminOrPlatformAdmin();
        requireActiveHub(hubId);
        if (userIds == null || userIds.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "USERS_REQUIRED", "Select at least one distributor");
        }
        List<UUID> ids = new ArrayList<>(new java.util.LinkedHashSet<>(userIds));
        if (userRepository.countByIdsAndType(ids, "MASTER_DISTRIBUTOR") != ids.size()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_USER",
                    "Only distributors can be assigned to a hub");
        }
        userRepository.assignHub(ids, hubId);
        userRepository.moveRetailersWithParents(ids, hubId);
        log.info("HUB_ASSIGN_DIST hubId={} count={}", hubId, ids.size());
        return getHub(hubId);
    }

    @Transactional
    public Map<String, Object> assignAdminsToHub(UUID hubId, List<UUID> userIds) {
        access.requireSuperAdminOrPlatformAdmin();
        requireActiveHub(hubId);
        if (userIds == null || userIds.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "USERS_REQUIRED", "Select at least one admin");
        }
        List<UUID> ids = new ArrayList<>(new java.util.LinkedHashSet<>(userIds));
        if (userRepository.countByIdsAndType(ids, "HUBADMIN") != ids.size()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_USER",
                    "Only hub admins can be assigned");
        }
        jdbc.batchUpdate("""
                INSERT INTO hub_admin_assignments (hub_id, user_id)
                VALUES (?, ?)
                ON CONFLICT (hub_id, user_id) DO NOTHING
                """, ids.stream().map(id -> new Object[]{hubId, id}).toList());
        userRepository.assignHubIfMissing(ids, hubId);
        log.info("HUB_ASSIGN_ADMIN hubId={} count={}", hubId, ids.size());
        return getHub(hubId);
    }

    @Transactional
    public Map<String, Object> createHub(String name, String city, String state, String notes) {
        access.requireSuperAdminOrPlatformAdmin();
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
        access.requireSuperAdminOrPlatformAdmin();
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
                  FROM platform_services
                 WHERE code <> 'LEAD_GEN'
                 ORDER BY sort_order
                """);
    }

    @Transactional
    public Map<String, Object> setServiceEnabled(String code, boolean enabled) {
        access.requireSuperAdminOrPlatformAdmin();
        int n = platformServices.updateEnabled(code, enabled);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "Unknown service code");
        }
        return jdbc.queryForMap("SELECT code, name, description, enabled, updated_at FROM platform_services WHERE code = ?", code);
    }

    public Map<String, Object> commission(String serviceCode) {
        access.requireSuperAdminOrPlatformAdmin();
        return commissionView(serviceCode);
    }

    @Transactional
    public Map<String, Object> saveCommission(String serviceCode, String mode, BigDecimal retailer,
                                              BigDecimal distributor, BigDecimal hub, BigDecimal vendorCommission) {
        access.requireSuperAdminOrPlatformAdmin();
        String kind = mode == null ? "" : mode.trim().toUpperCase();
        if (!"FLAT".equals(kind) && !"PERCENT".equals(kind)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COMMISSION", "Choose flat or percentage");
        }
        BigDecimal retailerValue = nonNegative(retailer);
        BigDecimal distributorValue = nonNegative(distributor);
        BigDecimal hubValue = nonNegative(hub);
        if ("PERCENT".equals(kind) && (retailerValue.compareTo(new BigDecimal("100")) > 0
                || distributorValue.compareTo(new BigDecimal("100")) > 0
                || hubValue.compareTo(new BigDecimal("100")) > 0)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COMMISSION", "A percentage cannot be more than 100");
        }
        String txnType = commissionTxnType(serviceCode);
        BigDecimal retailerPct = "PERCENT".equals(kind) ? retailerValue : BigDecimal.ZERO;
        BigDecimal distributorPct = "PERCENT".equals(kind) ? distributorValue : BigDecimal.ZERO;
        BigDecimal hubPct = "PERCENT".equals(kind) ? hubValue : BigDecimal.ZERO;
        BigDecimal retailerAmount = "FLAT".equals(kind) ? retailerValue : null;
        BigDecimal distributorAmount = "FLAT".equals(kind) ? distributorValue : null;
        BigDecimal hubAmount = "FLAT".equals(kind) ? hubValue : null;
        int updated = jdbc.update("""
                UPDATE commission_rules
                   SET commission_type = ?, retailer_amount = ?, distributor_amount = ?, hub_amount = ?,
                       retailer_share_pct = ?, distributor_share_pct = ?, hub_share_pct = ?, platform_share_pct = 0
                 WHERE txn_type = ? AND effective_to IS NULL
                """, kind, retailerAmount, distributorAmount, hubAmount,
                retailerPct, distributorPct, hubPct, txnType);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO commission_rules
                        (id, txn_type, slab_min, slab_max, total_rate_bp, flat_fee,
                         retailer_share_pct, distributor_share_pct, platform_share_pct, hub_share_pct,
                         commission_type, retailer_amount, distributor_amount, hub_amount, effective_from)
                    VALUES (?, ?, 0, 999999999, 0, NULL, ?, ?, 0, ?, ?, ?, ?, ?, now())
                    """, UUID.randomUUID(), txnType, retailerPct, distributorPct, hubPct,
                    kind, retailerAmount, distributorAmount, hubAmount);
        }
        if ("HOSPI_CASH".equalsIgnoreCase(serviceCode) && vendorCommission != null) {
            BigDecimal vendor = nonNegative(vendorCommission).setScale(2, RoundingMode.HALF_UP);
            jdbc.update("""
                    UPDATE hospi_cash_settings
                       SET vendor_commission = ?, updated_at = now()
                     WHERE id IS TRUE
                    """, vendor);
        }
        log.info("COMMISSION_SET service={} type={} mode={} retailer={} distributor={} hub={}",
                serviceCode, txnType, kind, retailerValue, distributorValue, hubValue);
        return commissionView(serviceCode);
    }

    private Map<String, Object> commissionView(String serviceCode) {
        String txnType = commissionTxnType(serviceCode);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT commission_type, retailer_amount, distributor_amount, hub_amount,
                       retailer_share_pct, distributor_share_pct, hub_share_pct
                  FROM commission_rules
                 WHERE txn_type = ? AND effective_to IS NULL
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, txnType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceCode", serviceCode);
        out.put("txnType", txnType);
        if (rows.isEmpty()) {
            out.put("mode", "FLAT");
            out.put("retailer", BigDecimal.ZERO);
            out.put("distributor", BigDecimal.ZERO);
            out.put("hub", BigDecimal.ZERO);
            attachVendorCommission(serviceCode, out);
            return out;
        }
        Map<String, Object> row = rows.get(0);
        boolean percent = "PERCENT".equalsIgnoreCase(String.valueOf(row.get("commission_type")));
        out.put("mode", percent ? "PERCENT" : "FLAT");
        out.put("retailer", valueOf(percent ? row.get("retailer_share_pct") : row.get("retailer_amount")));
        out.put("distributor", valueOf(percent ? row.get("distributor_share_pct") : row.get("distributor_amount")));
        out.put("hub", valueOf(percent ? row.get("hub_share_pct") : row.get("hub_amount")));
        attachVendorCommission(serviceCode, out);
        return out;
    }

    private void attachVendorCommission(String serviceCode, Map<String, Object> out) {
        if (!"HOSPI_CASH".equalsIgnoreCase(serviceCode)) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT vendor_commission FROM hospi_cash_settings WHERE id IS TRUE
                """);
        out.put("vendorCommission", rows.isEmpty() ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : valueOf(rows.get(0).get("vendor_commission")).setScale(2, RoundingMode.HALF_UP));
    }

    private static String commissionTxnType(String serviceCode) {
        String code = serviceCode == null ? "" : serviceCode.trim().toUpperCase();
        return switch (code) {
            case "UPI_CASHOUT" -> "CASHOUT_UPI";
            case "FD_CARD" -> "FD_CARD";
            case "HOSPI_CASH" -> "HOSPI_CASH";
            default -> code;
        };
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COMMISSION", "Amounts cannot be negative");
        }
        return value;
    }

    private static BigDecimal valueOf(Object value) {
        return value instanceof BigDecimal amount ? amount : BigDecimal.ZERO;
    }

    public List<Map<String, Object>> listUsers(String userType) {
        return listUsers(userType, PageWindow.DEFAULT_LIMIT, 0);
    }

    public List<Map<String, Object>> listUsers(String userType, int limit, int offset) {
        if ("ADMIN".equals(userType) || "HUBADMIN".equals(userType) || "SUPER_ADMIN".equals(userType)
                || "AUDITOR".equals(userType) || "ALL_ADMINS".equals(userType)) {
            access.requireSuperAdminOrPlatformAdmin();
        }
        StringBuilder sql = new StringBuilder(userListSql(null));
        List<Object> args = new ArrayList<>();
        sql.append(" WHERE 1=1 ");
        if ("ALL_ADMINS".equals(userType)) {
            sql.append(" AND u.user_type IN ('SUPER_ADMIN', 'ADMIN', 'HUBADMIN') ");
        } else if ("ADMIN".equals(userType) || "SUPER_ADMIN".equals(userType)) {
            access.requireSuperAdminOrPlatformAdmin();
            sql.append(" AND u.user_type = ? ");
            args.add(userType);
        } else if (userType != null && !userType.isBlank()) {
            sql.append(" AND u.user_type = ? ");
            args.add(userType);
        } else if (!access.superAdminOrPlatformAdmin()) {
            sql.append(" AND u.user_type NOT IN ('SUPER_ADMIN', 'ADMIN') ");
        }
        if ("MASTER_DISTRIBUTOR".equals(userType) || "RETAILER".equals(userType) || userType == null || userType.isBlank()) {
            access.appendHubFilter(sql, args, "u.hub_id");
        }
        sql.append(" ORDER BY u.created_at DESC LIMIT ? OFFSET ?");
        args.add(PageWindow.limit(limit));
        args.add(PageWindow.offset(offset));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> getUser(UUID id) {
        Map<String, Object> user = one(userListSql("WHERE u.id = ?"), id, "USER_NOT_FOUND", "User does not exist");
        String type = String.valueOf(user.get("user_type"));
        if (!id.equals(access.me().userId())) {
            if ("SUPER_ADMIN".equals(type) || "ADMIN".equals(type)) {
                access.requireSuperAdminOrPlatformAdmin();
            } else if ("HUBADMIN".equals(type) || "AUDITOR".equals(type)) {
                access.requireSuperAdminOrPlatformAdmin();
            } else {
                access.assertNetworkUser(id);
            }
        }
        if ("HUBADMIN".equals(type)) {
            user.put("assignedHubs", jdbc.queryForList("""
                    SELECT h.id, h.name, h.code, h.city, h.state, h.status::text AS status,
                           ha.assigned_at
                      FROM hub_admin_assignments ha
                      JOIN hubs h ON h.id = ha.hub_id
                     WHERE ha.user_id = ?
                     ORDER BY h.name
                    """, id));
            user.put("distributors", jdbc.queryForList("""
                    SELECT u.id, u.full_name, u.mobile, u.code, u.status::text AS status,
                           h.name AS hub_name, h.code AS hub_code,
                           COALESCE(rc.retailer_count, 0) AS retailer_count
                      FROM users u
                      JOIN hubs h ON h.id = u.hub_id
                      LEFT JOIN (
                            SELECT parent_id, count(*) AS retailer_count
                              FROM users
                             WHERE user_type = 'RETAILER'
                             GROUP BY parent_id
                      ) rc ON rc.parent_id = u.id
                     WHERE u.user_type = 'MASTER_DISTRIBUTOR'
                       AND u.hub_id IN (
                           SELECT hub_id FROM hub_admin_assignments WHERE user_id = ?
                           UNION
                           SELECT hub_id FROM users WHERE id = ? AND hub_id IS NOT NULL
                       )
                     ORDER BY u.full_name
                    """, id, id));
        }
        if ("ADMIN".equals(type) || "SUPER_ADMIN".equals(type)) {
            Integer hubCount = jdbc.queryForObject("SELECT count(*)::int FROM hubs", Integer.class);
            Integer distCount = jdbc.queryForObject("SELECT count(*)::int FROM users WHERE user_type = 'MASTER_DISTRIBUTOR'", Integer.class);
            Integer retCount = jdbc.queryForObject("SELECT count(*)::int FROM users WHERE user_type = 'RETAILER'", Integer.class);
            user.put("platformStats", Map.of(
                    "totalHubs", hubCount != null ? hubCount : 0,
                    "totalDistributors", distCount != null ? distCount : 0,
                    "totalRetailers", retCount != null ? retCount : 0
            ));
        }
        if ("MASTER_DISTRIBUTOR".equals(type)) {
            user.put("retailers", jdbc.queryForList("""
                    SELECT u.id, u.full_name, u.mobile, u.code, u.status::text AS status,
                           k.kyc_status::text AS kyc_status, k.bank_status,
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
                        "status", w.status()));
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
        if ("RETAILER".equals(type)) {
            user.put("activation", finance.activationSummary(id));
        }
        return user;
    }

    @Transactional
    public Map<String, Object> createDistributor(String fullName, String mobile, String email, String password,
                                                 UUID hubId, UUID adminId) {
        access.assertHub(hubId);
        requireActiveHub(hubId);
        return createUser("MASTER_DISTRIBUTOR", fullName, mobile, email, password, adminId, adminId, hubId,
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
        if (hubId == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_REQUIRED",
                    "This distributor is not assigned to a hub");
        }
        AuthPrincipal caller = access.me();
        boolean ownNetwork = caller.userId().equals(distributorId)
                && "MASTER_DISTRIBUTOR".equals(caller.userType());
        if (!ownNetwork) {
            access.assertHub(hubId);
        }
        return createUser("RETAILER", fullName, mobile, email, password, distributorId, adminId, hubId,
                city, state, pincode);
    }

    @Transactional
    public Map<String, Object> createHubAdmin(String fullName, String mobile, String email, String password,
                                              UUID hubId, UUID createdBy) {
        access.requireSuperAdminOrPlatformAdmin();
        if (hubId == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_REQUIRED", "Hub is required for Hub Admin");
        }
        requireActiveHub(hubId);
        return createUser("HUBADMIN", fullName, mobile, email, password, createdBy, createdBy, hubId,
                null, null, null);
    }

    @Transactional
    public Map<String, Object> createPlatformAdmin(String fullName, String mobile, String email, String password,
                                                   UUID createdBy) {
        access.requireSuperAdmin();
        return createUser("ADMIN", fullName, mobile, email, password, createdBy, createdBy, null,
                null, null, null);
    }

    @Transactional
    public Map<String, Object> createAuditor(String fullName, String mobile, String email, String password,
                                             UUID createdBy) {
        access.requireSuperAdminOrPlatformAdmin();
        return createUser("AUDITOR", fullName, mobile, email, password, createdBy, createdBy, null,
                null, null, null);
    }

    @Transactional
    public Map<String, Object> updateStatus(UUID userId, String status) {
        access.assertNetworkUser(userId);
        String type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
        if ("SUPER_ADMIN".equals(type)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Super admin status cannot be changed");
        }
        if ("ADMIN".equals(type)) {
            access.requireSuperAdminOrPlatformAdmin();
        } else if ("HUBADMIN".equals(type) || "AUDITOR".equals(type)) {
            access.requireSuperAdminOrPlatformAdmin();
        }
        if (!List.of("ACTIVE", "INACTIVE", "SUSPENDED", "TERMINATED", "PENDING", "SUBMITTED", "APPROVED", "REJECTED",
                "PENDING_KYC", "PENDING_ACTIVATION").contains(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "Unknown status");
        }
        if ("RETAILER".equals(type) && "ACTIVE".equals(status)) {
            String current = jdbc.queryForObject("SELECT status::text FROM users WHERE id = ?", String.class, userId);
            boolean restoring = "SUSPENDED".equals(current) || "INACTIVE".equals(current);
            if (!restoring && (!"APPROVED".equals(current) || !finance.hasReceivedActivation(userId))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVATION_NOT_APPROVED",
                        "The auditor must approve the activation request before this retailer can go live");
            }
        }
        int n = userRepository.updateStatus(userId, status);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        if (!UserService.canAuthenticate(type, status)) {
            sessions.revokeAllForUser(userId, "STATUS_CHANGE");
        }
        log.info("USER_STATUS userId={} status={}", userId, status);
        return getUser(userId);
    }

    @Transactional
    public Map<String, Object> assignHub(UUID userId, UUID hubId) {
        access.requireSuperAdminOrPlatformAdmin();
        requireActiveHub(hubId);
        String type;
        try {
            type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        if ("ADMIN".equals(type) || "HUBADMIN".equals(type)) {
            if ("ADMIN".equals(type)) {
                access.requireSuperAdminOrPlatformAdmin();
            }
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
        if ("SUPER_ADMIN".equals(type) && !userId.equals(access.me().userId())) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Cannot reset super admin password");
        }
        if ("ADMIN".equals(type) && !userId.equals(access.me().userId())) {
            access.requireSuperAdminOrPlatformAdmin();
        } else if ("HUBADMIN".equals(type) || "AUDITOR".equals(type)) {
            access.requireSuperAdminOrPlatformAdmin();
        }
        int n = userRepository.updatePassword(userId, passwordEncoder.encode(password));
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        sessions.revokeAllForUser(userId, "ADMIN_RESET");
        return Map.of("id", userId, "reset", true);
    }

    /** Puts one finished bank check back on the retailer's KYC page. Onboarding stays complete. */
    @Transactional
    public Map<String, Object> reopenBankEkyc(UUID userId, String bankName) {
        access.assertNetworkUser(userId);
        String bank = bankName == null ? "" : bankName.trim().toLowerCase(Locale.ROOT);
        if (!"bank2".equals(bank) && !"bank6".equals(bank)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_BANK",
                    "Choose money transfer or cash out eKYC.");
        }
        String type;
        try {
            type = jdbc.queryForObject("SELECT user_type::text FROM users WHERE id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        if (!"RETAILER".equals(type)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_A_RETAILER",
                    "Bank eKYC can be reopened for a retailer.");
        }
        int updated = jdbc.update("""
                UPDATE paysprint_bank_ekyc
                   SET status = 'PENDING',
                       completed_at = NULL,
                       last_message = 'Scan again so this check includes CASA.',
                       updated_at = now()
                 WHERE user_id = ? AND bank_name = ? AND status = 'SUCCESS'
                """, userId, bank);
        if (updated == 0) {
            Integer exists = jdbc.queryForObject(
                    "SELECT count(*)::int FROM paysprint_bank_ekyc WHERE user_id = ? AND bank_name = ?",
                    Integer.class, userId, bank);
            if (exists == null || exists == 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EKYC_NOT_DONE",
                        "This bank check has not been completed yet.");
            }
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EKYC_ALREADY_OPEN",
                    "This bank check is already waiting for a new scan.");
        }
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET bank_status = 'PENDING', updated_at = now()
                 WHERE user_id = ?
                """, userId);
        log.info("MERCHANT_EKYC reopen userId={} pipe={}", userId, bank);
        return Map.of("id", userId, "bank", bank, "status", "PENDING");
    }

    @Transactional
    public Map<String, Object> setWalletFrozen(UUID userId, boolean frozen) {
        access.assertNetworkUser(userId);
        String status = frozen ? "FROZEN" : "ACTIVE";
        int n = walletRepository.updateStatus(userId, status, !frozen);
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
            ledgerService.post(txnId, wallet.walletId(), "CREDIT", amount, note);
            walletService.adjustCache(wallet.walletId(), amount, BigDecimal.ZERO);
        } else {
            if (wallet.available().compareTo(amount) < 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                        "Available float is less than the debit");
            }
            ledgerService.post(txnId, wallet.walletId(), "DEBIT", amount, note);
            walletService.adjustCache(wallet.walletId(), amount.negate(), BigDecimal.ZERO);
        }
        transactionService.transition(txnId, "SUCCESS", null, null);
        log.info("WALLET_ADJUST userId={} dir={} amount={} txnId={}", userId, direction, amount, txnId);
        return getUser(userId);
    }

    public List<Map<String, Object>> listKyc() {
        return listKyc(null, null, PageWindow.DEFAULT_LIMIT, 0);
    }

    public List<Map<String, Object>> listKyc(UUID distributorId, UUID userId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT k.user_id, u.full_name, u.mobile, u.status::text AS user_status,
                       k.shop_name, k.kyc_status::text AS kyc_status, k.bank_status, k.rejection_reason,
                       k.aadhaar_last4, k.gstin, k.created_at, k.updated_at,
                       p.full_name AS distributor_name, u.parent_id AS distributor_id
                  FROM agent_kyc_profiles k
                  JOIN users u ON u.id = k.user_id
                  LEFT JOIN users p ON p.id = u.parent_id
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        access.appendHubFilter(sql, args, "u.hub_id");
        if (distributorId != null) {
            sql.append(" AND u.parent_id = ? ");
            args.add(distributorId);
        }
        if (userId != null) {
            sql.append(" AND k.user_id = ? ");
            args.add(userId);
        }
        sql.append(" ORDER BY k.updated_at DESC LIMIT ? OFFSET ?");
        args.add(PageWindow.limit(limit));
        args.add(PageWindow.offset(offset));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> getKyc(UUID userId) {
        access.assertNetworkUser(userId);
        Map<String, Object> row = one("""
                SELECT k.user_id, u.full_name, u.mobile, u.status::text AS user_status,
                       k.shop_name, k.gstin, k.kyc_status::text AS kyc_status, k.bank_status, k.rejection_reason,
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
        String stored = "VERIFIED".equals(decision) || "COMPLETED".equals(decision) ? "COMPLETED"
                : "REJECTED".equals(decision) || "FAILED".equals(decision) ? "FAILED" : "";
        if (stored.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DECISION", "Decision must be COMPLETED or FAILED");
        }
        int n = kycProfiles.updateStatus(userId, stored, reason);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "KYC_NOT_FOUND", "No KYC profile for this outlet");
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
        access.requireSuperAdminOrPlatformAdmin();
        return jdbc.queryForList("""
                SELECT id, provider, business_date, mis_file_uri, total_rows, matched_rows,
                       unidentified_rows, status_counts, status, started_at, finished_at
                  FROM recon_batches
                 ORDER BY started_at DESC
                """).stream().map(this::hydrateBatchJson).toList();
    }

    public Map<String, Object> getRecon(UUID id) {
        return getRecon(id, PageWindow.DEFAULT_LIMIT, 0);
    }

    public Map<String, Object> getRecon(UUID id, int limit, int offset) {
        access.requireSuperAdminOrPlatformAdmin();
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
                 LIMIT ? OFFSET ?
                """, id, PageWindow.limit(limit), PageWindow.offset(offset));
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
        access.requireSuperAdminOrPlatformAdmin();
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
        access.requireSuperAdminOrPlatformAdmin();
        int n = jdbc.update("""
                UPDATE recon_mismatches SET resolution = ?, resolved_at = now() WHERE id = ?
                """, resolution, mismatchId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "MISMATCH_NOT_FOUND", "Mismatch not found");
        }
        UUID batchId = jdbc.queryForObject("SELECT batch_id FROM recon_mismatches WHERE id = ?", UUID.class, mismatchId);
        return getRecon(batchId);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise report payload", e);
        }
    }

    private Map<String, Object> createUser(String userType, String fullName, String mobile,
                                           String email, String password, UUID parentId, UUID createdBy,
                                           UUID hubId, String retailerCity, String retailerState, String retailerPincode) {
        long startMs = System.currentTimeMillis();
        log.info("ONBOARD started type={} mobile={}", userType, mask(mobile));
        try {
            assertMobileAvailableForOnboard(mobile, userType);
            UUID userId = UUID.randomUUID();
            String outletCode = switch (userType) {
                case "RETAILER", "MASTER_DISTRIBUTOR", "SUPER_ADMIN", "ADMIN", "HUBADMIN", "AUDITOR" -> generateGfinCode(
                        "USER_CODE_EXHAUSTED", "Could not generate a unique user code");
                default -> null;
            };
            String status = "RETAILER".equals(userType) ? "PENDING" : "ACTIVE";
            String mail = email == null || email.isBlank() ? null : email.trim().toLowerCase();
            if ("RETAILER".equals(userType) && (mail == null || !mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_REQUIRED",
                        "A valid email is required to onboard a retailer");
            }
            jdbc.update("""
                    INSERT INTO users (id, user_type, parent_id, created_by, full_name, mobile, email, password_hash, status, hub_id, code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, userId, userType, parentId, createdBy, fullName, mobile, mail,
                    passwordEncoder.encode(password), status, hubId, outletCode);
            if (("ADMIN".equals(userType) || "HUBADMIN".equals(userType)) && hubId != null) {
                jdbc.update("""
                        INSERT INTO hub_admin_assignments (hub_id, user_id)
                        VALUES (?, ?)
                        ON CONFLICT (hub_id, user_id) DO NOTHING
                        """, hubId, userId);
            }
            if (!"SUPER_ADMIN".equals(userType) && !"ADMIN".equals(userType) && !"HUBADMIN".equals(userType) && !"AUDITOR".equals(userType)) {
                walletService.createForUser(userId);
            }
            if ("RETAILER".equals(userType) || "MASTER_DISTRIBUTOR".equals(userType) || "HUBADMIN".equals(userType)) {
                walletService.ensureRewards(userId);
            }
            if ("MASTER_DISTRIBUTOR".equals(userType)) {
                jdbc.update("""
                        INSERT INTO agent_kyc_profiles (user_id, kyc_status)
                        VALUES (?, 'PENDING')
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
                        VALUES (?, ?::jsonb, 'PENDING')
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
        if ("AUDITOR".equals(intendedUserType)) {
            return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AUDITOR_ALREADY_EXISTS",
                    "Auditor already exists");
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
                SELECT shop_name, gstin, kyc_status::text AS kyc_status, bank_status, rejection_reason,
                       ovd_type, aadhaar_last4, aadhaar_verified_at, pan_verified_at, created_at,
                       paysprint_onboard_issued_at,
                       shop_address->>'city' AS city,
                       shop_address->>'state' AS state,
                       shop_address->>'pincode' AS pincode
                  FROM agent_kyc_profiles WHERE user_id = ?
                """, userId);
        if (kyc.isEmpty()) {
            return null;
        }
        Map<String, Object> row = kyc.get(0);
        row.put("banks", jdbc.queryForList("""
                SELECT bank_name, status, completed_at, last_message, is_dmt_active
                  FROM paysprint_bank_ekyc
                 WHERE user_id = ?
                 ORDER BY bank_name
                """, userId));
        return row;
    }

    private static String userListSql(String where) {
        return """
                SELECT u.id, u.user_type::text AS user_type, u.full_name, u.mobile, u.email, u.code,
                       u.status::text AS status, u.created_at, u.parent_id, u.hub_id, u.created_by,
                       p.full_name AS parent_name, c.full_name AS created_by_name, c.code AS created_by_code,
                       h.name AS hub_name, h.code AS hub_code,
                       COALESCE((
                           SELECT string_agg(DISTINCT h2.name, ', ' ORDER BY h2.name)
                             FROM hubs h2
                            WHERE h2.id = u.hub_id
                               OR EXISTS (
                                   SELECT 1 FROM hub_admin_assignments ha3
                                    WHERE ha3.hub_id = h2.id AND ha3.user_id = u.id
                               )
                       ), h.name, '') AS hub_names,
                       k.shop_name, k.kyc_status::text AS kyc_status, k.bank_status,
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
