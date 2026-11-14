package com.fintech.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.AdminAccess;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PublicAppUrl;
import com.fintech.sms.SmsSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static final String SELECT = """
            SELECT i.id, i.purpose, i.role, i.full_name, i.mobile, i.email,
                   i.parent_id, i.hub_id, i.user_id, i.status, i.expires_at,
                   i.created_by, i.created_at, i.updated_at,
                   p.full_name AS parent_name, p.code AS parent_code,
                   h.name AS hub_name, h.code AS hub_code,
                   c.full_name AS created_by_name, c.user_type::text AS created_by_type,
                   j.id AS journey_id, j.current_step, j.status AS journey_status,
                   j.last_activity_at, j.started_at, j.completed_at,
                   j.failure_counts::text AS failure_counts_json,
                   j.identity::text AS identity_json
              FROM onboarding_invites i
              LEFT JOIN onboarding_journeys j ON j.invite_id = i.id
              LEFT JOIN users p ON p.id = i.parent_id
              LEFT JOIN hubs h ON h.id = i.hub_id
              LEFT JOIN users c ON c.id = i.created_by
            """;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;
    private final JourneyTracker journeyTracker;
    private final SmsSender smsSender;
    private final ObjectMapper mapper;
    private final String publicAppUrl;
    private final int inviteTtlDays;

    public InviteService(
            JdbcTemplate jdbc,
            AdminAccess access,
            JourneyTracker journeyTracker,
            SmsSender smsSender,
            ObjectMapper mapper,
            @Value("${app.public-app-url:http://localhost:3001}") String publicAppUrl,
            @Value("${app.onboarding.invite-ttl-days:7}") int inviteTtlDays) {
        this.jdbc = jdbc;
        this.access = access;
        this.journeyTracker = journeyTracker;
        this.smsSender = smsSender;
        this.mapper = mapper;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
        this.inviteTtlDays = inviteTtlDays;
    }

    @Transactional
    public Map<String, Object> createInvite(AuthPrincipal me, String roleRaw, String fullNameRaw,
                                            String mobileRaw, String emailRaw, UUID parentId, UUID hubId,
                                            String ip, String userAgent) {
        String role = normalizeRole(roleRaw);
        String fullName = validateName(fullNameRaw);
        String mobile = validateMobile(mobileRaw);
        String email = validateEmail(emailRaw);

        assertMobileAvailable(mobile);

        UUID resolvedParentId;
        UUID resolvedHubId;

        if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            if (!"RETAILER".equals(role)) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Distributors can only invite retailers under their own network");
            }
            Map<String, Object> self = loadUser(me.userId());
            if (!"ACTIVE".equals(String.valueOf(self.get("status")))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PARENT_NOT_ACTIVE",
                        "Your distributor account must be active to invite retailers");
            }
            resolvedHubId = (UUID) self.get("hub_id");
            if (resolvedHubId == null) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_REQUIRED",
                        "Your distributor account is not assigned to a hub");
            }
            resolvedParentId = me.userId();
        } else if (me.platformStaff()) {
            if ("MASTER_DISTRIBUTOR".equals(role)) {
                if (hubId == null) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_REQUIRED",
                            "Select a hub for this distributor");
                }
                access.assertHub(hubId);
                requireActiveHub(hubId);
                resolvedHubId = hubId;
                resolvedParentId = null;
            } else {
                if (parentId == null) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DISTRIBUTOR_REQUIRED",
                            "Select the distributor this retailer belongs to");
                }
                Map<String, Object> parent = loadUser(parentId);
                if (!"MASTER_DISTRIBUTOR".equals(String.valueOf(parent.get("user_type")))) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARENT",
                            "Retailers must be onboarded under a distributor");
                }
                if (!"ACTIVE".equals(String.valueOf(parent.get("status")))) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PARENT_NOT_ACTIVE",
                            "Cannot place a retailer under an inactive distributor");
                }
                resolvedHubId = (UUID) parent.get("hub_id");
                if (resolvedHubId == null) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_REQUIRED",
                            "This distributor is not assigned to a hub");
                }
                access.assertHub(resolvedHubId);
                resolvedParentId = parentId;
            }
        } else {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed to create onboarding invites");
        }

        String rawToken = newOpaqueToken();
        String tokenHash = sha256(rawToken);
        UUID inviteId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(Duration.ofDays(inviteTtlDays));

        try {
            jdbc.update("""
                    INSERT INTO onboarding_invites
                        (id, purpose, role, full_name, mobile, email, parent_id, hub_id,
                         token_hash, status, expires_at, created_by)
                    VALUES (?, 'ONBOARD', ?, ?, ?, ?, ?, ?, ?, 'SENT', ?, ?)
                    """,
                    inviteId, role, fullName, mobile, email, resolvedParentId, resolvedHubId,
                    tokenHash, Timestamp.from(expiresAt), me.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVITE_ALREADY_OPEN",
                    "An active onboarding invite already exists for this mobile number");
        }

        UUID journeyId = journeyTracker.createJourney(inviteId, null, "ONBOARD", JourneyTracker.LINK_SENT);
        String onboardingUrl = buildLink(rawToken);
        journeyTracker.record(
                journeyId, null, JourneyTracker.LINK_SENT, "SUCCESS",
                Map.of("role", role, "mobile", OtpService.maskMobile(mobile), "createdBy", me.userId().toString()),
                null, null, ip, userAgent, true);

        smsSender.send(mobile, "ONBOARD_INVITE", Map.of(
                "name", fullName,
                "role", role,
                "link", onboardingUrl
        ));

        log.info("INVITE_CREATED id={} role={} mobile={} by={}",
                inviteId, role, OtpService.maskMobile(mobile), me.userId());

        Map<String, Object> out = getInvite(me, inviteId);
        out.put("token", rawToken);
        out.put("onboardingUrl", onboardingUrl);
        return out;
    }

    public List<Map<String, Object>> listInvites(AuthPrincipal me, String status, String step, String role) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        appendScope(me, sql, args);

        if (status != null && !status.isBlank()) {
            sql.append(" AND i.status = ? ");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (step != null && !step.isBlank()) {
            sql.append(" AND j.current_step = ? ");
            args.add(step.trim().toUpperCase(Locale.ROOT));
        }
        if (role != null && !role.isBlank()) {
            sql.append(" AND i.role = ? ");
            args.add(role.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY COALESCE(j.last_activity_at, i.created_at) DESC");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(r -> hydrateRow(r, me))
                .toList();
    }

    public Map<String, Object> getInvite(AuthPrincipal me, UUID inviteId) {
        Map<String, Object> row = loadInviteRow(inviteId);
        assertCanView(me, row);
        Map<String, Object> out = hydrateRow(row, me);
        UUID journeyId = (UUID) row.get("journey_id");
        List<Map<String, Object>> events = journeyId == null ? List.of() : journeyTracker.eventsForJourney(journeyId);
        if (!me.platformStaff()) {
            events = events.stream().map(InviteService::sanitizeEventForNonAdmin).toList();
        }
        out.put("events", events);
        return out;
    }

    @Transactional
    public Map<String, Object> resendInvite(AuthPrincipal me, UUID inviteId, String ip, String userAgent) {
        Map<String, Object> row = loadInviteRow(inviteId);
        assertCanView(me, row);
        String status = String.valueOf(row.get("status"));
        if ("COMPLETED".equals(status) || "REVOKED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVITE_CLOSED",
                    "Cannot resend a completed or revoked invite");
        }
        String rawToken = newOpaqueToken();
        String tokenHash = sha256(rawToken);
        Instant expiresAt = Instant.now().plus(Duration.ofDays(inviteTtlDays));
        String nextStatus = "EXPIRED".equals(status) ? "SENT" : status;

        jdbc.update("""
                UPDATE onboarding_invites
                   SET token_hash = ?, session_hash = NULL, session_expires_at = NULL,
                       status = ?, expires_at = ?, updated_at = now()
                 WHERE id = ?
                """, tokenHash, nextStatus, Timestamp.from(expiresAt), inviteId);

        UUID journeyId = (UUID) row.get("journey_id");
        if (journeyId != null) {
            jdbc.update("""
                    UPDATE onboarding_journeys
                       SET status = CASE WHEN status IN ('EXPIRED', 'ABANDONED') THEN 'IN_PROGRESS' ELSE status END,
                           last_activity_at = now()
                     WHERE id = ?
                    """, journeyId);
            journeyTracker.record(
                    journeyId, (UUID) row.get("user_id"), JourneyTracker.LINK_SENT, "INFO",
                    Map.of("resent", true, "by", me.userId().toString()),
                    null, null, ip, userAgent, false);
        }

        String onboardingUrl = buildLink(rawToken);
        smsSender.send(String.valueOf(row.get("mobile")), "ONBOARD_INVITE_RESEND", Map.of(
                "name", String.valueOf(row.get("full_name")),
                "role", String.valueOf(row.get("role")),
                "link", onboardingUrl
        ));

        Map<String, Object> out = getInvite(me, inviteId);
        out.put("token", rawToken);
        out.put("onboardingUrl", onboardingUrl);
        return out;
    }

    @Transactional
    public Map<String, Object> revokeInvite(AuthPrincipal me, UUID inviteId, String ip, String userAgent) {
        Map<String, Object> row = loadInviteRow(inviteId);
        assertCanView(me, row);
        String status = String.valueOf(row.get("status"));
        if ("COMPLETED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVITE_COMPLETED",
                    "Cannot revoke an invite after the account has been created");
        }
        jdbc.update("""
                UPDATE onboarding_invites
                   SET status = 'REVOKED', session_hash = NULL, session_expires_at = NULL, updated_at = now()
                 WHERE id = ?
                """, inviteId);
        UUID journeyId = (UUID) row.get("journey_id");
        if (journeyId != null) {
            jdbc.update("UPDATE onboarding_journeys SET status = 'EXPIRED', last_activity_at = now() WHERE id = ?", journeyId);
            journeyTracker.record(
                    journeyId, (UUID) row.get("user_id"), "INVITE_REVOKED", "INFO",
                    Map.of("by", me.userId().toString()),
                    null, null, ip, userAgent, false);
        }
        return getInvite(me, inviteId);
    }

    @Transactional
    public Map<String, Object> reviewInvite(AuthPrincipal me, UUID inviteId, boolean approve, String note,
                                            String ip, String userAgent) {
        if (!me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only an admin can review flagged onboarding journeys");
        }
        Map<String, Object> row = loadInviteRow(inviteId);
        assertCanView(me, row);
        UUID journeyId = (UUID) row.get("journey_id");
        if (journeyId == null) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "JOURNEY_NOT_FOUND", "Onboarding journey not found");
        }
        String cleanNote = note == null ? "" : note.trim();
        if (approve) {
            Map<String, Object> identity = journeyTracker.getIdentity(journeyId);
            String stepToAdvance = null;
            if (Boolean.TRUE.equals(identity.get("panPendingReview"))) {
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put("panPendingReview", false);
                patch.put("panVerified", true);
                patch.put("reviewApprovedBy", me.userId().toString());
                patch.put("reviewNote", cleanNote);
                journeyTracker.mergeIdentity(journeyId, patch);
                stepToAdvance = JourneyTracker.PAN_VERIFIED;
            } else if (Boolean.TRUE.equals(identity.get("aadhaarPendingReview"))) {
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put("aadhaarPendingReview", false);
                patch.put("aadhaarVerified", true);
                patch.put("reviewApprovedBy", me.userId().toString());
                patch.put("reviewNote", cleanNote);
                journeyTracker.mergeIdentity(journeyId, patch);
                stepToAdvance = JourneyTracker.AADHAAR_VERIFIED;
            }
            jdbc.update("""
                    UPDATE onboarding_journeys
                       SET status = 'IN_PROGRESS',
                           failure_counts = '{}'::jsonb,
                           current_step = COALESCE(?, current_step),
                           last_activity_at = now()
                     WHERE id = ?
                    """, stepToAdvance, journeyId);
            jdbc.update("""
                    UPDATE onboarding_invites
                       SET status = 'IN_PROGRESS', updated_at = now()
                     WHERE id = ?
                    """, inviteId);
            journeyTracker.record(
                    journeyId, (UUID) row.get("user_id"),
                    stepToAdvance != null ? stepToAdvance : "ADMIN_REVIEW_APPROVED",
                    "SUCCESS",
                    Map.of("approvedBy", me.userId().toString(), "note", cleanNote),
                    null, null, ip, userAgent, stepToAdvance != null);
        } else {
            jdbc.update("""
                    UPDATE onboarding_invites
                       SET status = 'REVOKED', session_hash = NULL, session_expires_at = NULL, updated_at = now()
                     WHERE id = ?
                    """, inviteId);
            jdbc.update("UPDATE onboarding_journeys SET status = 'EXPIRED', last_activity_at = now() WHERE id = ?", journeyId);
            journeyTracker.record(
                    journeyId, (UUID) row.get("user_id"), "ADMIN_REVIEW_REJECTED", "FAILURE",
                    Map.of("rejectedBy", me.userId().toString(), "note", cleanNote),
                    null, null, ip, userAgent, false);
        }
        return getInvite(me, inviteId);
    }

    private void appendScope(AuthPrincipal me, StringBuilder sql, List<Object> args) {
        if (me.superAdminOrPlatformAdmin()) {
            return;
        }
        if (me.hubAdmin()) {
            access.appendHubFilter(sql, args, "i.hub_id");
            return;
        }
        if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            sql.append(" AND i.role = 'RETAILER' AND (i.parent_id = ? OR i.created_by = ?) ");
            args.add(me.userId());
            args.add(me.userId());
            return;
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
    }

    private void assertCanView(AuthPrincipal me, Map<String, Object> row) {
        if (me.superAdminOrPlatformAdmin()) {
            return;
        }
        if (me.hubAdmin()) {
            UUID hubId = (UUID) row.get("hub_id");
            if (hubId != null) {
                access.assertHub(hubId);
                return;
            }
        }
        if ("MASTER_DISTRIBUTOR".equals(me.userType())) {
            if ("RETAILER".equals(String.valueOf(row.get("role")))
                    && (me.userId().equals(row.get("parent_id")) || me.userId().equals(row.get("created_by")))) {
                return;
            }
        }
        throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "This invite is outside your network");
    }

    private Map<String, Object> loadInviteRow(UUID id) {
        try {
            return jdbc.queryForMap(SELECT + " WHERE i.id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "Onboarding invite not found");
        }
    }

    private Map<String, Object> hydrateRow(Map<String, Object> row, AuthPrincipal me) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        Object fcRaw = out.remove("failure_counts_json");
        Object idRaw = out.remove("identity_json");
        if (me != null && me.platformStaff()) {
            out.put("failure_counts", parseJson(fcRaw));
            Map<String, Object> identity = new LinkedHashMap<>(PublicOnboardingService.sanitizeIdentity(parseJson(idRaw)));
            out.put("identity", identity);
        } else {
            out.put("failure_counts", Map.of());
            out.put("identity", Map.of());
        }
        return out;
    }

    private static Map<String, Object> sanitizeEventForNonAdmin(Map<String, Object> event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", event.get("id"));
        out.put("step", event.get("step"));
        out.put("outcome", event.get("outcome"));
        out.put("created_at", event.get("created_at"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(String.valueOf(raw), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void assertMobileAvailable(String mobile) {
        List<Map<String, Object>> existingUser = jdbc.queryForList(
                "SELECT 1 FROM users WHERE mobile = ? LIMIT 1", mobile);
        if (!existingUser.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MOBILE_ALREADY_REGISTERED",
                    "A user with this mobile number already exists");
        }
        List<Map<String, Object>> openInvite = jdbc.queryForList("""
                SELECT id FROM onboarding_invites
                 WHERE mobile = ? AND status IN ('SENT', 'IN_PROGRESS', 'NEEDS_REVIEW')
                 LIMIT 1
                """, mobile);
        if (!openInvite.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVITE_ALREADY_OPEN",
                    "An active onboarding invite already exists for this mobile number");
        }
    }

    private Map<String, Object> loadUser(UUID userId) {
        try {
            return jdbc.queryForMap(
                    "SELECT id, user_type::text AS user_type, status::text AS status, hub_id FROM users WHERE id = ?",
                    userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
    }

    private void requireActiveHub(UUID hubId) {
        try {
            String status = jdbc.queryForObject("SELECT status FROM hubs WHERE id = ?", String.class, hubId);
            if (!"ACTIVE".equals(status)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "HUB_NOT_ACTIVE", "Hub is not active");
            }
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "HUB_NOT_FOUND", "Hub does not exist");
        }
    }

    private String buildLink(String token) {
        String base = publicAppUrl == null || publicAppUrl.isBlank() ? "http://localhost:3001" : publicAppUrl;
        return base + "/onboard/" + token;
    }

    private static String normalizeRole(String raw) {
        String role = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("DISTRIBUTOR".equals(role)) {
            role = "MASTER_DISTRIBUTOR";
        }
        if (!"RETAILER".equals(role) && !"MASTER_DISTRIBUTOR".equals(role)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ROLE",
                    "Role must be RETAILER or MASTER_DISTRIBUTOR");
        }
        return role;
    }

    private static String validateName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.length() < 2) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NAME", "Enter the partner's full name");
        }
        return name;
    }

    private static String validateMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE", "Enter a 10-digit mobile number");
        }
        return digits;
    }

    private static String validateEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL", "Enter a valid email address");
        }
        return email;
    }

    public static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return B64.encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

