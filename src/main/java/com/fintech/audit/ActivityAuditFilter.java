package com.fintech.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.onboarding.InviteService;
import com.fintech.onboarding.PublicOnboardingController;
import com.fintech.onboarding.SignatoryKycController;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.verification.BusinessRegistrationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
            "password", "newpassword", "currentpassword", "oldpassword", "confirmpassword",
            "pin", "newpin", "currentpin", "oldpin", "confirmpin", "securitypin", "mpin",
            "token", "resettoken", "sessiontoken", "accesstoken", "refreshtoken",
            "access_token", "refresh_token", "otp",
            "aadhaar", "aadhaarnumber", "aadhaar_number",
            "pan", "pannumber", "pan_number",
            "secret", "authorization", "refresh", "proof", "cookie", "authorisedkey", "jwt",
            "piddata", "pid_data", "pid", "wadh", "skey", "hmac", "dob");

    static boolean isSecretKey(String key) {
        return key != null && SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

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
        if (path.startsWith("/webhooks") || path.startsWith("/emerald/") || path.startsWith("/actuator")) {
            return true;
        }
        if (path.startsWith("/api/v1/public")) {
            return !path.startsWith("/api/v1/public/onboarding/")
                    && !path.startsWith("/api/v1/public/signatory/");
        }
        return "/api/v1/auth/refresh".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper cached = new ContentCachingRequestWrapper(request, CACHE_LIMIT);
        SubjectBefore subject = subjectBefore(request);
        OnboardingContext onboardingCtx = onboardingBefore(request);
        SignatoryContext signatoryCtx = signatoryBefore(request);
        int status = 500;
        try {
            chain.doFilter(cached, response);
            status = response.getStatus();
        } catch (Exception e) {
            submit(cached, status, subject, onboardingCtx, signatoryCtx);
            if (e instanceof ServletException servletException) {
                throw servletException;
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new ServletException(e);
        }
        submit(cached, status == 0 ? response.getStatus() : status, subject, onboardingCtx, signatoryCtx);
    }

    private void submit(
            ContentCachingRequestWrapper request,
            int status,
            SubjectBefore subject,
            OnboardingContext onboardingCtx,
            SignatoryContext signatoryCtx) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        AuthPrincipal actor = principal();
        boolean authRoute = ActivityPhrases.isUnauthenticatedAuthRoute(method, path);
        boolean publicOnboarding = path.startsWith("/api/v1/public/onboarding/");
        boolean publicSignatory = path.startsWith("/api/v1/public/signatory/");

        if (publicOnboarding && onboardingCtx == null) {
            return;
        }
        if (publicSignatory && signatoryCtx == null) {
            return;
        }
        if (!authRoute && !publicOnboarding && !publicSignatory
                && (actor == null || !ActivityAuditWriter.ACTOR_TYPES.contains(actor.userType()))) {
            return;
        }

        Map<String, Object> detail = detail(request);
        if (onboardingCtx != null) {
            detail.put("_inviteId", onboardingCtx.inviteId().toString());
            if (!onboardingCtx.fullName().isBlank()) {
                detail.put("applicantName", onboardingCtx.fullName());
                detail.put("name", onboardingCtx.fullName());
            }
            String role = ActivityPhrases.role(onboardingCtx.role());
            if (!role.isBlank()) {
                detail.put("role", role);
            }
        }
        if (signatoryCtx != null) {
            detail.put("_partnerUserId", signatoryCtx.userId().toString());
            if (!signatoryCtx.directorName().isBlank()) {
                detail.put("signatoryName", signatoryCtx.directorName());
            }
        }

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

    /** Resolve self-service onboarding invite before filter execution (since setCredentials clears session_hash). */
    private OnboardingContext onboardingBefore(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/public/onboarding/")) {
            return null;
        }
        try {
            Map<String, Object> row;
            if (path.startsWith("/api/v1/public/onboarding/session/")) {
                String session = request.getHeader(PublicOnboardingController.SESSION_HEADER);
                if (session == null || session.isBlank()) {
                    return null;
                }
                row = jdbc.queryForMap("""
                        SELECT id, role, full_name
                          FROM onboarding_invites
                         WHERE session_hash = ?
                        """, InviteService.sha256(session.trim()));
            } else {
                String token = ActivityActions.segmentAfter(path, "onboarding");
                if (token == null || token.isBlank() || "session".equals(token)) {
                    return null;
                }
                row = jdbc.queryForMap("""
                        SELECT id, role, full_name
                          FROM onboarding_invites
                         WHERE token_hash = ?
                        """, InviteService.sha256(token.trim()));
            }
            return new OnboardingContext(
                    (UUID) row.get("id"),
                    text(row.get("role")),
                    text(row.get("full_name")));
        } catch (Exception e) {
            return null;
        }
    }

    /** Resolve company signatory context before filter execution so steps link to the partner user. */
    private SignatoryContext signatoryBefore(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/public/signatory/")) {
            return null;
        }
        try {
            Map<String, Object> row;
            if (path.startsWith("/api/v1/public/signatory/session/")) {
                String session = request.getHeader(SignatoryKycController.SESSION_HEADER);
                if (session == null || session.isBlank()) {
                    return null;
                }
                row = jdbc.queryForMap("""
                        SELECT id, user_id, director_name
                          FROM business_signatories
                         WHERE session_hash = ?
                        """, BusinessRegistrationService.sha256(session.trim()));
            } else {
                String token = ActivityActions.segmentAfter(path, "signatory");
                if (token == null || token.isBlank() || "session".equals(token)) {
                    return null;
                }
                row = jdbc.queryForMap("""
                        SELECT id, user_id, director_name
                          FROM business_signatories
                         WHERE token_hash = ?
                        """, BusinessRegistrationService.sha256(token.trim()));
            }
            return new SignatoryContext(
                    (UUID) row.get("id"),
                    (UUID) row.get("user_id"),
                    text(row.get("director_name")));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record SubjectBefore(String code, String name, String userType, String status) {}

    private record OnboardingContext(UUID inviteId, String role, String fullName) {}

    private record SignatoryContext(UUID signatoryId, UUID userId, String directorName) {}

    private Map<String, Object> detail(ContentCachingRequestWrapper request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        String path = request.getRequestURI();
        boolean digilockerCallback = path != null && path.endsWith("/digilocker/complete");
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
                if ("/api/v1/auth/reset/confirm".equals(path) && node.hasNonNull("resetToken")) {
                    String subjectUuid = extractJwtSubjectUuid(node.get("resetToken").asText());
                    if (subjectUuid != null) {
                        detail.put("_resolvedActorId", subjectUuid);
                    }
                }
                node.fields().forEachRemaining(entry -> {
                    String lowerKey = entry.getKey().toLowerCase(Locale.ROOT);
                    if (SECRET_KEYS.contains(lowerKey)) {
                        return;
                    }
                    if (digilockerCallback && ("state".equals(lowerKey) || "clientid".equals(lowerKey))) {
                        return;
                    }
                    detail.put(entry.getKey(), value(entry.getValue()));
                });
            }
        } catch (Exception e) {
            detail.put("body", "unparsed");
        }
        if (ActivityPhrases.isUnauthenticatedAuthRoute(request.getMethod(), path)) {
            Object identifier = detail.get("identifier");
            if (identifier == null || String.valueOf(identifier).isBlank()) {
                Object mobile = detail.get("mobile");
                if (mobile != null && !String.valueOf(mobile).isBlank()) {
                    detail.put("identifier", mobile);
                }
            }
            detail.remove("mobile");
        }
        return detail;
    }

    private String extractJwtSubjectUuid(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return null;
        }
        String[] parts = jwt.trim().split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = mapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (payload.hasNonNull("sub")) {
                UUID parsed = UUID.fromString(payload.get("sub").asText().trim());
                return parsed.toString();
            }
        } catch (Exception ignored) {
            // Malformed or non-UUID subject; writer will handle gracefully.
        }
        return null;
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
