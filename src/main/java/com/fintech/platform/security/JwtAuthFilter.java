package com.fintech.platform.security;

import com.fintech.identity.UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;
    private final UserService users;

    public JwtAuthFilter(JwtService jwtService, UserService users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                UUID userId = UUID.fromString(claims.getSubject());
                UUID sessionId = sessionId(claims);
                if (sessionId == null) {
                    log.warn("JWT rejected missing sid user={}", userId);
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                UserService.AuthContext ctx = users.authContext(userId, sessionId);
                if (ctx == null || !ctx.canAuthenticate()) {
                    log.warn("JWT rejected inactive user={}", userId);
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                if (!ctx.sessionActive()) {
                    log.warn("JWT rejected revoked session user={} session={}", userId, sessionId);
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                String name = claims.get("name", String.class);
                String claimScope = claims.get("scope", String.class);
                String effectiveScope = (ctx.reverifyRequired() || JwtService.SCOPE_REVERIFY.equals(claimScope))
                        ? JwtService.SCOPE_REVERIFY
                        : JwtService.SCOPE_FULL;

                if (JwtService.SCOPE_REVERIFY.equals(effectiveScope) && !reverifyAllowedPath(request.getRequestURI())) {
                    log.warn("JWT reverify-scoped user blocked path={} user={}", request.getRequestURI(), userId);
                    SecurityContextHolder.clearContext();
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType("application/json");
                    response.getWriter().write("{\"data\":null,\"error\":{\"code\":\"REVERIFY_REQUIRED\","
                            + "\"message\":\"Complete identity re-verification to access services\","
                            + "\"details\":null,\"retriable\":false},\"meta\":null}");
                    return;
                }

                if (ctx.isPendingActivation() && !pendingPartnerPath(request.getRequestURI())) {
                    log.warn("JWT pending partner blocked path={} user={}", request.getRequestURI(), userId);
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_" + ctx.userType()));
                ctx.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                AuthPrincipal principal = new AuthPrincipal(userId, name, ctx.userType(), sessionId, effectiveScope);
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.warn("JWT rejected: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private static UUID sessionId(Claims claims) {
        String sid = claims.get("sid", String.class);
        if (sid == null || sid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean reverifyAllowedPath(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/v1/me")
                || path.startsWith("/api/v1/me/")
                || path.equals("/api/v1/reverify")
                || path.startsWith("/api/v1/reverify/")
                || path.equals("/api/v1/auth")
                || path.startsWith("/api/v1/auth/");
    }

    private static boolean pendingPartnerPath(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/v1/me")
                || path.startsWith("/api/v1/me/")
                || path.equals("/api/v1/finance")
                || path.startsWith("/api/v1/finance/")
                || path.equals("/api/v1/verification")
                || path.equals("/api/v1/reverify")
                || path.startsWith("/api/v1/reverify/")
                || path.equals("/api/v1/auth")
                || path.startsWith("/api/v1/auth/");
    }
}
