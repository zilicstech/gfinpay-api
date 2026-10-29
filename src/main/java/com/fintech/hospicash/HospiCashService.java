package com.fintech.hospicash;

import com.fintech.commission.CommissionEngine;
import com.fintech.ledger.ServiceSettlement;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class HospiCashService {

    private static final Logger log = LoggerFactory.getLogger(HospiCashService.class);

    private static final BigDecimal DEFAULT_PRICE = new BigDecimal("1000.00");
    private static final String TXN = "HOSPI_CASH";
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static final String SALE_SELECT = """
            SELECT s.id, s.gfin_sale_code, s.sale_channel, s.retailer_user_id, s.distributor_user_id,
                   s.vendor_id, s.affiliate_id, s.customer_id, s.gender, s.customer_name, s.dob, s.address,
                   s.pincode, s.mobile, s.email, s.insured_relationship, s.nominee_name, s.nominee_relation,
                   s.nominee_dob, s.good_health, s.ped_details, s.ped_since, s.pep, s.plan_name,
                   s.premium_amount, s.state, s.vendor_payment_status, s.activation_status,
                   s.sale_txn_id, s.commission_txn_id,
                   s.exported_at, s.activated_at, s.partner_policy_ref, s.created_at, s.updated_at,
                   r.full_name AS retailer_name, r.code AS retailer_code,
                   v.full_name AS vendor_name, v.code AS vendor_code,
                   a.employee_name, a.gfin_code AS employee_gfin_code
              FROM hospi_cash_sales s
              LEFT JOIN users r ON r.id = s.retailer_user_id
              LEFT JOIN vendors v ON v.id = s.vendor_id
              LEFT JOIN vendor_affiliates a ON a.id = s.affiliate_id
            """;

    private final JdbcTemplate jdbc;
    private final PlatformServiceGate services;
    private final ServiceSettlement settlement;
    private final CommissionEngine commissionEngine;
    private final WalletService walletService;
    private final TransactionService transactionService;
    private final TransactionTemplate tx;

    public HospiCashService(JdbcTemplate jdbc, PlatformServiceGate services, ServiceSettlement settlement,
                            CommissionEngine commissionEngine, WalletService walletService,
                            TransactionService transactionService, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.services = services;
        this.settlement = settlement;
        this.commissionEngine = commissionEngine;
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.tx = new TransactionTemplate(transactions);
    }

    @Transactional
    public Map<String, Object> sell(AuthPrincipal me, Capture capture) {
        requireRetailer(me);
        services.requireEnabled(PlatformServiceGate.HOSPI_CASH);
        Fields fields = fields(capture);
        if (openRetailerSale(me.userId(), fields.mobile())) {
            throw openSale();
        }
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT parent_id, code FROM users WHERE id = ?", me.userId());
        String retailerCode = text(user.get("code"));
        if (retailerCode.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "USER_CODE_MISSING", "This user has no code");
        }
        UUID customerId = linkRetailerCustomer(me.userId(), retailerCode, fields);
        String code = nextCode();
        BigDecimal payable = pricing().payable();
        ServiceSettlement.Result debit = settlement.debitMain(me.userId(), TXN, payable, "HOSPI", code);
        UUID id = UUID.randomUUID();
        insert(id, code, "INTERNAL", me.userId(), (UUID) user.get("parent_id"), null, null, customerId,
                fields, "NOT_REQUIRED", debit.transactionId(), payable);
        return getOwned(me, id);
    }

    public List<Map<String, Object>> list(AuthPrincipal me, String state) {
        StringBuilder sql = new StringBuilder(SALE_SELECT).append(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if ("RETAILER".equals(me.userType())) {
            sql.append(" AND s.retailer_user_id = ? ");
            args.add(me.userId());
        } else if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            sql.append(" AND s.distributor_user_id = ? ");
            args.add(me.userId());
        } else if (!me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        String wanted = cleanState(state);
        if (wanted != null) {
            sql.append(" AND s.state = ? ");
            args.add(wanted);
        }
        sql.append(" ORDER BY s.created_at DESC ");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> get(AuthPrincipal me, UUID id) {
        return getOwned(me, id);
    }

    @Transactional
    public Map<String, Object> sellForAffiliate(String token, Capture capture) {
        services.requireEnabled(PlatformServiceGate.HOSPI_CASH);
        Fields fields = fields(capture);
        Map<String, Object> desk = affiliate(token);
        UUID affiliateId = (UUID) desk.get("affiliate_id");
        UUID vendorId = (UUID) desk.get("vendor_id");
        if (openAffiliateSale(affiliateId, fields.mobile())) {
            throw openSale();
        }
        UUID customerId = linkVendorCustomer(vendorId, affiliateId, text(desk.get("gfin_code")), fields);
        String code = nextCode();
        UUID id = UUID.randomUUID();
        insert(id, code, "EXTERNAL", null, null, vendorId, affiliateId, customerId, fields, "UNPAID", null,
                pricing().payable());
        return jdbc.queryForMap(SALE_SELECT + " WHERE s.id = ?", id);
    }

    public List<Map<String, Object>> adminList(String activation, LocalDate from, LocalDate to, String query) {
        LocalDate start = from == null ? LocalDate.now(ZONE) : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DATE", "The end date is before the start date");
        }
        String status = cleanActivation(activation == null || activation.isBlank() ? "PENDING" : activation);
        StringBuilder sql = new StringBuilder(SALE_SELECT).append("""
                 WHERE s.activation_status = ?
                   AND s.created_at >= ?
                   AND s.created_at < ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.add(Timestamp.from(start.atStartOfDay(ZONE).toInstant()));
        args.add(Timestamp.from(end.plusDays(1).atStartOfDay(ZONE).toInstant()));
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!needle.isBlank()) {
            sql.append("""
                     AND (
                       position(? in lower(s.gfin_sale_code)) > 0
                       OR position(? in lower(s.customer_name)) > 0
                       OR position(? in s.mobile) > 0
                       OR position(? in lower(coalesce(r.full_name, ''))) > 0
                       OR position(? in lower(coalesce(r.code, ''))) > 0
                       OR position(? in lower(coalesce(v.full_name, ''))) > 0
                       OR position(? in lower(coalesce(v.code, ''))) > 0
                       OR position(? in lower(coalesce(a.employee_name, ''))) > 0
                     )
                    """);
            for (int i = 0; i < 8; i++) {
                args.add(needle);
            }
        }
        sql.append(" ORDER BY s.created_at DESC ");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Transactional
    public Map<String, Object> markVendorPaid(UUID id) {
        Map<String, Object> sale = requireSale(id);
        if (!"EXTERNAL".equals(sale.get("sale_channel"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "VENDOR_PAYMENT_NOT_REQUIRED",
                    "Retailer sales are collected in the wallet");
        }
        if ("ACTIVATED".equals(sale.get("state")) || "REJECTED".equals(sale.get("state"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SALE_CLOSED", "This card is already closed");
        }
        jdbc.update("""
                UPDATE hospi_cash_sales
                   SET vendor_payment_status = 'COLLECTED', updated_at = now()
                 WHERE id = ? AND sale_channel = 'EXTERNAL'
                """, id);
        return requireSale(id);
    }

    @Transactional
    public Map<String, Object> markActivation(UUID id, String status) {
        String next = cleanActivation(status);
        Map<String, Object> sale = requireSale(id);
        if (!paymentCollected(sale)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_NOT_COLLECTED",
                    "Collect the payment before marking activation");
        }
        String current = activationOf(sale);
        if ("SUCCESS".equals(current)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVATION_CLOSED",
                    "This card is already activated");
        }
        if (next.equals(current)) {
            return sale;
        }
        if ("SUCCESS".equals(next)) {
            int moved = jdbc.update("""
                    UPDATE hospi_cash_sales
                       SET activation_status = 'SUCCESS',
                           state = 'ACTIVATED',
                           activated_at = COALESCE(activated_at, now()),
                           updated_at = now()
                     WHERE id = ? AND activation_status <> 'SUCCESS'
                    """, id);
            if (moved == 0) {
                return requireSale(id);
            }
            pay(requireSale(id));
            return requireSale(id);
        }
        if ("FAILED".equals(next)) {
            jdbc.update("""
                    UPDATE hospi_cash_sales
                       SET activation_status = 'FAILED', state = 'REJECTED', updated_at = now()
                     WHERE id = ? AND activation_status <> 'SUCCESS'
                    """, id);
            return requireSale(id);
        }
        String restored = sale.get("exported_at") == null ? "SOLD" : "EXPORTED";
        try {
            jdbc.update("""
                    UPDATE hospi_cash_sales
                       SET activation_status = 'PENDING', state = ?, updated_at = now()
                     WHERE id = ? AND activation_status = 'FAILED'
                    """, restored, id);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("uq_hospi_open")) {
                throw openSale();
            }
            throw e;
        }
        return requireSale(id);
    }

    public byte[] exportActivation(String activation, LocalDate from, LocalDate to, String query) {
        return HospiCashWorkbook.write(adminList(activation, from, to, query));
    }

    public Map<String, Object> importRecon(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_REQUIRED", "Choose the recon workbook");
        }
        List<HospiCashWorkbook.ReconRow> rows;
        try {
            rows = HospiCashWorkbook.read(file.getInputStream());
        } catch (IOException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_UNREADABLE", "This is not a readable Excel file");
        }
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_EMPTY",
                    "No rows with a GFIN Sale Id or mobile were found");
        }
        int activated = 0;
        int rejected = 0;
        int skipped = 0;
        int pending = 0;
        int unpaid = 0;
        int unmatched = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (HospiCashWorkbook.ReconRow row : rows) {
            String outcome;
            String failure = "";
            try {
                outcome = tx.execute(status -> applyRecon(row));
            } catch (RuntimeException e) {
                log.warn("HOSPI_RECON row={} sale={}", row.rowNumber(), row.saleCode(), e);
                outcome = "failed";
                failure = e.getClass().getSimpleName();
            }
            switch (outcome == null ? "failed" : outcome) {
                case "activated" -> activated++;
                case "rejected" -> rejected++;
                case "skipped" -> skipped++;
                case "pending" -> pending++;
                case "unpaid" -> unpaid++;
                case "failed" -> unmatched++;
                default -> unmatched++;
            }
            if (details.size() < 40 && !"skipped".equals(outcome)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("row", row.rowNumber());
                detail.put("result", outcome == null ? "failed" : outcome);
                detail.put("saleCode", row.saleCode());
                detail.put("status", row.status());
                if (!failure.isBlank()) {
                    detail.put("failure", failure);
                }
                details.add(detail);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activated", activated);
        out.put("rejected", rejected);
        out.put("skipped", skipped);
        out.put("pending", pending);
        out.put("unpaid", unpaid);
        out.put("unmatched", unmatched);
        out.put("rows", details);
        return out;
    }

    public List<Map<String, Object>> earnings() {
        return jdbc.queryForList("""
                SELECT e.id, e.vendor_id, e.sale_id, e.amount, e.created_at, e.settled_at,
                       v.full_name AS vendor_name, v.code AS vendor_code,
                       s.gfin_sale_code, s.customer_name, s.mobile
                  FROM hospi_cash_vendor_earnings e
                  JOIN vendors v ON v.id = e.vendor_id
                  JOIN hospi_cash_sales s ON s.id = e.sale_id
                 ORDER BY e.settled_at NULLS FIRST, e.created_at DESC
                """);
    }

    @Transactional
    public Map<String, Object> settleEarning(UUID id) {
        int updated = jdbc.update("""
                UPDATE hospi_cash_vendor_earnings
                   SET settled_at = COALESCE(settled_at, now())
                 WHERE id = ?
                """, id);
        if (updated == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "EARNING_NOT_FOUND", "Vendor earning was not found");
        }
        return jdbc.queryForMap("""
                SELECT e.id, e.amount, e.settled_at, s.gfin_sale_code
                  FROM hospi_cash_vendor_earnings e
                  JOIN hospi_cash_sales s ON s.id = e.sale_id
                 WHERE e.id = ?
                """, id);
    }

    private String applyRecon(HospiCashWorkbook.ReconRow row) {
        Map<String, Object> sale = match(row);
        if (sale == null) {
            return "unmatched";
        }
        if ("SUCCESS".equals(activationOf(sale)) || "ACTIVATED".equals(text(sale.get("state")))) {
            return "skipped";
        }
        if (!paymentCollected(sale)) {
            return "unpaid";
        }
        String decision = decision(row.status());
        if ("unknown".equals(decision)) {
            return "unmatched";
        }
        UUID id = (UUID) sale.get("id");
        if (row.policyRef() != null && !row.policyRef().isBlank()) {
            jdbc.update("""
                    UPDATE hospi_cash_sales
                       SET partner_policy_ref = COALESCE(NULLIF(?, ''), partner_policy_ref), updated_at = now()
                     WHERE id = ?
                    """, blankToNull(row.policyRef()), id);
        }
        if ("pending".equals(decision)) {
            if ("PENDING".equals(activationOf(sale))) {
                return "skipped";
            }
            markActivation(id, "PENDING");
            return "pending";
        }
        if ("rejected".equals(decision)) {
            if ("FAILED".equals(activationOf(sale))) {
                return "skipped";
            }
            markActivation(id, "FAILED");
            return "rejected";
        }
        markActivation(id, "SUCCESS");
        return "activated";
    }

    private void pay(Map<String, Object> sale) {
        if (sale.get("commission_txn_id") != null) {
            return;
        }
        BigDecimal amount = sale.get("premium_amount") instanceof BigDecimal premium ? premium : pricing().payable();
        if ("INTERNAL".equals(sale.get("sale_channel"))) {
            UUID retailerId = (UUID) sale.get("retailer_user_id");
            WalletService.WalletInfo wallet = walletService.lockByUser(retailerId);
            UUID txnId = transactionService.create(TXN, retailerId, wallet.walletId(), amount, BigDecimal.ZERO,
                    null, null, "HOSPI", null);
            transactionService.transition(txnId, "SUCCESS", sale.get("id").toString(), null);
            commissionEngine.creditRewards(txnId, retailerId, TXN, amount);
            jdbc.update("UPDATE hospi_cash_sales SET commission_txn_id = ? WHERE id = ?", txnId, sale.get("id"));
            return;
        }
        BigDecimal vendorCut = pricing().vendorCommission();
        if (vendorCut.signum() <= 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO hospi_cash_vendor_earnings (vendor_id, sale_id, amount)
                VALUES (?, ?, ?)
                ON CONFLICT (sale_id) DO NOTHING
                """, sale.get("vendor_id"), sale.get("id"), vendorCut);
    }

    public Map<String, Object> offer() {
        Pricing price = pricing();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sellingPrice", price.sellingPrice());
        out.put("discount", price.discount());
        out.put("payable", price.payable());
        return out;
    }

    public Map<String, Object> adminPricing() {
        Map<String, Object> out = offer();
        out.put("vendorCommission", pricing().vendorCommission());
        return out;
    }

    @Transactional
    public Map<String, Object> savePricing(BigDecimal sellingPrice, BigDecimal discount) {
        BigDecimal price = money(sellingPrice, "Selling price");
        BigDecimal off = money(discount, "Discount");
        if (price.signum() <= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRICE", "Selling price must be greater than zero");
        }
        if (off.compareTo(price) >= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRICE", "Discount must be less than the selling price");
        }
        int updated = jdbc.update("""
                UPDATE hospi_cash_settings
                   SET selling_price = ?, discount_amount = ?, updated_at = now()
                 WHERE id IS TRUE
                """, price, off);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO hospi_cash_settings (id, selling_price, discount_amount, vendor_commission)
                    VALUES (TRUE, ?, ?, 0)
                    """, price, off);
        }
        return adminPricing();
    }

    private Pricing pricing() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT selling_price, discount_amount, vendor_commission
                  FROM hospi_cash_settings
                 WHERE id IS TRUE
                """);
        if (rows.isEmpty()) {
            return new Pricing(DEFAULT_PRICE, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        Map<String, Object> row = rows.get(0);
        BigDecimal price = moneyOr(row.get("selling_price"), DEFAULT_PRICE);
        BigDecimal discount = moneyOr(row.get("discount_amount"), BigDecimal.ZERO);
        BigDecimal vendor = moneyOr(row.get("vendor_commission"), BigDecimal.ZERO);
        if (discount.compareTo(price) >= 0) {
            discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return new Pricing(price, discount, vendor);
    }

    private static BigDecimal money(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRICE", label + " cannot be negative");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal moneyOr(Object value, BigDecimal fallback) {
        BigDecimal amount = value instanceof BigDecimal decimal ? decimal : fallback;
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private record Pricing(BigDecimal sellingPrice, BigDecimal discount, BigDecimal vendorCommission) {
        BigDecimal payable() {
            return sellingPrice.subtract(discount).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private Map<String, Object> match(HospiCashWorkbook.ReconRow row) {
        if (!row.saleCode().isBlank()) {
            List<Map<String, Object>> byCode = jdbc.queryForList(
                    SALE_SELECT + " WHERE upper(s.gfin_sale_code) = upper(?)", row.saleCode().trim());
            if (!byCode.isEmpty()) {
                return byCode.get(0);
            }
        }
        if (row.mobile().length() == 10 && !row.email().isBlank()) {
            List<Map<String, Object>> byContact = jdbc.queryForList(SALE_SELECT + """
                     WHERE s.mobile = ? AND lower(s.email) = ? AND s.state IN ('SOLD', 'EXPORTED')
                     ORDER BY s.created_at
                    """, row.mobile(), row.email());
            if (!byContact.isEmpty()) {
                return byContact.get(0);
            }
        }
        return null;
    }

    private static String decision(String status) {
        String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "success" -> "activated";
            case "failed" -> "rejected";
            case "pending" -> "pending";
            default -> "unknown";
        };
    }

    private void insert(UUID id, String code, String channel, UUID retailerId, UUID distributorId, UUID vendorId,
                        UUID affiliateId, UUID customerId, Fields fields, String payment, UUID saleTxnId,
                        BigDecimal premium) {
        try {
            jdbc.update("""
                    INSERT INTO hospi_cash_sales
                        (id, gfin_sale_code, sale_channel, retailer_user_id, distributor_user_id,
                         vendor_id, affiliate_id, customer_id, gender, customer_name, dob, address, pincode,
                         mobile, email, insured_relationship, nominee_name, nominee_relation, nominee_dob,
                         good_health, ped_details, ped_since, pep, plan_name, premium_amount, state,
                         vendor_payment_status, activation_status, sale_txn_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SOLD', ?, 'PENDING', ?)
                    """,
                    id, code, channel, retailerId, distributorId, vendorId, affiliateId, customerId,
                    fields.gender(), fields.name(), fields.dob(), fields.address(), fields.pincode(),
                    fields.mobile(), fields.email(), fields.relationship(), fields.nomineeName(),
                    fields.nomineeRelation(), fields.nomineeDob(), fields.goodHealth(), fields.pedDetails(),
                    fields.pedSince(), fields.pep(), fields.plan(), premium, payment, saleTxnId);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("uq_hospi_open")) {
                throw openSale();
            }
            throw e;
        }
    }

    private UUID linkRetailerCustomer(UUID retailerId, String code, Fields fields) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, retailer_user_id FROM customers WHERE mobile = ?", fields.mobile());
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO customers
                        (id, retailer_user_id, full_name, mobile, email, pincode, created_by_role, created_by_code)
                    VALUES (?, ?, ?, ?, ?, ?, 'RETAILER', ?)
                    """,                     id, retailerId, fields.name(), fields.mobile(), fields.email(), fields.pincode(), code);
            return id;
        }
        Map<String, Object> row = existing.get(0);
        if (!retailerId.equals(row.get("retailer_user_id"))) {
            return null;
        }
        UUID id = (UUID) row.get("id");
        jdbc.update("""
                UPDATE customers
                   SET full_name = ?, email = ?, pincode = ?, updated_at = now()
                 WHERE id = ?
                """, fields.name(), fields.email(), fields.pincode(), id);
        return id;
    }

    private UUID linkVendorCustomer(UUID vendorId, UUID affiliateId, String gfinCode, Fields fields) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, created_by_role FROM customers WHERE mobile = ?", fields.mobile());
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            try {
                jdbc.update("""
                        INSERT INTO customers
                            (id, full_name, mobile, email, pincode, created_by_role, created_by_code, vendor_id, affiliate_id)
                        VALUES (?, ?, ?, ?, ?, 'VENDOR', ?, ?, ?)
                        """, id, fields.name(), fields.mobile(), fields.email(), fields.pincode(),
                        gfinCode, vendorId, affiliateId);
                return id;
            } catch (DataIntegrityViolationException e) {
                return null;
            }
        }
        Map<String, Object> row = existing.get(0);
        if (!"VENDOR".equals(row.get("created_by_role"))) {
            return null;
        }
        UUID id = (UUID) row.get("id");
        jdbc.update("""
                UPDATE customers
                   SET full_name = ?, email = ?, pincode = ?, vendor_id = ?, affiliate_id = ?, updated_at = now()
                 WHERE id = ?
                """, fields.name(), fields.email(), fields.pincode(), vendorId, affiliateId, id);
        return id;
    }

    private Map<String, Object> affiliate(String token) {
        String ref = token == null ? "" : token.trim();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id AS affiliate_id, a.vendor_id, a.gfin_code, v.status AS vendor_status
                  FROM vendor_affiliates a
                  JOIN vendors v ON v.id = a.vendor_id
                 WHERE upper(a.gfin_code) = upper(?) OR a.apply_token = ?
                """, ref, ref);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AFFILIATE_NOT_FOUND", "This link is not valid");
        }
        if (!"ACTIVE".equals(rows.get(0).get("vendor_status"))) {
            throw ApiException.of(HttpStatus.GONE, "VENDOR_INACTIVE", "This URL is not active");
        }
        return rows.get(0);
    }

    private boolean openRetailerSale(UUID retailerId, String mobile) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)::int FROM hospi_cash_sales
                 WHERE retailer_user_id = ? AND mobile = ? AND state IN ('SOLD', 'EXPORTED')
                """, Integer.class, retailerId, mobile);
        return count != null && count > 0;
    }

    private boolean openAffiliateSale(UUID affiliateId, String mobile) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)::int FROM hospi_cash_sales
                 WHERE affiliate_id = ? AND mobile = ? AND state IN ('SOLD', 'EXPORTED')
                """, Integer.class, affiliateId, mobile);
        return count != null && count > 0;
    }

    private Map<String, Object> getOwned(AuthPrincipal me, UUID id) {
        Map<String, Object> sale = requireSale(id);
        if ("RETAILER".equals(me.userType()) && !me.userId().equals(sale.get("retailer_user_id"))) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SALE_NOT_FOUND", "Sale was not found");
        }
        if ("MASTER_DISTRIBUTOR".equals(me.userType()) && !me.userId().equals(sale.get("distributor_user_id"))) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SALE_NOT_FOUND", "Sale was not found");
        }
        if (!"RETAILER".equals(me.userType()) && !"MASTER_DISTRIBUTOR".equals(me.userType()) && !me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        return sale;
    }

    private Map<String, Object> requireSale(UUID id) {
        try {
            return jdbc.queryForMap(SALE_SELECT + " WHERE s.id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SALE_NOT_FOUND", "Sale was not found");
        }
    }

    private void requireRetailer(AuthPrincipal me) {
        if (me == null || !"RETAILER".equals(me.userType())) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a retailer can sell Hospi Cash");
        }
        String status;
        try {
            status = jdbc.queryForObject("SELECT status::text FROM users WHERE id = ?", String.class, me.userId());
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        if (!"ACTIVE".equals(status)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE", "Account is not active");
        }
    }

    private String nextCode() {
        for (int i = 0; i < 5; i++) {
            String code = "HC" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            Integer taken = jdbc.queryForObject(
                    "SELECT count(*)::int FROM hospi_cash_sales WHERE gfin_sale_code = ?", Integer.class, code);
            if (taken == null || taken == 0) {
                return code;
            }
        }
        throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "SALE_CODE_FAILED", "Could not create a sale id");
    }

    private static AuthPrincipal platform() {
        return new AuthPrincipal(null, null, "SUPER_ADMIN", null);
    }

    private static ApiException openSale() {
        return ApiException.of(HttpStatus.CONFLICT, "OPEN_SALE_EXISTS",
                "This mobile already has a Hospi Cash card waiting for activation");
    }

    private static boolean paymentCollected(Map<String, Object> sale) {
        if ("INTERNAL".equals(sale.get("sale_channel"))) {
            return true;
        }
        return "COLLECTED".equals(sale.get("vendor_payment_status"));
    }

    private static String activationOf(Map<String, Object> sale) {
        String value = text(sale.get("activation_status"));
        return value.isBlank() ? "PENDING" : value;
    }

    private static String cleanActivation(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("PENDING") && !value.equals("SUCCESS") && !value.equals("FAILED")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACTIVATION",
                    "Activation must be pending, success, or failed");
        }
        return value;
    }

    private static String cleanState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String value = state.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("SOLD") && !value.equals("EXPORTED") && !value.equals("ACTIVATED") && !value.equals("REJECTED")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE", "Unknown sale state");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public record Capture(
            String gender,
            String customerName,
            String dob,
            String address,
            String pincode,
            String mobile,
            String email,
            String insuredRelationship,
            String nomineeName,
            String nomineeRelation,
            String nomineeDob,
            String goodHealth,
            String pedDetails,
            String pedSince,
            String pep,
            String plan) {}

    private record Fields(String gender, String name, LocalDate dob, String address, String pincode, String mobile,
                          String email, String relationship, String nomineeName, String nomineeRelation,
                          LocalDate nomineeDob, String goodHealth, String pedDetails, String pedSince, String pep,
                          String plan) {}

    private static Fields fields(Capture capture) {
        if (capture == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SALE", "Sale details are required");
        }
        String gender = switch (capture.gender() == null ? "" : capture.gender().trim().toUpperCase(Locale.ROOT)) {
            case "M", "MALE" -> "M";
            case "F", "FEMALE" -> "F";
            default -> throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GENDER", "Choose M or F");
        };
        return new Fields(
                gender,
                required(capture.customerName(), "INVALID_NAME", "Customer name is required"),
                day(capture.dob(), "INVALID_DOB", "Enter the customer date of birth"),
                required(capture.address(), "INVALID_ADDRESS", "Address is required"),
                pin(capture.pincode()),
                mobile(capture.mobile()),
                email(capture.email()),
                required(capture.insuredRelationship(), "INVALID_RELATIONSHIP", "Insured relationship is required"),
                required(capture.nomineeName(), "INVALID_NOMINEE", "Nominee name is required"),
                required(capture.nomineeRelation(), "INVALID_NOMINEE", "Nominee relation is required"),
                day(capture.nomineeDob(), "INVALID_NOMINEE_DOB", "Enter the nominee date of birth"),
                required(capture.goodHealth(), "INVALID_HEALTH", "Good health declaration is required"),
                required(capture.pedDetails(), "INVALID_PED", "PED details are required"),
                required(capture.pedSince(), "INVALID_PED", "PED since is required"),
                required(capture.pep(), "INVALID_PEP", "PEP is required"),
                required(capture.plan(), "INVALID_PLAN", "Plan is required"));
    }

    private static String required(String value, String code, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        }
        return trimmed;
    }

    private static LocalDate day(String value, String code, String message) {
        String trimmed = value == null ? "" : value.trim();
        try {
            LocalDate date = LocalDate.parse(trimmed);
            if (date.isAfter(LocalDate.now())) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
            }
            return date;
        } catch (DateTimeParseException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        }
    }

    private static String pin(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.length() != 6) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PINCODE", "Enter a 6-digit pin code");
        }
        return digits;
    }

    private static String mobile(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE", "Enter a 10-digit mobile");
        }
        return digits;
    }

    private static String email(String value) {
        String mail = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL", "Enter a valid email");
        }
        return mail;
    }
}
