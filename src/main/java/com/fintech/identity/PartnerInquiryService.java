package com.fintech.identity;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public partner enquiries and the path from enquiry to distributor or retailer. */
@Service
public class PartnerInquiryService {

    private static final String SELECT = """
            SELECT i.id, i.full_name, i.mobile, i.email, i.city, i.message, i.status,
                   i.hub_id, h.name AS hub_name,
                   i.assigned_hub_admin, ha.full_name AS hub_admin_name,
                   i.assigned_distributor, d.full_name AS distributor_name, d.code AS distributor_code,
                   i.converted_user_id, i.converted_role,
                   cu.full_name AS converted_name, cu.code AS converted_code,
                   i.created_at, i.updated_at
              FROM partner_inquiries i
              LEFT JOIN hubs h ON h.id = i.hub_id
              LEFT JOIN users ha ON ha.id = i.assigned_hub_admin
              LEFT JOIN users d ON d.id = i.assigned_distributor
              LEFT JOIN users cu ON cu.id = i.converted_user_id
            """;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;
    private final AdminPlatformService admin;

    public PartnerInquiryService(JdbcTemplate jdbc, AdminAccess access, AdminPlatformService admin) {
        this.jdbc = jdbc;
        this.access = access;
        this.admin = admin;
    }

    @Transactional
    public Map<String, Object> submit(String fullName, String mobile, String email, String city, String message) {
        String mail = email.trim().toLowerCase();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO partner_inquiries (id, full_name, mobile, email, city, message)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, fullName.trim(), digits(mobile), mail, city.trim(), blankToNull(message));
        return getRow(id);
    }

    public List<Map<String, Object>> listForStaff() {
        AuthPrincipal me = access.me();
        if (me.superAdminOrPlatformAdmin()) {
            return jdbc.queryForList(SELECT + " ORDER BY i.created_at DESC");
        }
        if (!me.hubAdmin()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        return jdbc.queryForList(SELECT + " WHERE i.assigned_hub_admin = ? ORDER BY i.created_at DESC", me.userId());
    }

    public Map<String, Object> getForStaff(UUID id) {
        Map<String, Object> row = getRow(id);
        assertStaffCanSee(row);
        return row;
    }

    @Transactional
    public Map<String, Object> retire(UUID id) {
        access.requireSuperAdminOrPlatformAdmin();
        requireActionable(id);
        jdbc.update("""
                UPDATE partner_inquiries SET status = 'RETIRED', updated_at = now() WHERE id = ?
                """, id);
        return getRow(id);
    }

    @Transactional
    public Map<String, Object> notInterested(UUID id) {
        access.requireSuperAdminOrPlatformAdmin();
        requireActionable(id);
        jdbc.update("""
                UPDATE partner_inquiries SET status = 'NOT_INTERESTED', updated_at = now() WHERE id = ?
                """, id);
        return getRow(id);
    }

    @Transactional
    public Map<String, Object> assignHubAdmin(UUID id, UUID hubAdminId) {
        access.requireSuperAdminOrPlatformAdmin();
        requireActionable(id);
        Map<String, Object> hubAdmin = loadUser(hubAdminId, "HUB_ADMIN_NOT_FOUND", "Hub admin does not exist");
        if (!"HUBADMIN".equals(hubAdmin.get("user_type"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_A_HUB_ADMIN", "Choose a hub admin");
        }
        UUID hubId = (UUID) hubAdmin.get("hub_id");
        if (hubId == null) {
            try {
                hubId = jdbc.queryForObject("""
                        SELECT hub_id FROM hub_admin_assignments WHERE user_id = ? ORDER BY assigned_at LIMIT 1
                        """, UUID.class, hubAdminId);
            } catch (EmptyResultDataAccessException ignored) {
                hubId = null;
            }
        }
        if (hubId == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_REQUIRED",
                    "This hub admin is not assigned to a hub");
        }
        jdbc.update("""
                UPDATE partner_inquiries
                   SET status = 'WITH_HUB',
                       assigned_hub_admin = ?,
                       hub_id = ?,
                       assigned_distributor = NULL,
                       updated_at = now()
                 WHERE id = ?
                """, hubAdminId, hubId, id);
        return getRow(id);
    }

    @Transactional
    public Map<String, Object> assignDistributor(UUID id, UUID distributorId) {
        Map<String, Object> row = requireActionable(id);
        assertHubSide(row);
        if (!"WITH_HUB".equals(row.get("status")) && !"WITH_DISTRIBUTOR".equals(row.get("status"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INQUIRY_NOT_WITH_HUB",
                    "Assign this inquiry to a hub admin before a distributor");
        }
        Map<String, Object> distributor = loadUser(distributorId, "DISTRIBUTOR_NOT_FOUND", "Distributor does not exist");
        if (!"MASTER_DISTRIBUTOR".equals(distributor.get("user_type"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_A_DISTRIBUTOR", "Choose a distributor");
        }
        if (!"ACTIVE".equals(distributor.get("status"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DISTRIBUTOR_NOT_ACTIVE",
                    "Choose an active distributor");
        }
        UUID inquiryHub = (UUID) row.get("hub_id");
        UUID distributorHub = (UUID) distributor.get("hub_id");
        if (inquiryHub != null && !inquiryHub.equals(distributorHub)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_MISMATCH",
                    "This distributor is not in the inquiry hub");
        }
        access.assertNetworkUser(distributorId);
        jdbc.update("""
                UPDATE partner_inquiries
                   SET status = 'WITH_DISTRIBUTOR',
                       assigned_distributor = ?,
                       hub_id = COALESCE(hub_id, ?),
                       updated_at = now()
                 WHERE id = ?
                """, distributorId, distributorHub, id);
        return getRow(id);
    }

    @Transactional
    public Map<String, Object> convertDistributor(UUID id, String password, UUID hubId) {
        Map<String, Object> row = requireActionable(id);
        assertHubSide(row);
        String status = String.valueOf(row.get("status"));
        if ("WITH_DISTRIBUTOR".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ALREADY_WITH_DISTRIBUTOR",
                    "This inquiry is with a distributor. They can create a retailer from it.");
        }
        Map<String, Object> created = admin.createDistributor(
                text(row.get("full_name")),
                text(row.get("mobile")),
                text(row.get("email")),
                password,
                hubId,
                access.me().userId());
        markConverted(id, (UUID) created.get("id"), "MASTER_DISTRIBUTOR");
        return getRow(id);
    }

    public List<Map<String, Object>> listForDistributor(AuthPrincipal me) {
        requireDistributor(me);
        return jdbc.queryForList(SELECT + " WHERE i.assigned_distributor = ? ORDER BY i.created_at DESC", me.userId());
    }

    public Map<String, Object> getForDistributor(AuthPrincipal me, UUID id) {
        requireDistributor(me);
        Map<String, Object> row = getRow(id);
        if (!me.userId().equals(row.get("assigned_distributor"))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This inquiry is not assigned to you");
        }
        return row;
    }

    @Transactional
    public Map<String, Object> convertRetailer(AuthPrincipal me, UUID id, String password,
                                               String city, String state, String pincode) {
        requireDistributor(me);
        Map<String, Object> row = getRow(id);
        if (!me.userId().equals(row.get("assigned_distributor"))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This inquiry is not assigned to you");
        }
        if (!"WITH_DISTRIBUTOR".equals(row.get("status"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INQUIRY_CLOSED",
                    "This inquiry can no longer be converted");
        }
        Map<String, Object> created = admin.createRetailer(
                text(row.get("full_name")),
                text(row.get("mobile")),
                text(row.get("email")),
                password,
                me.userId(),
                city,
                state,
                pincode,
                me.userId());
        markConverted(id, (UUID) created.get("id"), "RETAILER");
        return getRow(id);
    }

    private void markConverted(UUID id, UUID userId, String role) {
        jdbc.update("""
                UPDATE partner_inquiries
                   SET status = 'CONVERTED',
                       converted_user_id = ?,
                       converted_role = ?,
                       updated_at = now()
                 WHERE id = ?
                """, userId, role, id);
    }

    private Map<String, Object> requireActionable(UUID id) {
        Map<String, Object> row = getRow(id);
        String status = String.valueOf(row.get("status"));
        if ("RETIRED".equals(status) || "NOT_INTERESTED".equals(status) || "CONVERTED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INQUIRY_CLOSED",
                    "This inquiry is already closed");
        }
        return row;
    }

    private void assertStaffCanSee(Map<String, Object> row) {
        AuthPrincipal me = access.me();
        if (me.superAdminOrPlatformAdmin()) {
            return;
        }
        if (me.hubAdmin() && me.userId().equals(row.get("assigned_hub_admin"))) {
            return;
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This inquiry is not assigned to you");
    }

    private void assertHubSide(Map<String, Object> row) {
        AuthPrincipal me = access.me();
        if (me.superAdminOrPlatformAdmin()) {
            return;
        }
        if (me.hubAdmin() && me.userId().equals(row.get("assigned_hub_admin"))) {
            return;
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This inquiry is not assigned to you");
    }

    private Map<String, Object> getRow(UUID id) {
        try {
            return jdbc.queryForMap(SELECT + " WHERE i.id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry does not exist");
        }
    }

    private Map<String, Object> loadUser(UUID id, String code, String message) {
        try {
            return jdbc.queryForMap("""
                    SELECT id, user_type::text AS user_type, status::text AS status, hub_id
                      FROM users WHERE id = ?
                    """, id);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, code, message);
        }
    }

    private static void requireDistributor(AuthPrincipal me) {
        if (!"MASTER_DISTRIBUTOR".equals(me.userType())) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a distributor can do this");
        }
    }

    private static String digits(String mobile) {
        String value = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (value.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE", "Enter a 10-digit mobile");
        }
        return value;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
