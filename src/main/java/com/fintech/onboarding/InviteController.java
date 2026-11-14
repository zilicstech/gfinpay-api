package com.fintech.onboarding;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/onboarding/invites")
public class InviteController {

    public record CreateInviteRequest(
            @NotBlank String role,
            @NotBlank String fullName,
            @NotBlank String mobile,
            String email,
            UUID parentId,
            UUID hubId
    ) {}

    public record ReviewInviteRequest(
            @NotNull Boolean approve,
            String note
    ) {}

    private final InviteService invites;

    public InviteController(InviteService invites) {
        this.invites = invites;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('users.onboard','outlets.manage')")
    public ApiResponse<Map<String, Object>> create(
            @AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody CreateInviteRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(invites.createInvite(
                me, req.role(), req.fullName(), req.mobile(), req.email(), req.parentId(), req.hubId(), ip, userAgent));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('users.onboard','outlets.manage')")
    public ApiResponse<List<Map<String, Object>>> list(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String step,
            @RequestParam(required = false) String role) {
        return ApiResponse.ok(invites.listInvites(me, status, step, role));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('users.onboard','outlets.manage')")
    public ApiResponse<Map<String, Object>> get(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id) {
        return ApiResponse.ok(invites.getInvite(me, id));
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAnyAuthority('users.onboard','outlets.manage')")
    public ApiResponse<Map<String, Object>> resend(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(invites.resendInvite(me, id, ip, userAgent));
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyAuthority('users.onboard','outlets.manage')")
    public ApiResponse<Map<String, Object>> revoke(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(invites.revokeInvite(me, id, ip, userAgent));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> review(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @Valid @RequestBody ReviewInviteRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(invites.reviewInvite(me, id, req.approve(), req.note(), ip, userAgent));
    }
}

