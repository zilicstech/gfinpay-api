package com.fintech.sales;

import com.fintech.dmt.DmtService;
import com.fintech.identity.AdminAccess;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private static final String SELECT = """
            SELECT c.id, c.retailer_user_id, c.full_name, c.mobile, c.email, c.city, c.state, c.pincode,
                   c.employment_type, c.monthly_income, c.ekyc_status, c.ekyc_verified_at,
                   c.ovd_type, c.ovd_last4, c.created_by_role AS created_by, c.created_by_code,
                   COALESCE(creator.full_name, va.employee_name) AS created_by_name,
                   c.created_at, c.updated_at,
                   r.full_name AS retailer_name,
                   v.full_name AS vendor_name, v.code AS vendor_code
              FROM customers c
              LEFT JOIN users r ON r.id = c.retailer_user_id
              LEFT JOIN users creator ON creator.code = c.created_by_code AND c.created_by_role <> 'VENDOR'
              LEFT JOIN vendor_affiliates va ON va.gfin_code = c.created_by_code AND c.created_by_role = 'VENDOR'
              LEFT JOIN vendors v ON v.id = c.vendor_id
            """;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;

    public CustomerService(JdbcTemplate jdbc, AdminAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public List<Map<String, Object>> list(AuthPrincipal me, String mobile, UUID retailerUserId, UUID distributorUserId) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1 ");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        scope(me, sql, args);
        if (retailerUserId != null && distributorUserId != null) {
            throw ApiException.of(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "Use retailerId or distributorId, not both");
        }
        if (retailerUserId != null) {
            if (!me.platformStaff() && !"MASTER_DISTRIBUTOR".equals(me.userType())) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
            }
            access.assertNetworkUser(retailerUserId);
            sql.append(" AND c.retailer_user_id = ? ");
            args.add(retailerUserId);
        }
        if (distributorUserId != null) {
            if (!me.platformStaff()) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
            }
            access.assertNetworkUser(distributorUserId);
            sql.append(" AND (r.parent_id = ? OR c.retailer_user_id = ?) ");
            args.add(distributorUserId);
            args.add(distributorUserId);
        }
        String needle = digits(mobile);
        if (!needle.isEmpty()) {
            sql.append(" AND c.mobile LIKE ? ");
            args.add(needle + "%");
        }
        sql.append(" ORDER BY c.created_at DESC LIMIT 200 ");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> get(AuthPrincipal me, UUID id) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE c.id = ? ");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(id);
        scope(me, sql, args);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found");
        }
        Map<String, Object> customer = new LinkedHashMap<>(rows.get(0));
        customer.put("desk_manageable", deskPrincipal(me));
        customer.put("services", jdbc.queryForList("""
                SELECT l.id, l.state, l.budget, l.payment_link_url, l.link_opened_at, l.created_at,
                       i.code AS item_code, i.name AS item_name, cat.name AS category_name
                  FROM sales_leads l
                  JOIN catalog_items i ON i.id = l.catalog_item_id
                  JOIN catalog_categories cat ON cat.code = i.category_code
                 WHERE l.customer_id = ?
                 ORDER BY l.created_at DESC
                """, id));
        if (customer.get("retailer_user_id") != null) {
            customer.put("transactions", jdbc.queryForList("""
                    SELECT t.id, t.txn_type::text AS txn_type, t.state::text AS state, t.amount, t.created_at
                      FROM transactions t
                      JOIN dmt_senders s ON s.id = t.dmt_sender_id
                     WHERE s.mobile = ?
                       AND t.agent_user_id = ?
                     ORDER BY t.created_at DESC
                     LIMIT 100
                    """, customer.get("mobile"), customer.get("retailer_user_id")));
        } else {
            customer.put("transactions", List.of());
        }
        return customer;
    }

    @Transactional
    public Map<String, Object> create(AuthPrincipal me, String name, String mobile, String email,
                                      String city, String state, String pincode) {
        Creator creator = creator(me);
        String digits = mobileDigits(mobile);
        String trimmed = requiredName(name);
        String place = requiredPlace(city, "INVALID_CITY", "City is required");
        String region = requiredPlace(state, "INVALID_STATE", "State is required");
        String pin = pin(pincode);
        assertCustomerMobileAvailable(digits, null);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO customers
                        (id, retailer_user_id, full_name, mobile, email, city, state, pincode, created_by_role, created_by_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, me.userId(), trimmed, digits, optionalEmail(email), place, region, pin,
                    creator.role(), creator.code());
        } catch (DuplicateKeyException e) {
            throw customerAlreadyExists();
        }
        return get(me, id);
    }

    private record Creator(String role, String code) {}

    private Creator creator(AuthPrincipal me) {
        if (me == null) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        String role = switch (me.userType()) {
            case "RETAILER" -> "RETAILER";
            case "MASTER_DISTRIBUTOR" -> "DISTRIBUTOR";
            case "SUPER_ADMIN" -> "ADMIN";
            case "ADMIN" -> "ADMIN";
            default -> throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed to add customers");
        };
        String code;
        try {
            code = jdbc.queryForObject("SELECT code FROM users WHERE id = ?", String.class, me.userId());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        if (code == null || code.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "USER_CODE_MISSING", "This user has no code");
        }
        return new Creator(role, code);
    }

    @Transactional
    public Map<String, Object> update(AuthPrincipal me, UUID id, String name, String mobile, String email,
                                      String city, String state, String pincode) {
        get(me, id);
        String digits = mobileDigits(mobile);
        assertCustomerMobileAvailable(digits, id);
        String trimmed = requiredName(name);
        String place = requiredPlace(city, "INVALID_CITY", "City is required");
        String region = requiredPlace(state, "INVALID_STATE", "State is required");
        String pin = pin(pincode);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(trimmed);
        args.add(digits);
        args.add(optionalEmail(email));
        args.add(place);
        args.add(region);
        args.add(pin);
        String where = deskCustomerWhere(me, args, id);
        try {
            int n = jdbc.update("""
                    UPDATE customers
                       SET full_name = ?, mobile = ?, email = ?, city = ?, state = ?, pincode = ?, updated_at = now()
                     WHERE %s
                    """.formatted(where), args.toArray());
            if (n == 0) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found");
            }
        } catch (DuplicateKeyException e) {
            throw customerAlreadyExists();
        }
        return get(me, id);
    }

    private void assertCustomerMobileAvailable(String mobile, UUID exceptCustomerId) {
        Integer taken;
        if (exceptCustomerId == null) {
            taken = jdbc.queryForObject("SELECT COUNT(*)::int FROM customers WHERE mobile = ?", Integer.class, mobile);
        } else {
            taken = jdbc.queryForObject(
                    "SELECT COUNT(*)::int FROM customers WHERE mobile = ? AND id <> ?",
                    Integer.class, mobile, exceptCustomerId);
        }
        if (taken != null && taken > 0) {
            throw customerAlreadyExists();
        }
    }

    private static ApiException customerAlreadyExists() {
        return ApiException.of(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_EXISTS", "Customer already exists");
    }

    @Transactional
    public Map<String, Object> checkEligibility(AuthPrincipal me, UUID id) {
        Map<String, Object> customer = get(me, id);
        boolean profileOk = hasText(customer.get("full_name"))
                && String.valueOf(customer.get("mobile")).length() == 10
                && hasText(customer.get("email"));
        String reason = profileOk ? null : "Add the customer's name, 10-digit mobile, and email";
        String status = profileOk ? "ELIGIBLE" : "INELIGIBLE";
        List<Map<String, Object>> categories = jdbc.queryForList("SELECT code FROM catalog_categories");
        for (Map<String, Object> category : categories) {
            jdbc.update("""
                    INSERT INTO customer_category_eligibility (customer_id, category_code, status, reason, checked_at)
                    VALUES (?, ?, ?, ?, now())
                    ON CONFLICT (customer_id, category_code)
                    DO UPDATE SET status = EXCLUDED.status, reason = EXCLUDED.reason, checked_at = now()
                    """, id, category.get("code"), status, reason);
        }
        return get(me, id);
    }

    private void scopeAdminHubs(StringBuilder sql, java.util.List<Object> args) {
        List<UUID> hubs = access.hubIdsOrNull();
        if (hubs == null) {
            return;
        }
        if (hubs.isEmpty()) {
            sql.append(" AND FALSE ");
            return;
        }
        sql.append(" AND COALESCE(r.hub_id, v.hub_id) IN (");
        for (int i = 0; i < hubs.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            args.add(hubs.get(i));
        }
        sql.append(") ");
    }

    private void scope(AuthPrincipal me, StringBuilder sql, java.util.List<Object> args) {
        if (me == null) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        switch (me.userType()) {
            case "RETAILER" -> {
                sql.append(" AND c.retailer_user_id = ? ");
                args.add(me.userId());
            }
            case "MASTER_DISTRIBUTOR" -> {
                sql.append(" AND (c.retailer_user_id = ? OR r.parent_id = ?) ");
                args.add(me.userId());
                args.add(me.userId());
            }
            case "SUPER_ADMIN" -> {
                // network-wide
            }
            case "ADMIN" -> scopeAdminHubs(sql, args);
            default -> throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
    }

    static void requireRetailer(AuthPrincipal me) {
        if (me == null || !"RETAILER".equals(me.userType())) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a retailer can manage customers");
        }
    }

    static void requireSalesDesk(AuthPrincipal me) {
        if (me == null || (!"RETAILER".equals(me.userType()) && !"MASTER_DISTRIBUTOR".equals(me.userType()))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a retailer or distributor desk can do this");
        }
    }

    static boolean deskPrincipal(AuthPrincipal me) {
        return me != null && ("RETAILER".equals(me.userType()) || "MASTER_DISTRIBUTOR".equals(me.userType()));
    }

    /** WHERE clause for desk updates; appends customer id and scope args to {@code args}. */
    static String deskCustomerWhere(AuthPrincipal me, java.util.List<Object> args, UUID customerId) {
        args.add(customerId);
        if ("RETAILER".equals(me.userType())) {
            args.add(me.userId());
            return "id = ? AND retailer_user_id = ?";
        }
        if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            args.add(me.userId());
            args.add(me.userId());
            return """
                    id = ? AND (
                      retailer_user_id = ?
                      OR retailer_user_id IN (
                          SELECT id FROM users WHERE parent_id = ? AND user_type = 'RETAILER'
                      )
                    )
                    """;
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
    }

    static String digits(String mobile) {
        return DmtService.digits(mobile == null ? "" : mobile);
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static String mobileDigits(String mobile) {
        String digits = digits(mobile);
        if (digits.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE", "Enter a 10-digit mobile");
        }
        return digits;
    }

    private static String requiredName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NAME", "Customer name is required");
        }
        return trimmed;
    }

    private static String requiredPlace(String value, String code, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        }
        return trimmed;
    }

    private static String pin(String pincode) {
        String digits = digits(pincode);
        if (digits.length() != 6) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PINCODE", "Enter a 6-digit pincode");
        }
        return digits;
    }

    private static String optionalEmail(String email) {
        String trimmed = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.isBlank() ? null : trimmed;
    }
}
