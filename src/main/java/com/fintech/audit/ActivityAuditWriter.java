package com.fintech.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(ActivityAuditWriter.class);

    static final Set<String> ACTOR_TYPES = Set.of(
            "SUPER_ADMIN", "ADMIN", "HUBADMIN", "MASTER_DISTRIBUTOR", "RETAILER", "AUDITOR");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ActivityAuditWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Async("activityAuditExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(ActivityEvent event) {
        try {
            Map<String, Object> detail = event.detail() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(event.detail());
            String inviteId = text(detail.remove("_inviteId"));
            String partnerUserId = text(detail.remove("_partnerUserId"));
            String resolvedActorId = text(detail.remove("_resolvedActorId"));

            Resolved actor = resolve(event, detail, inviteId, partnerUserId, resolvedActorId);
            if (actor.ignored) {
                return;
            }
            SubjectRef subject = resolveSubjectAndEnrich(event, actor, detail, inviteId, partnerUserId);
            jdbc.update("""
                    INSERT INTO activity_events (
                        id, occurred_at, actor_user_id, actor_user_type, actor_code,
                        action, subject_type, subject_id, outcome, http_status,
                        http_method, http_path, detail, ip, user_agent)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """,
                    UUID.randomUUID(),
                    Timestamp.from(event.occurredAt()),
                    actor.userId,
                    actor.userType,
                    actor.code,
                    event.action(),
                    subject.subjectType,
                    subject.subjectId,
                    event.outcome(),
                    event.httpStatus(),
                    event.httpMethod(),
                    event.httpPath(),
                    json(detail),
                    event.ip(),
                    clip(event.userAgent(), 300));
        } catch (Exception e) {
            log.warn("ACTIVITY_AUDIT insert failed action={} path={} reason={}",
                    event.action(), event.httpPath(), e.getMessage());
        }
    }

    private Resolved resolve(
            ActivityEvent event,
            Map<String, Object> detail,
            String inviteId,
            String partnerUserId,
            String resolvedActorId) {
        if (event.actorUserId() != null) {
            Actor row = loadById(event.actorUserId());
            if (row == null) {
                return Resolved.keep(event.actorUserId(), event.actorUserType(), null);
            }
            if (!ACTOR_TYPES.contains(row.userType)) {
                return Resolved.none();
            }
            return Resolved.keep(row.userId, row.userType, row.code);
        }

        String path = event.httpPath() == null ? "" : event.httpPath();

        // 1. Unauthenticated Auth / OTP / Password Reset routes (same pattern as User Login)
        if (ActivityPhrases.isUnauthenticatedAuthRoute(event.httpMethod(), path)
                || ActivityPhrases.isLogin(event.action())) {
            Actor row = null;
            UUID actorUuid = parseUuid(resolvedActorId);
            if (actorUuid != null) {
                row = loadById(actorUuid);
            }
            if (row == null) {
                String identifier = text(detail.get("identifier"));
                if (!identifier.isBlank()) {
                    row = loadByIdentifier(identifier);
                }
            }
            if (row == null) {
                return Resolved.keep(null, null, null);
            }
            if (!ACTOR_TYPES.contains(row.userType)) {
                return Resolved.none();
            }
            return Resolved.keep(row.userId, row.userType, row.code);
        }

        // 2. Self-service KYC Onboarding (/api/v1/public/onboarding/*)
        if (path.startsWith("/api/v1/public/onboarding/")) {
            UUID invUuid = parseUuid(inviteId);
            if (invUuid == null) {
                return Resolved.none();
            }
            try {
                Map<String, Object> inv = jdbc.queryForMap(
                        "SELECT user_id, role, full_name FROM onboarding_invites WHERE id = ?", invUuid);
                UUID createdUserId = (UUID) inv.get("user_id");
                String role = text(inv.get("role"));
                if (createdUserId != null) {
                    Actor created = loadById(createdUserId);
                    if (created != null && ACTOR_TYPES.contains(created.userType)) {
                        return Resolved.keep(created.userId, created.userType, created.code);
                    }
                }
                return Resolved.keep(null, ACTOR_TYPES.contains(role) ? role : null, null);
            } catch (Exception e) {
                return Resolved.none();
            }
        }

        // 3. Company Authorized Signatory KYC (/api/v1/public/signatory/*)
        if (path.startsWith("/api/v1/public/signatory/")) {
            if (parseUuid(partnerUserId) == null) {
                return Resolved.none();
            }
            return Resolved.keep(null, null, null);
        }

        return Resolved.none();
    }

    private SubjectRef resolveSubjectAndEnrich(
            ActivityEvent event,
            Resolved actor,
            Map<String, Object> detail,
            String inviteId,
            String partnerUserId) {
        String subjectType = event.subjectType();
        String subjectId = event.subjectId();
        String path = event.httpPath() == null ? "" : event.httpPath();

        if (!partnerUserId.isBlank() && parseUuid(partnerUserId) != null) {
            subjectType = "users";
            subjectId = partnerUserId;
        } else if (!inviteId.isBlank() && parseUuid(inviteId) != null) {
            if (actor.userId != null) {
                subjectType = "users";
                subjectId = actor.userId.toString();
            } else {
                subjectType = "onboarding_invite";
                subjectId = inviteId;
            }
        } else if ("agreements".equals(subjectType) && parseUuid(subjectId) != null) {
            try {
                Map<String, Object> ag = jdbc.queryForMap(
                        "SELECT user_id, agreement_no FROM partner_agreements WHERE id = ?",
                        UUID.fromString(subjectId));
                UUID uid = (UUID) ag.get("user_id");
                if (uid != null) {
                    subjectType = "users";
                    subjectId = uid.toString();
                }
                String agNo = text(ag.get("agreement_no"));
                if (!agNo.isBlank()) {
                    detail.put("agreementNo", agNo);
                }
            } catch (Exception ignored) {
                // Keep original subject if lookup misses.
            }
        } else if ("invites".equals(subjectType) && parseUuid(subjectId) != null) {
            try {
                Map<String, Object> inv = jdbc.queryForMap(
                        "SELECT user_id, full_name, role FROM onboarding_invites WHERE id = ?",
                        UUID.fromString(subjectId));
                String fullName = text(inv.get("full_name"));
                if (!fullName.isBlank() && text(detail.get("name")).isBlank()) {
                    detail.put("name", fullName);
                }
                String role = ActivityPhrases.role(text(inv.get("role")));
                if (!role.isBlank() && text(detail.get("role")).isBlank()) {
                    detail.put("role", role);
                }
                UUID uid = (UUID) inv.get("user_id");
                if (uid != null) {
                    subjectType = "users";
                    subjectId = uid.toString();
                }
            } catch (Exception ignored) {
                // Keep original invite subject.
            }
        } else if ("requests".equals(subjectType) && parseUuid(subjectId) != null) {
            try {
                Map<String, Object> req = jdbc.queryForMap(
                        "SELECT retailer_user_id, amount, utr FROM finance_requests WHERE id = ?",
                        UUID.fromString(subjectId));
                UUID uid = (UUID) req.get("retailer_user_id");
                if (uid != null) {
                    subjectType = "users";
                    subjectId = uid.toString();
                }
                if (detail.get("amount") == null && req.get("amount") != null) {
                    detail.put("amount", req.get("amount"));
                }
                String utr = text(req.get("utr"));
                if (!utr.isBlank() && text(detail.get("utr")).isBlank()) {
                    detail.put("utr", utr);
                }
            } catch (Exception ignored) {
                // Keep original finance request subject.
            }
        }

        // Self-service agreement actions (/api/v1/agreements/me/*): attach agreementNo
        if (path.startsWith("/api/v1/agreements/me/") && actor.userId != null && text(detail.get("agreementNo")).isBlank()) {
            try {
                String agNo = jdbc.queryForObject("""
                        SELECT agreement_no FROM partner_agreements
                         WHERE user_id = ? AND status <> 'VOID'
                         ORDER BY created_at DESC LIMIT 1
                        """, String.class, actor.userId);
                if (agNo != null && !agNo.isBlank()) {
                    detail.put("agreementNo", agNo.trim());
                }
            } catch (Exception ignored) {
                // Optional enrichment.
            }
        }

        // DigiLocker document review: attach human-readable document name
        if (path.contains("/digilocker-documents/") && path.endsWith("/review")) {
            UUID docId = ActivityActions.uuidAfterSegment(path, "digilocker-documents");
            if (docId != null) {
                try {
                    Map<String, Object> doc = jdbc.queryForMap(
                            "SELECT name, doc_type FROM digilocker_documents WHERE id = ?", docId);
                    String docName = text(doc.get("name"));
                    if (docName.isBlank()) {
                        docName = text(doc.get("doc_type"));
                    }
                    if (!docName.isBlank()) {
                        detail.put("documentName", docName);
                    }
                } catch (Exception ignored) {
                    // Optional enrichment.
                }
            }
        }

        // Retailer bank account actions: attach bank name and masked last-4 account digits
        if (path.contains("/banks/")) {
            UUID bankId = ActivityActions.uuidAfterSegment(path, "banks");
            if (bankId != null && text(detail.get("bankName")).isBlank()) {
                try {
                    Map<String, Object> bank = jdbc.queryForMap(
                            "SELECT bank_name, account_number FROM retailer_bank_accounts WHERE id = ?", bankId);
                    String bankName = text(bank.get("bank_name"));
                    if (!bankName.isBlank()) {
                        detail.put("bankName", bankName);
                    }
                    String acc = text(bank.get("account_number"));
                    if (acc.length() >= 4) {
                        detail.put("accountMasked", "••••" + acc.substring(acc.length() - 4));
                    }
                } catch (Exception ignored) {
                    // Platform bank or deleted bank; skip enrichment.
                }
            }
        }

        // Admin provider onboarding / sync / refresh: attach provider code
        if (path.contains("/providers/")) {
            String provSeg = ActivityActions.segmentAfter(path, "providers");
            if (provSeg != null
                    && !"channels".equalsIgnoreCase(provSeg)
                    && !"routing".equalsIgnoreCase(provSeg)
                    && text(detail.get("providerCode")).isBlank()) {
                detail.put("providerCode", provSeg.toUpperCase(Locale.ROOT));
            }
        }

        // Normalize fullName -> name for invite creation payloads
        if (text(detail.get("name")).isBlank() && !text(detail.get("fullName")).isBlank()) {
            detail.put("name", text(detail.get("fullName")));
        }

        attachSubjectUser(subjectId, detail);
        return new SubjectRef(subjectType, subjectId);
    }

    private void attachSubjectUser(String subjectId, Map<String, Object> detail) {
        if (!text(detail.get("code")).isEmpty()) {
            return;
        }
        UUID userId = parseUuid(subjectId);
        if (userId == null) {
            return;
        }
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT code, full_name, user_type::text AS user_type
                      FROM users WHERE id = ?
                    """, userId);
            String code = text(row.get("code"));
            String name = text(row.get("full_name"));
            if (!code.isBlank()) {
                detail.put("code", code);
            }
            if (!name.isBlank() && text(detail.get("name")).isBlank()) {
                detail.put("name", name);
            }
            if (text(detail.get("role")).isBlank()) {
                String role = ActivityPhrases.role(text(row.get("user_type")));
                if (!role.isBlank()) {
                    detail.put("role", role);
                }
            }
        } catch (Exception ignored) {
            // The subject is not always a user. The action is still stored.
        }
    }

    private Actor loadById(UUID id) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT id, user_type::text AS user_type, code FROM users WHERE id = ?", id);
            return actor(row);
        } catch (Exception e) {
            return null;
        }
    }

    private Actor loadByIdentifier(String identifier) {
        String key = identifier.trim();
        try {
            if (key.toUpperCase(Locale.ROOT).startsWith("GFIN")) {
                Map<String, Object> row = jdbc.queryForMap(
                        "SELECT id, user_type::text AS user_type, code FROM users WHERE upper(code) = ?",
                        key.toUpperCase(Locale.ROOT));
                return actor(row);
            }
            if (key.contains("@")) {
                Map<String, Object> row = jdbc.queryForMap(
                        "SELECT id, user_type::text AS user_type, code FROM users WHERE lower(email) = ?",
                        key.toLowerCase(Locale.ROOT));
                return actor(row);
            }
            String digits = key.replaceAll("\\D", "");
            if (digits.length() > 10 && digits.startsWith("91")) {
                digits = digits.substring(digits.length() - 10);
            }
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT id, user_type::text AS user_type, code FROM users WHERE mobile = ?",
                    digits.isEmpty() ? key : digits);
            return actor(row);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Actor actor(Map<String, Object> row) {
        return new Actor((UUID) row.get("id"), String.valueOf(row.get("user_type")), text(row.get("code")));
    }

    private String json(Map<String, Object> detail) throws JsonProcessingException {
        return mapper.writeValueAsString(detail == null ? Map.of() : detail);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private record Actor(UUID userId, String userType, String code) {}

    private record SubjectRef(String subjectType, String subjectId) {}

    private record Resolved(boolean ignored, UUID userId, String userType, String code) {
        static Resolved none() {
            return new Resolved(true, null, null, null);
        }

        static Resolved keep(UUID userId, String userType, String code) {
            return new Resolved(false, userId, userType, code);
        }
    }
}
