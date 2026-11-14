package com.fintech.identity;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.security.RefreshCookies;
import com.fintech.platform.security.SessionService;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    /** Cookie-authenticated endpoints require this header, which forces a CORS preflight. */
    private static final String REQUESTED_WITH = "fintech-app";

    public record LoginRequest(String identifier, String mobile, @NotBlank String password, String pin, String newPin) {
        public LoginRequest(String identifier, String mobile, String password) {
            this(identifier, mobile, password, null, null);
        }
        public LoginRequest(String identifier, String mobile, String password, String pin) {
            this(identifier, mobile, password, pin, null);
        }
    }

    public record ResetOtpRequest(@NotBlank String identifier) {}

    public record ResetVerifyRequest(@NotBlank String identifier, @NotBlank String otp) {}

    public record ResetConfirmRequest(@NotBlank String resetToken, String newPassword, String newPin) {}

    public record LoginOtpRequest(@NotBlank String identifier) {}

    public record LoginOtpVerifyRequest(@NotBlank String identifier, @NotBlank String otp) {}

    private final UserService userService;
    private final SessionService sessions;
    private final RefreshCookies cookies;

    public AuthController(UserService userService, SessionService sessions, RefreshCookies cookies) {
        this.userService = userService;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        String identifier = request.identifier() != null && !request.identifier().isBlank()
                ? request.identifier()
                : request.mobile();
        UserService.LoginResult result = userService.login(
                identifier, request.password(), request.pin(), request.newPin(), forwardedFor, userAgent);

        HttpHeaders headers = new HttpHeaders();
        if (result.refreshCookie() != null && !result.refreshCookie().isBlank()) {
            cookies.attach(headers, result.refreshCookie(), result.refreshExpiresAt());
        }
        return ResponseEntity.ok().headers(headers).body(ApiResponse.ok(result.body()));
    }

    @PostMapping("/otp/send")
    public ApiResponse<Map<String, Object>> sendLoginOtp(@RequestBody LoginOtpRequest request) {
        return ApiResponse.ok(userService.requestLoginOtp(request.identifier()));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyLoginOtp(
            @RequestBody LoginOtpVerifyRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        UserService.LoginResult result = userService.verifyLoginOtp(
                request.identifier(), request.otp(), forwardedFor, userAgent);

        HttpHeaders headers = new HttpHeaders();
        if (result.refreshCookie() != null && !result.refreshCookie().isBlank()) {
            cookies.attach(headers, result.refreshCookie(), result.refreshExpiresAt());
        }
        return ResponseEntity.ok().headers(headers).body(ApiResponse.ok(result.body()));
    }

    /** Rotates the refresh cookie and mints a new access token, provided the session is live and not idle. */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refresh(
            HttpServletRequest request, HttpServletResponse response,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith) {
        requireRequestedWith(requestedWith);
        String refreshCookie = cookies.read(request);
        SessionService.Rotation rotation = sessions.rotate(refreshCookie);
        if (!rotation.ok()) {
            if (refreshCookie != null) {
                cookies.clear(response);
            }
            throw SessionService.sessionExpired();
        }
        SessionService.Issued rotated = rotation.issued();
        Map<String, Object> body = userService.issueForSession(rotated.userId(), rotated.sessionId());

        HttpHeaders headers = new HttpHeaders();
        cookies.attach(headers, rotated.cookieValue(), rotated.expiresAt());
        return ResponseEntity.ok().headers(headers).body(ApiResponse.ok(body));
    }

    /** Idempotent: revokes whichever session the caller can prove, and always clears the cookie. */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logout(
            HttpServletRequest request,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
            @AuthenticationPrincipal AuthPrincipal me) {
        requireRequestedWith(requestedWith);
        if (me != null) {
            sessions.revoke(me.sessionId(), "LOGOUT");
        }
        sessions.revokeByCookie(cookies.read(request), "LOGOUT");

        HttpHeaders headers = new HttpHeaders();
        cookies.clear(headers);
        return ResponseEntity.ok().headers(headers).body(ApiResponse.ok(Map.of("loggedOut", true)));
    }

    @PostMapping("/reset/otp")
    public ApiResponse<Map<String, Object>> requestResetOtp(@RequestBody ResetOtpRequest request) {
        return ApiResponse.ok(userService.requestResetOtp(request.identifier()));
    }

    @PostMapping("/reset/verify")
    public ApiResponse<Map<String, Object>> verifyResetOtp(@RequestBody ResetVerifyRequest request) {
        return ApiResponse.ok(userService.verifyResetOtp(request.identifier(), request.otp()));
    }

    @PostMapping("/reset/confirm")
    public ApiResponse<Map<String, Object>> confirmReset(@RequestBody ResetConfirmRequest request) {
        return ApiResponse.ok(userService.confirmReset(
                request.resetToken(), request.newPassword(), request.newPin()));
    }

    private static void requireRequestedWith(String requestedWith) {
        if (!REQUESTED_WITH.equals(requestedWith)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "INVALID_REQUEST_ORIGIN", "Request origin not allowed");
        }
    }
}
