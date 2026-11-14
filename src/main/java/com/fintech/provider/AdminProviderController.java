package com.fintech.provider;

import com.fintech.emerald.EmeraldAuditLogService;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for multi-provider routing policies and per-retailer Emerald onboarding.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminProviderController {

    public record EmeraldOnboardRequest(
            String aadhaarNumber,
            String email,
            String pipeCode
    ) {}

    public record UpdateChannelRequest(
            Boolean active,
            Integer priorityOrder,
            String displayLabel
    ) {}

    public record UpdateRoutingPolicyRequest(
            String strategy,
            Boolean active
    ) {}

    public record UpdateRetailerChannelRequest(
            Boolean enabledForRetailer,
            Boolean reopenBiometric
    ) {}

    public record SyncBankRequest(
            String pipeCode
    ) {}

    public record BankActiveRequest(
            Boolean active
    ) {}

    private final ProviderOnboardingService onboarding;
    private final ServiceProviderRouter router;
    private final EmeraldAuditLogService emeraldAuditLogs;

    public AdminProviderController(
            ProviderOnboardingService onboarding,
            ServiceProviderRouter router) {
        this(onboarding, router, null);
    }

    @Autowired
    public AdminProviderController(
            ProviderOnboardingService onboarding,
            ServiceProviderRouter router,
            EmeraldAuditLogService emeraldAuditLogs) {
        this.onboarding = onboarding;
        this.router = router;
        this.emeraldAuditLogs = emeraldAuditLogs;
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAuthority('settings.manage') or hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> listPlatformProviders() {
        return ApiResponse.ok(router.listPlatformConfiguration());
    }

    @PatchMapping("/providers/channels/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> updatePlatformChannel(
            @PathVariable UUID id,
            @RequestBody UpdateChannelRequest body) {
        return ApiResponse.ok(router.updateChannel(id, body.active(), body.priorityOrder(), body.displayLabel()));
    }

    @PutMapping("/providers/routing/{serviceCode}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> updateRoutingPolicy(
            @PathVariable String serviceCode,
            @RequestBody UpdateRoutingPolicyRequest body) {
        return ApiResponse.ok(router.updatePolicy(serviceCode, body.strategy(), body.active()));
    }

    @GetMapping("/users/{id}/providers")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> getRetailerProviders(@PathVariable UUID id) {
        return ApiResponse.ok(onboarding.getRetailerProviders(id));
    }

    @PostMapping("/users/{id}/providers/emerald/onboard")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> onboardToEmerald(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @RequestBody(required = false) EmeraldOnboardRequest body) {
        EmeraldOnboardRequest req = body == null ? new EmeraldOnboardRequest(null, null, null) : body;
        return ApiResponse.ok(onboarding.onboardToEmerald(me, id, req.aadhaarNumber(), req.email(), req.pipeCode()));
    }

    @PostMapping("/users/{id}/providers/emerald/sync-bank")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> syncEmeraldBank(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id) {
        return ApiResponse.ok(onboarding.syncEmeraldBank(me, id));
    }

    @PostMapping("/users/{id}/providers/emerald/refresh")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> refreshEmerald(@PathVariable UUID id) {
        return ApiResponse.ok(onboarding.refreshEmeraldStatus(id));
    }

    @PostMapping("/users/{id}/providers/{providerCode}/onboard")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> onboardToProvider(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @PathVariable String providerCode,
            @RequestBody(required = false) EmeraldOnboardRequest body) {
        EmeraldOnboardRequest req = body == null ? new EmeraldOnboardRequest(null, null, null) : body;
        return ApiResponse.ok(onboarding.onboardToProvider(
                me, id, providerCode, req.aadhaarNumber(), req.email(), req.pipeCode()));
    }

    @PostMapping("/users/{id}/providers/{providerCode}/sync-bank")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> syncProviderBank(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @PathVariable String providerCode,
            @RequestBody(required = false) SyncBankRequest body) {
        return ApiResponse.ok(onboarding.syncProviderBank(
                me, id, providerCode, body == null ? null : body.pipeCode()));
    }

    @PostMapping("/users/{id}/providers/{providerCode}/refresh")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> refreshProvider(
            @PathVariable UUID id,
            @PathVariable String providerCode) {
        return ApiResponse.ok(onboarding.refreshProviderStatus(id, providerCode));
    }

    @PostMapping("/users/{id}/banks/{bankId}/verify")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> verifyRetailerBank(
            @PathVariable UUID id,
            @PathVariable UUID bankId) {
        return ApiResponse.ok(onboarding.verifyRetailerBank(id, bankId));
    }

    @PostMapping("/users/{id}/banks/{bankId}/active")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> setRetailerBankActive(
            @PathVariable UUID id,
            @PathVariable UUID bankId,
            @RequestBody BankActiveRequest body) {
        return ApiResponse.ok(onboarding.setRetailerBankActive(id, bankId, Boolean.TRUE.equals(body.active())));
    }

    @PatchMapping("/users/{id}/providers/channels/{channelId}")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> updateRetailerChannel(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @PathVariable UUID channelId,
            @RequestBody UpdateRetailerChannelRequest body) {
        return ApiResponse.ok(onboarding.updateRetailerChannel(
                me, id, channelId, body.enabledForRetailer(), body.reopenBiometric()));
    }

    @GetMapping("/providers/emerald/logs")
    @PreAuthorize("hasAnyAuthority('settings.manage','users.onboard','finance.review')")
    public ApiResponse<List<Map<String, Object>>> listGlobalEmeraldLogs(
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String flow,
            @RequestParam(required = false) String outletCode,
            @RequestParam(required = false) String externalRef,
            @RequestParam(defaultValue = "50") int limit) {
        if (emeraldAuditLogs == null) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(emeraldAuditLogs.listGlobal(direction, flow, outletCode, externalRef, limit));
    }

    @GetMapping("/users/{id}/providers/emerald/logs")
    @PreAuthorize("hasAnyAuthority('settings.manage','users.onboard','finance.review')")
    public ApiResponse<List<Map<String, Object>>> listUserEmeraldLogs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "40") int limit) {
        if (emeraldAuditLogs == null) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(emeraldAuditLogs.listForUser(id, limit));
    }

    @GetMapping("/users/{id}/providers/export-csv")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ResponseEntity<byte[]> exportRetailerCsv(@PathVariable UUID id) {
        String csv = onboarding.exportVendor2Csv(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("retailer_onboarding_" + id.toString().substring(0, 8) + ".csv")
                .build());
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }
}

