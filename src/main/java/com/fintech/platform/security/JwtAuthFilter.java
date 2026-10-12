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

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_" + ctx.userType()));
                ctx.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                AuthPrincipal principal = new AuthPrincipal(userId, name, ctx.userType(), sessionId);
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                if (ctx.isPendingActivation() && !pendingRetailerPath(request.getRequestURI())) {
                    log.warn("JWT pending retailer blocked path={} user={}", request.getRequestURI(), userId);
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
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

    private static boolean pendingRetailerPath(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/v1/me")
                || path.startsWith("/api/v1/me/")
                || path.equals("/api/v1/finance")
                || path.startsWith("/api/v1/finance/");
    }
}
