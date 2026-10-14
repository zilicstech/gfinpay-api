package com.fintech.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
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
            Resolved actor = resolve(event);
            if (actor.ignored) {
                return;
            }
            Map<String, Object> detail = event.detail() == null
                    ? new java.util.LinkedHashMap<>()
                    : event.detail();
            attachSubject(event, detail);
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
                    event.subjectType(),
                    event.subjectId(),
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

    private Resolved resolve(ActivityEvent event) {
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
        if (!ActivityPhrases.isLogin(event.action())) {
            return Resolved.none();
        }
        String identifier = text(event.detail().get("identifier"));
        if (identifier.isBlank()) {
            return Resolved.keep(null, null, null);
        }
        Actor row = loadByIdentifier(identifier);
        if (row == null) {
            return Resolved.keep(null, null, null);
        }
        if (!ACTOR_TYPES.contains(row.userType)) {
            return Resolved.none();
        }
        return Resolved.keep(row.userId, row.userType, row.code);
    }

    private void attachSubject(ActivityEvent event, Map<String, Object> detail) {
        if (text(detail.get("code")).length() > 0) {
            return;
        }
        String id = event.subjectId();
        if (id == null || id.isBlank()) {
            return;
        }
        UUID userId;
        try {
            userId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
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
        boolean byCode = key.toUpperCase(java.util.Locale.ROOT).startsWith("GFIN");
        String sql = byCode
                ? "SELECT id, user_type::text AS user_type, code FROM users WHERE upper(code) = ?"
                : "SELECT id, user_type::text AS user_type, code FROM users WHERE mobile = ?";
        try {
            Map<String, Object> row = jdbc.queryForMap(sql, byCode ? key.toUpperCase(java.util.Locale.ROOT) : key);
            return actor(row);
        } catch (Exception e) {
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

    private record Resolved(boolean ignored, UUID userId, String userType, String code) {
        static Resolved none() {
            return new Resolved(true, null, null, null);
        }

        static Resolved keep(UUID userId, String userType, String code) {
            return new Resolved(false, userId, userType, code);
        }
    }
}
