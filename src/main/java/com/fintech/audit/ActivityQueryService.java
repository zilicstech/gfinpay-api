package com.fintech.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.AdminAccess;
import com.fintech.platform.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ActivityQueryService {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static final String COLUMNS = """
            SELECT e.id, e.occurred_at,
                   COALESCE(e.actor_user_id, oi_actor.id) AS actor_user_id,
                   COALESCE(e.actor_user_type, oi_actor.user_type::text) AS actor_user_type,
                   COALESCE(e.actor_code, oi_actor.code) AS actor_code,
                   e.action, e.subject_type, e.subject_id, e.outcome, e.http_status,
                   e.http_method, e.http_path, e.detail::text AS detail,
                   COALESCE(subject_user.code, pa_user.code, fr_user.code, oi_subject.code) AS subject_code,
                   COALESCE(subject_user.full_name, pa_user.full_name, fr_user.full_name, oi_subject.full_name, oi.full_name) AS subject_name,
                   COALESCE(subject_user.user_type::text, pa_user.user_type::text, fr_user.user_type::text, oi_subject.user_type::text, oi.role) AS subject_user_type,
                   pa.agreement_no AS resolved_agreement_no
              FROM activity_events e
              LEFT JOIN users subject_user
                     ON subject_user.id::text = e.subject_id
              LEFT JOIN onboarding_invites oi
                     ON e.subject_type IN ('onboarding_invite', 'invites')
                    AND oi.id::text = e.subject_id
              LEFT JOIN users oi_actor
                     ON e.subject_type = 'onboarding_invite'
                    AND e.actor_user_id IS NULL
                    AND oi_actor.id = oi.user_id
              LEFT JOIN users oi_subject
                     ON oi_subject.id = oi.user_id
              LEFT JOIN partner_agreements pa
                     ON e.subject_type = 'agreements'
                    AND pa.id::text = e.subject_id
              LEFT JOIN users pa_user
                     ON pa_user.id = pa.user_id
              LEFT JOIN finance_requests fr
                     ON e.subject_type = 'requests'
                    AND fr.id::text = e.subject_id
              LEFT JOIN users fr_user
                     ON fr_user.id = fr.retailer_user_id
            """;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;
    private final ObjectMapper mapper;

    public ActivityQueryService(JdbcTemplate jdbc, AdminAccess access, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> forUser(UUID userId, String date) {
        access.requireSuperAdminOrPlatformAdmin();
        LocalDate day = day(date);
        List<Map<String, Object>> rows = jdbc.query(COLUMNS + """
                 WHERE (e.occurred_at AT TIME ZONE 'Asia/Kolkata')::date = ?
                   AND (
                         e.actor_user_id = ?
                      OR e.subject_id = ?
                      OR oi.user_id = ?
                      OR pa.user_id = ?
                      OR fr.retailer_user_id = ?
                   )
                 ORDER BY e.occurred_at DESC
                """,
                (rs, row) -> row(rs),
                java.sql.Date.valueOf(day),
                userId,
                userId.toString(),
                userId,
                userId,
                userId);
        enrichDigiLockerDocumentNames(rows);
        return rows;
    }

    public List<Map<String, Object>> forMe(String date) {
        access.requireSuperAdminOrPlatformAdmin();
        LocalDate day = day(date);
        List<Map<String, Object>> rows = jdbc.query(COLUMNS + """
                 WHERE (e.occurred_at AT TIME ZONE 'Asia/Kolkata')::date = ?
                   AND e.actor_user_id = ?
                 ORDER BY e.occurred_at DESC
                """, (rs, row) -> row(rs), java.sql.Date.valueOf(day), access.me().userId());
        enrichDigiLockerDocumentNames(rows);
        return rows;
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getObject("id"));
        out.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
        out.put("actorUserId", rs.getObject("actor_user_id"));
        out.put("actorUserType", rs.getString("actor_user_type"));
        String subjectUserType = rs.getString("subject_user_type");
        String httpMethod = rs.getString("http_method");
        String httpPath = rs.getString("http_path");
        out.put("actorCode", rs.getString("actor_code"));
        out.put("action", ActivityPhrases.display(rs.getString("action"), httpMethod, httpPath, subjectUserType));
        out.put("subjectType", rs.getString("subject_type"));
        out.put("subjectId", rs.getString("subject_id"));
        out.put("subjectCode", rs.getString("subject_code"));
        out.put("subjectName", rs.getString("subject_name"));
        out.put("subjectUserType", subjectUserType);
        out.put("outcome", rs.getString("outcome"));
        out.put("httpStatus", rs.getObject("http_status"));
        out.put("httpMethod", httpMethod);
        out.put("httpPath", httpPath);

        Map<String, Object> detail = readDetail(rs.getString("detail"), httpPath);
        String resolvedAgNo = rs.getString("resolved_agreement_no");
        if (resolvedAgNo != null && !resolvedAgNo.isBlank() && !detail.containsKey("agreementNo")) {
            detail.put("agreementNo", resolvedAgNo.trim());
        }
        out.put("detail", detail);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void enrichDigiLockerDocumentNames(List<Map<String, Object>> rows) {
        Set<UUID> missingDocIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String path = (String) row.get("httpPath");
            if (path == null || !path.contains("/digilocker-documents/") || !path.endsWith("/review")) {
                continue;
            }
            Map<String, Object> detail = (Map<String, Object>) row.get("detail");
            if (detail != null && detail.get("documentName") == null) {
                UUID docId = ActivityActions.uuidAfterSegment(path, "digilocker-documents");
                if (docId != null) {
                    missingDocIds.add(docId);
                }
            }
        }
        if (missingDocIds.isEmpty()) {
            return;
        }
        String placeholders = missingDocIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<UUID, String> namesById = new HashMap<>();
        try {
            jdbc.query(
                    "SELECT id, COALESCE(NULLIF(name, ''), doc_type) AS doc_name FROM digilocker_documents WHERE id IN ("
                            + placeholders + ")",
                    rs -> {
                        UUID id = (UUID) rs.getObject("id");
                        String name = rs.getString("doc_name");
                        if (id != null && name != null && !name.isBlank()) {
                            namesById.put(id, name.trim());
                        }
                    },
                    missingDocIds.toArray());
        } catch (Exception ignored) {
            return;
        }
        for (Map<String, Object> row : rows) {
            String path = (String) row.get("httpPath");
            if (path == null || !path.contains("/digilocker-documents/") || !path.endsWith("/review")) {
                continue;
            }
            UUID docId = ActivityActions.uuidAfterSegment(path, "digilocker-documents");
            String docName = docId == null ? null : namesById.get(docId);
            if (docName != null) {
                Map<String, Object> detail = (Map<String, Object>) row.get("detail");
                if (detail != null) {
                    detail.put("documentName", docName);
                }
            }
        }
    }

    private Map<String, Object> readDetail(String json, String httpPath) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            if (raw == null || raw.isEmpty()) {
                return new LinkedHashMap<>();
            }
            boolean digilockerCallback = httpPath != null && httpPath.endsWith("/digilocker/complete");
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.startsWith("_") || ActivityAuditFilter.isSecretKey(key)) {
                    continue;
                }
                String lower = key.toLowerCase(Locale.ROOT);
                if (digilockerCallback && ("state".equals(lower) || "clientid".equals(lower))) {
                    continue;
                }
                sanitized.put(key, entry.getValue());
            }
            if (!sanitized.containsKey("name") && sanitized.get("fullName") != null) {
                sanitized.put("name", sanitized.get("fullName"));
            }
            return sanitized;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static LocalDate day(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now(ZONE);
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.of(HttpStatus.BAD_REQUEST, "INVALID_DATE", "Use a date as YYYY-MM-DD");
        }
    }
}
