package com.fintech.vendor;

import com.fintech.identity.AdminAccess;
import com.fintech.sales.SaleNetworkSnapshot;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PublicAppUrl;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VendorService {

    private static final String VENDOR_SELECT = """
            SELECT v.id, v.hub_id, v.full_name, v.mobile, v.email, v.code, v.status,
                   v.created_at, v.updated_at, h.name AS hub_name, h.code AS hub_code
              FROM vendors v
              JOIN hubs h ON h.id = v.hub_id
            """;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;
    private final GfinCodeGenerator codes;
    private final String publicAppUrl;

    public VendorService(JdbcTemplate jdbc, AdminAccess access, GfinCodeGenerator codes,
                         @Value("${app.public-app-url}") String publicAppUrl) {
        this.jdbc = jdbc;
        this.access = access;
        this.codes = codes;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
    }

    public List<Map<String, Object>> listAdmin() {
        access.requirePlatformStaff();
        StringBuilder sql = new StringBuilder(VENDOR_SELECT).append(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        scopeHubs(sql, args);
        sql.append(" ORDER BY v.created_at DESC LIMIT 500 ");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> getAdmin(UUID id) {
        access.requirePlatformStaff();
        List<Map<String, Object>> rows = jdbc.queryForList(VENDOR_SELECT + " WHERE v.id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "VENDOR_NOT_FOUND", "Vendor not found");
        }
        Map<String, Object> vendor = rows.get(0);
        assertHub((UUID) vendor.get("hub_id"));
        vendor.put("public_url", publicAppUrl + "/v/" + vendor.get("code"));
        List<Map<String, Object>> affiliates = jdbc.queryForList("""
                SELECT id, employee_name, employee_code, gfin_code, apply_token, created_at
                  FROM vendor_affiliates WHERE vendor_id = ? ORDER BY created_at DESC
                """, id);
        for (Map<String, Object> affiliate : affiliates) {
            affiliate.put("desk_url", deskUrlForAffiliate(affiliate));
        }
        vendor.put("affiliates", affiliates);
        vendor.put("sales", jdbc.queryForList("""
                SELECT l.id, c.full_name AS customer_name, c.mobile AS customer_mobile,
                       l.product_code AS product_key, l.partner_status, l.partner_status_at,
                       l.partner_user_id, l.state, l.created_at AS first_seen_at, l.updated_at,
                       l.retailer_name AS employee_name, l.retailer_code AS employee_code
                  FROM sales_leads l
                  JOIN customers c ON c.id = l.customer_id
                 WHERE l.sale_channel = 'EXTERNAL' AND l.distributor_user_id = ?
                 ORDER BY l.updated_at DESC
                 LIMIT 500
                """, id));
        return vendor;
    }

    @Transactional
    public Map<String, Object> create(String fullName, String mobile, String email, UUID hubId) {
        access.requirePlatformStaff();
        assertHub(hubId);
        requireActiveHub(hubId);
        UUID id = UUID.randomUUID();
        String code = codes.nextCode("VENDOR_CODE_EXHAUSTED", "Could not generate a unique vendor code");
        try {
            jdbc.update("""
                    INSERT INTO vendors (id, hub_id, full_name, mobile, email, code, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, id, hubId, fullName.trim(), normalizeMobile(mobile), blankToNull(email), code);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "VENDOR_EXISTS",
                    "A vendor with this mobile already exists");
        }
        return getAdmin(id);
    }

    public Map<String, Object> publicVendor(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(VENDOR_SELECT + " WHERE v.code = ? AND v.status = 'ACTIVE'", code);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "VENDOR_NOT_FOUND", "Vendor not found");
        }
        Map<String, Object> v = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", v.get("code"));
        out.put("full_name", v.get("full_name"));
        out.put("hub_name", v.get("hub_name"));
        out.put("products", jdbc.queryForList("""
                SELECT id, code, name, product_key
                  FROM catalog_items
                 WHERE category_code = 'FD_CARD' AND provider = 'ZET' AND active = TRUE
                 ORDER BY sort_order
                """));
        return out;
    }

    @Transactional
    public Map<String, Object> mintAffiliateLink(String vendorCode, String employeeName, String employeeCode) {
        List<Map<String, Object>> vendors = jdbc.queryForList(
                "SELECT id, code FROM vendors WHERE code = ? AND status = 'ACTIVE'", vendorCode);
        if (vendors.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "VENDOR_NOT_FOUND", "Vendor not found");
        }
        UUID vendorId = (UUID) vendors.get(0).get("id");
        String empCode = employeeCode.trim();
        String empName = employeeName.trim();
        if (empCode.isBlank() || empName.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMPLOYEE",
                    "Employee name and code are required");
        }
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, apply_token, gfin_code FROM vendor_affiliates
                 WHERE vendor_id = ? AND employee_code = ?
                """, vendorId, empCode);
        if (!existing.isEmpty()) {
            return deskLinkResponse(existing.get(0), empName, empCode);
        }
        UUID affiliateRef = UUID.randomUUID();
        String gfinCode = codes.nextCode("EMPLOYEE_CODE_EXHAUSTED", "Could not generate employee GFIN code");
        String applyToken = gfinCode;
        try {
            jdbc.update("""
                    INSERT INTO vendor_affiliates
                        (id, vendor_id, employee_name, employee_code, affiliate_ref, apply_token, gfin_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), vendorId, empName, empCode, affiliateRef, applyToken, gfinCode);
        } catch (DuplicateKeyException e) {
            existing = jdbc.queryForList("""
                    SELECT id, apply_token, gfin_code FROM vendor_affiliates
                     WHERE vendor_id = ? AND employee_code = ?
                    """, vendorId, empCode);
            if (!existing.isEmpty()) {
                return deskLinkResponse(existing.get(0), empName, empCode);
            }
            throw e;
        }
        return Map.of(
                "apply_url", deskUrlForGfin(gfinCode),
                "employee_name", empName,
                "employee_code", empCode,
                "employee_gfin_code", gfinCode);
    }

    /** Public desk URL segment: employee {@code gfin_code} (legacy {@code apply_token} still resolves). */
    public Map<String, Object> publicAffiliate(String deskRef) {
        String ref = normalizeDeskRef(deskRef);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.employee_name, a.employee_code, a.gfin_code AS employee_gfin_code,
                       v.full_name AS vendor_name, v.code AS vendor_code
                  FROM vendor_affiliates a
                  JOIN vendors v ON v.id = a.vendor_id
                 WHERE (upper(a.gfin_code) = upper(?) OR a.apply_token = ?) AND v.status = 'ACTIVE'
                """, ref, ref);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AFFILIATE_NOT_FOUND", "This link is not valid");
        }
        Map<String, Object> row = rows.get(0);
        List<Map<String, Object>> products = jdbc.queryForList("""
                SELECT id, code, name, product_key
                  FROM catalog_items
                 WHERE category_code = 'FD_CARD' AND provider = 'ZET' AND active = TRUE
                 ORDER BY sort_order
                """);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendor_name", row.get("vendor_name"));
        out.put("employee_name", row.get("employee_name"));
        out.put("employee_gfin_code", row.get("employee_gfin_code"));
        out.put("products", products);
        return out;
    }

    @Transactional
    public Map<String, Object> generateCustomerCardLink(String deskRef, String customerName, String mobile,
                                                        String city, String state, String pincode,
                                                        UUID catalogItemId) {
        String ref = normalizeDeskRef(deskRef);
        List<Map<String, Object>> desk = jdbc.queryForList("""
                SELECT a.id AS affiliate_id, a.gfin_code, a.employee_name, a.employee_code,
                       a.vendor_id, v.hub_id, v.full_name AS vendor_name, v.code AS vendor_code,
                       h.name AS hub_name,
                       i.apply_url, i.name AS item_name, i.category_code, i.provider, i.product_key
                  FROM vendor_affiliates a
                  JOIN vendors v ON v.id = a.vendor_id
                  LEFT JOIN hubs h ON h.id = v.hub_id
                  JOIN catalog_items i ON i.id = ?
                 WHERE (upper(a.gfin_code) = upper(?) OR a.apply_token = ?) AND v.status = 'ACTIVE'
                   AND i.category_code = 'FD_CARD' AND i.provider = 'ZET' AND i.active = TRUE
                """, catalogItemId, ref, ref);
        if (desk.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AFFILIATE_NOT_FOUND", "This link is not valid");
        }
        Map<String, Object> row = desk.get(0);
        String template = String.valueOf(row.get("apply_url"));
        if (template.isBlank() || "null".equals(template)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LINK_UNAVAILABLE", "This card link is not configured");
        }
        UUID affiliateId = (UUID) row.get("affiliate_id");
        UUID vendorId = (UUID) row.get("vendor_id");
        String gfinCode = String.valueOf(row.get("gfin_code"));
        String digits = normalizeMobile(mobile);
        if (digits.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE", "Enter a 10-digit mobile");
        }
        String name = customerName == null ? "" : customerName.trim();
        if (name.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NAME", "Customer name is required");
        }
        String place = requiredPlace(city, "INVALID_CITY", "City is required");
        String region = requiredPlace(state, "INVALID_STATE", "State is required");
        String pin = optionalPin(pincode);
        UUID customerId = upsertVendorCustomer(vendorId, affiliateId, gfinCode, name, digits, place, region, pin);
        UUID trackingRef = UUID.randomUUID();
        String linkToken = UUID.randomUUID().toString().replace("-", "");
        String applyUrl = publicAppUrl + "/apply/" + linkToken;
        String saleType = String.valueOf(row.get("category_code"));
        String saleProvider = SaleNetworkSnapshot.normalizeProvider(String.valueOf(row.get("provider")));
        UUID hubId = (UUID) row.get("hub_id");
        jdbc.update("""
                INSERT INTO sales_leads
                    (id, customer_id, catalog_item_id, retailer_user_id, distributor_user_id, state,
                     provider_refid, link_token, payment_link_url, sale_type, sale_provider, hub_id,
                     sale_channel,
                     distributor_code, distributor_name, retailer_code, retailer_name, hub_name, product_code,
                     state_history)
                VALUES (?, ?, ?, ?, ?, 'LINK_CREATED', ?, ?, ?, ?, ?, ?, 'EXTERNAL',
                        ?, ?, ?, ?, ?, ?,
                        jsonb_build_array(jsonb_build_object('state', 'LINK_CREATED', 'at', now())))
                """,
                trackingRef,
                customerId,
                catalogItemId,
                affiliateId,
                vendorId,
                trackingRef.toString(),
                linkToken,
                applyUrl,
                saleType,
                saleProvider,
                hubId,
                String.valueOf(row.get("vendor_code")),
                String.valueOf(row.get("vendor_name")),
                String.valueOf(row.get("employee_code")),
                String.valueOf(row.get("employee_name")),
                row.get("hub_name") == null ? "—" : String.valueOf(row.get("hub_name")),
                String.valueOf(row.get("product_key")));
        jdbc.update("""
                INSERT INTO vendor_card_links (id, affiliate_id, customer_id, catalog_item_id)
                VALUES (?, ?, ?, ?)
                """, trackingRef, affiliateId, customerId, catalogItemId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", applyUrl);
        out.put("tracking_ref", trackingRef.toString());
        out.put("customer_id", customerId.toString());
        out.put("item_name", row.get("item_name"));
        return out;
    }

    private UUID upsertVendorCustomer(UUID vendorId, UUID affiliateId, String gfinCode, String name, String mobile,
                                      String city, String state, String pincode) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, created_by_role FROM customers WHERE mobile = ?", mobile);
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            try {
                jdbc.update("""
                        INSERT INTO customers
                            (id, retailer_user_id, full_name, mobile, city, state, pincode,
                             created_by_role, created_by_code, vendor_id, affiliate_id)
                        VALUES (?, NULL, ?, ?, ?, ?, ?, 'VENDOR', ?, ?, ?)
                        """, id, name, mobile, city, state, pincode, gfinCode, vendorId, affiliateId);
            } catch (DuplicateKeyException e) {
                throw ApiException.of(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_EXISTS", "Customer already exists");
            }
            return id;
        }
        Map<String, Object> row = existing.get(0);
        String role = String.valueOf(row.get("created_by_role"));
        if (!"VENDOR".equals(role)) {
            throw ApiException.of(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_EXISTS",
                    "This mobile is already registered in the retailer network");
        }
        UUID id = (UUID) row.get("id");
        jdbc.update("""
                UPDATE customers
                   SET full_name = ?, city = ?, state = ?, pincode = COALESCE(?, pincode),
                       vendor_id = ?, affiliate_id = ?, updated_at = now()
                 WHERE id = ?
                """, name, city, state, pincode, vendorId, affiliateId, id);
        return id;
    }

    public OptionalVendorLead findVendorLeadByRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(ref.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<Map<String, Object>> links = jdbc.queryForList("""
                SELECT l.id AS tracking_ref, c.id AS customer_id, c.full_name AS customer_name, c.mobile AS customer_mobile,
                       a.id AS affiliate_id, a.vendor_id, a.affiliate_ref, a.employee_name, a.employee_code, a.gfin_code,
                       v.full_name AS vendor_name, v.code AS vendor_code, v.hub_id
                  FROM vendor_card_links l
                  JOIN customers c ON c.id = l.customer_id
                  JOIN vendor_affiliates a ON a.id = l.affiliate_id
                  JOIN vendors v ON v.id = a.vendor_id
                 WHERE l.id = ?
                """, parsed);
        if (!links.isEmpty()) {
            return OptionalVendorLead.from(links.get(0));
        }
        OptionalAffiliate legacy = findAffiliateByRef(ref);
        if (legacy == null) {
            return null;
        }
        return OptionalVendorLead.fromLegacy(legacy);
    }

    public OptionalAffiliate findAffiliateByRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        try {
            UUID.fromString(ref.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id AS affiliate_id, a.vendor_id, a.affiliate_ref, a.employee_name, a.employee_code,
                       v.full_name AS vendor_name, v.code AS vendor_code, v.hub_id
                  FROM vendor_affiliates a
                  JOIN vendors v ON v.id = a.vendor_id
                 WHERE a.affiliate_ref = ?
                """, UUID.fromString(ref.trim()));
        if (rows.isEmpty()) {
            return null;
        }
        return OptionalAffiliate.from(rows.get(0));
    }

    @Transactional
    public void upsertVendorSale(OptionalVendorLead lead, String phone, String name, String productKey,
                                 String partnerStatus, Instant partnerStatusAt, String partnerUserId,
                                 Map<String, Object> payload) {
        String resolvedName = name == null || name.isBlank() ? lead.customerName() : name;
        String resolvedPhone = phone == null || phone.isBlank() ? lead.customerMobile() : phone;
        if (lead.trackingRef() != null) {
            jdbc.update("""
                    INSERT INTO vendor_sales
                        (id, vendor_id, affiliate_id, affiliate_ref, tracking_ref, customer_id,
                         customer_mobile, customer_name, product_key,
                         partner_status, partner_status_at, partner_user_id, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (tracking_ref)
                    DO UPDATE SET
                        customer_name = COALESCE(EXCLUDED.customer_name, vendor_sales.customer_name),
                        customer_mobile = EXCLUDED.customer_mobile,
                        partner_status = EXCLUDED.partner_status,
                        partner_status_at = EXCLUDED.partner_status_at,
                        partner_user_id = COALESCE(EXCLUDED.partner_user_id, vendor_sales.partner_user_id),
                        payload = EXCLUDED.payload,
                        updated_at = now()
                    """,
                    UUID.randomUUID(),
                    lead.vendorId(),
                    lead.affiliateId(),
                    lead.affiliateRef(),
                    lead.trackingRef(),
                    lead.customerId(),
                    resolvedPhone,
                    resolvedName,
                    productKey,
                    partnerStatus,
                    partnerStatusAt == null ? null : Timestamp.from(partnerStatusAt),
                    partnerUserId,
                    payloadJson(payload));
            return;
        }
        jdbc.update("""
                INSERT INTO vendor_sales
                    (id, vendor_id, affiliate_id, affiliate_ref, customer_mobile, customer_name, product_key,
                     partner_status, partner_status_at, partner_user_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (affiliate_ref, customer_mobile, product_key)
                DO UPDATE SET
                    customer_name = COALESCE(EXCLUDED.customer_name, vendor_sales.customer_name),
                    partner_status = EXCLUDED.partner_status,
                    partner_status_at = EXCLUDED.partner_status_at,
                    partner_user_id = COALESCE(EXCLUDED.partner_user_id, vendor_sales.partner_user_id),
                    payload = EXCLUDED.payload,
                    updated_at = now()
                """,
                UUID.randomUUID(),
                lead.vendorId(),
                lead.affiliateId(),
                lead.affiliateRef(),
                resolvedPhone,
                resolvedName,
                productKey,
                partnerStatus,
                partnerStatusAt == null ? null : Timestamp.from(partnerStatusAt),
                partnerUserId,
                payloadJson(payload));
    }

    public record OptionalAffiliate(
            UUID affiliateId,
            UUID vendorId,
            UUID affiliateRef,
            String employeeName,
            String employeeCode,
            String vendorName,
            String vendorCode,
            UUID hubId) {

        static OptionalAffiliate from(Map<String, Object> row) {
            return new OptionalAffiliate(
                    (UUID) row.get("affiliate_id"),
                    (UUID) row.get("vendor_id"),
                    (UUID) row.get("affiliate_ref"),
                    String.valueOf(row.get("employee_name")),
                    String.valueOf(row.get("employee_code")),
                    String.valueOf(row.get("vendor_name")),
                    String.valueOf(row.get("vendor_code")),
                    (UUID) row.get("hub_id"));
        }
    }

    public record OptionalVendorLead(
            UUID trackingRef,
            UUID customerId,
            String customerName,
            String customerMobile,
            UUID affiliateId,
            UUID vendorId,
            UUID affiliateRef,
            String employeeName,
            String employeeCode,
            String employeeGfinCode,
            String vendorName,
            String vendorCode,
            UUID hubId) {

        static OptionalVendorLead from(Map<String, Object> row) {
            return new OptionalVendorLead(
                    (UUID) row.get("tracking_ref"),
                    (UUID) row.get("customer_id"),
                    str(row.get("customer_name")),
                    str(row.get("customer_mobile")),
                    (UUID) row.get("affiliate_id"),
                    (UUID) row.get("vendor_id"),
                    (UUID) row.get("affiliate_ref"),
                    str(row.get("employee_name")),
                    str(row.get("employee_code")),
                    str(row.get("gfin_code")),
                    str(row.get("vendor_name")),
                    str(row.get("vendor_code")),
                    (UUID) row.get("hub_id"));
        }

        static OptionalVendorLead fromLegacy(OptionalAffiliate affiliate) {
            return new OptionalVendorLead(
                    null,
                    null,
                    null,
                    null,
                    affiliate.affiliateId(),
                    affiliate.vendorId(),
                    affiliate.affiliateRef(),
                    affiliate.employeeName(),
                    affiliate.employeeCode(),
                    null,
                    affiliate.vendorName(),
                    affiliate.vendorCode(),
                    affiliate.hubId());
        }

        private static String str(Object v) {
            return v == null ? null : String.valueOf(v);
        }
    }

    private Map<String, Object> deskLinkResponse(Map<String, Object> row, String empName, String empCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply_url", deskUrlForAffiliate(row));
        out.put("employee_name", empName);
        out.put("employee_code", empCode);
        if (row.get("gfin_code") != null) {
            out.put("employee_gfin_code", row.get("gfin_code"));
        }
        return out;
    }

    private String deskUrlForAffiliate(Map<String, Object> affiliate) {
        Object gfin = affiliate.get("gfin_code");
        if (gfin != null && !String.valueOf(gfin).isBlank()) {
            return deskUrlForGfin(String.valueOf(gfin));
        }
        return publicAppUrl + "/a/" + affiliate.get("apply_token");
    }

    private String deskUrlForGfin(String gfinCode) {
        return publicAppUrl + "/a/" + gfinCode.trim();
    }

    private static String normalizeDeskRef(String deskRef) {
        if (deskRef == null || deskRef.isBlank()) {
            throw ApiException.of(HttpStatus.BAD_REQUEST, "INVALID_DESK_REF", "Desk link reference is required");
        }
        return deskRef.trim();
    }

    private static String requiredPlace(String value, String code, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        }
        return trimmed;
    }

    private static String optionalPin(String pincode) {
        if (pincode == null || pincode.isBlank()) {
            return null;
        }
        String digits = pincode.replaceAll("\\D", "");
        if (digits.length() != 6) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PINCODE", "Enter a 6-digit pincode");
        }
        return digits;
    }

    private void scopeHubs(StringBuilder sql, List<Object> args) {
        List<UUID> hubs = access.hubIdsOrNull();
        if (hubs == null) {
            return;
        }
        if (hubs.isEmpty()) {
            sql.append(" AND 1=0 ");
            return;
        }
        sql.append(" AND v.hub_id IN (");
        for (int i = 0; i < hubs.size(); i++) {
            if (i > 0) {
                sql.append(",");
            }
            sql.append("?");
            args.add(hubs.get(i));
        }
        sql.append(") ");
    }

    private void assertHub(UUID hubId) {
        access.assertHub(hubId);
    }

    private void requireActiveHub(UUID hubId) {
        String status = jdbc.queryForObject("SELECT status FROM hubs WHERE id = ?", String.class, hubId);
        if (!"ACTIVE".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_NOT_ACTIVE", "Hub is not active");
        }
    }

    private static String normalizeMobile(String mobile) {
        String d = mobile.replaceAll("\\D", "");
        if (d.length() >= 10) {
            return d.substring(d.length() - 10);
        }
        return d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String payloadJson(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
