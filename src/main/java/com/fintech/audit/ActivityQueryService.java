package com.fintech.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.AdminAccess;
import com.fintech.platform.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ActivityQueryService {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static final String COLUMNS = """
            SELECT e.id, e.occurred_at, e.actor_user_id, e.actor_user_type, e.actor_code,
                   e.action, e.subject_type, e.subject_id, e.outcome, e.http_status,
                   e.http_method, e.http_path, e.detail::text AS detail,
                   subject_user.code AS subject_code,
                   subject_user.full_name AS subject_name,
                   subject_user.user_type::text AS subject_user_type
              FROM activity_events e
              LEFT JOIN users subject_user ON subject_user.id::text = e.subject_id
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
        return jdbc.query(COLUMNS + """
                 WHERE (e.occurred_at AT TIME ZONE 'Asia/Kolkata')::date = ?
                   AND (e.actor_user_id = ? OR e.subject_id = ?)
                 ORDER BY e.occurred_at DESC
                """, (rs, row) -> row(rs), java.sql.Date.valueOf(day), userId, userId.toString());
    }

    public List<Map<String, Object>> forMe(String date) {
        access.requireSuperAdminOrPlatformAdmin();
        LocalDate day = day(date);
        return jdbc.query(COLUMNS + """
                 WHERE (e.occurred_at AT TIME ZONE 'Asia/Kolkata')::date = ?
                   AND e.actor_user_id = ?
                 ORDER BY e.occurred_at DESC
                """, (rs, row) -> row(rs), java.sql.Date.valueOf(day), access.me().userId());
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", rs.getObject("id"));
        out.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
        out.put("actorUserId", rs.getObject("actor_user_id"));
        out.put("actorUserType", rs.getString("actor_user_type"));
        String subjectUserType = rs.getString("subject_user_type");
        out.put("actorCode", rs.getString("actor_code"));
        out.put("action", ActivityPhrases.display(rs.getString("action"), subjectUserType));
        out.put("subjectType", rs.getString("subject_type"));
        out.put("subjectId", rs.getString("subject_id"));
        out.put("subjectCode", rs.getString("subject_code"));
        out.put("subjectName", rs.getString("subject_name"));
        out.put("subjectUserType", subjectUserType);
        out.put("outcome", rs.getString("outcome"));
        out.put("httpStatus", rs.getObject("http_status"));
        out.put("httpMethod", rs.getString("http_method"));
        out.put("httpPath", rs.getString("http_path"));
        out.put("detail", readDetail(rs.getString("detail")));
        return out;
    }

    private Object readDetail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
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
