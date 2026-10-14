package com.fintech.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.security.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Builds an activity event after the response is ready and hands it to the async writer. */
public class ActivityAuditFilter extends OncePerRequestFilter {

    private static final int CACHE_LIMIT = 32_768;
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "token", "otp", "pin", "aadhaar", "pan", "secret", "authorization",
            "refresh", "proof", "cookie", "authorisedkey", "jwt", "access_token", "refresh_token");

    private final ActivityAuditWriter writer;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public ActivityAuditFilter(ActivityAuditWriter writer, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.writer = writer;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (!MUTATING.contains(method)) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/webhooks") || path.startsWith("/api/v1/public") || path.startsWith("/actuator")) {
            return true;
        }
        return "/api/v1/auth/refresh".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper cached = new ContentCachingRequestWrapper(request, CACHE_LIMIT);
        SubjectBefore subject = subjectBefore(request);
        int status = 500;
        try {
            chain.doFilter(cached, response);
            status = response.getStatus();
        } catch (Exception e) {
            submit(cached, status, subject);
            if (e instanceof ServletException servletException) {
                throw servletException;
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new ServletException(e);
        }
        submit(cached, status == 0 ? response.getStatus() : status, subject);
    }

    private void submit(ContentCachingRequestWrapper request, int status, SubjectBefore subject) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        AuthPrincipal actor = principal();
        boolean login = "POST".equals(method) && "/api/v1/auth/login".equals(path);
        if (!login && (actor == null || !ActivityAuditWriter.ACTOR_TYPES.contains(actor.userType()))) {
            return;
        }
        Map<String, Object> detail = detail(request);
        String action = ActivityActions.action(method, path);
        if (subject != null) {
            action = ActivityPhrases.statusChange(subject.userType());
            if (!subject.code().isBlank()) {
                detail.put("code", subject.code());
            }
            if (!subject.name().isBlank()) {
                detail.put("name", subject.name());
            }
            String role = ActivityPhrases.role(subject.userType());
            if (!role.isBlank()) {
                detail.put("role", role);
            }
            String previous = ActivityPhrases.status(subject.status());
            if (!previous.isBlank()) {
                detail.put("previousStatus", previous);
            }
            Object requested = detail.remove("status");
            String current = ActivityPhrases.status(requested == null ? "" : String.valueOf(requested));
            if (!current.isBlank()) {
                detail.put("currentStatus", current);
            }
        }
        writer.save(new ActivityEvent(
                Instant.now(),
                actor == null ? null : actor.userId(),
                actor == null ? null : actor.userType(),
                action,
                ActivityActions.subjectType(path),
                ActivityActions.subjectId(path),
                status >= 200 && status < 400 ? "SUCCESS" : "FAILURE",
                status,
                method,
                path,
                detail,
                clientIp(request),
                request.getHeader("User-Agent")));
    }

    /** Read the person before the status update, so the log can show the old status. */
    private SubjectBefore subjectBefore(HttpServletRequest request) {
        if (!ActivityActions.isUserStatus(request.getMethod(), request.getRequestURI())) {
            return null;
        }
        String id = ActivityActions.subjectId(request.getRequestURI());
        if (id == null) {
            return null;
        }
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT code, full_name, user_type::text AS user_type, status::text AS status
                      FROM users WHERE id = ?
                    """, UUID.fromString(id));
            return new SubjectBefore(
                    text(row.get("code")),
                    text(row.get("full_name")),
                    text(row.get("user_type")),
                    text(row.get("status")));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record SubjectBefore(String code, String name, String userType, String status) {}

    private Map<String, Object> detail(ContentCachingRequestWrapper request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        String type = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("multipart/")) {
            detail.put("multipart", true);
            if (request.getContentType() != null) {
                try {
                    for (Part part : request.getParts()) {
                        if (part.getSubmittedFileName() != null) {
                            detail.put(part.getName(), part.getSubmittedFileName());
                        }
                    }
                } catch (Exception ignored) {
                    detail.put("files", "unavailable");
                }
            }
            return detail;
        }
        byte[] body = request.getContentAsByteArray();
        if (body.length == 0) {
            return detail;
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    if (SECRET_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                        return;
                    }
                    detail.put(entry.getKey(), value(entry.getValue()));
                });
            }
        } catch (Exception e) {
            detail.put("body", "unparsed");
        }
        if ("POST".equals(request.getMethod()) && "/api/v1/auth/login".equals(request.getRequestURI())) {
            Object identifier = detail.get("identifier");
            if (identifier == null || String.valueOf(identifier).isBlank()) {
                detail.put("identifier", detail.get("mobile"));
            }
            detail.remove("mobile");
        }
        return detail;
    }

    private Object value(JsonNode node) {
        if (node.isTextual()) {
            String text = node.asText();
            return text.length() > 500 ? text.substring(0, 500) : text;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        String text = node.toString();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static AuthPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal;
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
