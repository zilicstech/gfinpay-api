package com.fintech.identity;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AdminAccess {

    private final JdbcTemplate jdbc;

    public AdminAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AuthPrincipal me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        return principal;
    }

    public boolean superAdmin() {
        return me().superAdmin();
    }

    public void requireSuperAdmin() {
        if (!superAdmin()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a super admin can do this");
        }
    }

    public void requirePlatformStaff() {
        if (!me().platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
    }

    /** null = unrestricted (super admin); otherwise assigned hub ids. */
    public List<UUID> hubIdsOrNull() {
        if (superAdmin()) {
            return null;
        }
        if (!me().hubAdmin()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        return jdbc.queryForList(
                "SELECT hub_id FROM hub_admin_assignments WHERE user_id = ?",
                UUID.class, me().userId());
    }

    public void assertHub(UUID hubId) {
        List<UUID> hubs = hubIdsOrNull();
        if (hubs == null) {
            return;
        }
        if (hubId == null || !hubs.contains(hubId)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This hub is outside your assignment");
        }
    }

    public void assertNetworkUser(UUID userId) {
        if (userId != null && userId.equals(me().userId())) {
            return;
        }
        if (superAdmin()) {
            return;
        }
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT user_type::text AS user_type, hub_id, parent_id FROM users WHERE id = ?", userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        String type = String.valueOf(row.get("user_type"));
        UUID hubId = (UUID) row.get("hub_id");
        UUID parentId = (UUID) row.get("parent_id");
        if ("SUPER_ADMIN".equals(type) || "ADMIN".equals(type)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        if ("MASTER_DISTRIBUTOR".equals(me().userType())) {
            if (parentId != null && parentId.equals(me().userId())) {
                return;
            }
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This user is outside your network");
        }
        List<UUID> hubs = hubIdsOrNull();
        if (hubs != null && hubId != null && hubs.contains(hubId)) {
            return;
        }
        if (hubs != null && parentId != null) {
            UUID parentHub = jdbc.queryForObject("SELECT hub_id FROM users WHERE id = ?", UUID.class, parentId);
            if (parentHub != null && hubs.contains(parentHub)) {
                return;
            }
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This user is outside your hubs");
    }

    public void appendHubFilter(StringBuilder sql, List<Object> args, String column) {
        List<UUID> hubs = hubIdsOrNull();
        if (hubs == null) {
            return;
        }
        if (hubs.isEmpty()) {
            sql.append(" AND FALSE ");
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        for (int i = 0; i < hubs.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            args.add(hubs.get(i));
        }
        sql.append(") ");
    }

    public List<Object> hubArgs() {
        List<UUID> hubs = hubIdsOrNull();
        if (hubs == null) {
            return List.of();
        }
        return new ArrayList<>(hubs);
    }
}
