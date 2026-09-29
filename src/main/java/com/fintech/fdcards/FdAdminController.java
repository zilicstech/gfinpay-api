package com.fintech.fdcards;

import com.fintech.fdcards.FdAdminService.BudgetBandInput;
import com.fintech.fdcards.FdAdminService.CommissionRuleInput;
import com.fintech.fdcards.FdAdminService.CommissionTierInput;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/fd-cards")
public class FdAdminController {

    public record ProviderToggleRequest(@NotNull Boolean enabled) {}

    public record ProviderConfigRequest(Integer fallbackRank, Map<String, String> applyUrls) {}

    public record BudgetBandsRequest(@Valid @NotNull List<BudgetBandInput> bands) {}

    public record ProviderCommissionRequest(@Valid @NotNull CommissionRuleInput rule) {}


    public record FdCardUpdateRequest(String applyUrl, Boolean active) {}

    public record RuleRequest(@Valid @NotNull BudgetBandInput rule) {}

    public record TierRequest(@Valid @NotNull CommissionTierInput tier) {}

    private final FdAdminService admin;

    public FdAdminController(FdAdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> providers() {
        return ApiResponse.ok(admin.listProviders());
    }

    @GetMapping("/providers/{code}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> provider(@PathVariable String code) {
        return ApiResponse.ok(admin.getProvider(code));
    }

    @PatchMapping("/providers/{code}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> toggleProvider(@PathVariable String code,
                                                           @Valid @RequestBody ProviderToggleRequest req) {
        return ApiResponse.ok(admin.setProviderEnabled(code, req.enabled()));
    }

    @PatchMapping("/providers/{code}/config")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> configProvider(@PathVariable String code,
                                                             @RequestBody ProviderConfigRequest req) {
        return ApiResponse.ok(admin.updateProviderConfig(code, req.fallbackRank(), req.applyUrls()));
    }

    @GetMapping("/cards")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> cards() {
        return ApiResponse.ok(admin.listFdCards());
    }

    @PatchMapping("/cards/{code}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> updateCard(@PathVariable String code,
                                                       @RequestBody FdCardUpdateRequest req) {
        return ApiResponse.ok(admin.updateFdCard(code, req.applyUrl(), req.active()));
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> rules() {
        return ApiResponse.ok(admin.listBudgetBands());
    }

    @GetMapping("/budget-bands")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> budgetBands() {
        return ApiResponse.ok(admin.listBudgetBands());
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> createRule(@Valid @RequestBody RuleRequest req) {
        return ApiResponse.ok(admin.createRule(req.rule()));
    }

    @PatchMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> updateRule(@PathVariable UUID id, @Valid @RequestBody RuleRequest req) {
        return ApiResponse.ok(admin.updateRule(id, req.rule()));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, String>> deleteRule(@PathVariable UUID id) {
        admin.deleteRule(id);
        return ApiResponse.ok(Map.of("deleted", id.toString()));
    }

    @PutMapping("/budget-bands")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> saveBudgetBands(@Valid @RequestBody BudgetBandsRequest req) {
        return ApiResponse.ok(admin.replaceBudgetBands(req.bands()));
    }

    @GetMapping("/commission-tiers")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<Map<String, Object>>> commissionTiers() {
        return ApiResponse.ok(admin.listCommissionTiers());
    }

    @PostMapping("/commission-tiers")
    @PreAuthorize("hasAuthority('commissions.configure')")
    public ApiResponse<Map<String, Object>> createTier(@Valid @RequestBody TierRequest req) {
        return ApiResponse.ok(admin.createCommissionTier(req.tier()));
    }

    @PatchMapping("/commission-tiers/{id}")
    @PreAuthorize("hasAuthority('commissions.configure')")
    public ApiResponse<Map<String, Object>> updateTier(@PathVariable UUID id, @Valid @RequestBody TierRequest req) {
        return ApiResponse.ok(admin.updateCommissionTier(id, req.tier()));
    }

    @DeleteMapping("/commission-tiers/{id}")
    @PreAuthorize("hasAuthority('commissions.configure')")
    public ApiResponse<Map<String, String>> deleteTier(@PathVariable UUID id) {
        admin.deleteCommissionTier(id);
        return ApiResponse.ok(Map.of("deleted", id.toString()));
    }

    @PutMapping("/providers/{code}/commission")
    @PreAuthorize("hasAnyAuthority('settings.manage', 'commissions.configure')")
    public ApiResponse<Map<String, Object>> providerCommission(@PathVariable String code,
                                                               @Valid @RequestBody ProviderCommissionRequest req) {
        String txnType = switch (code.toUpperCase()) {
            case "ZET" -> "FD_ZET";
            case "PAYSPRINT" -> "FD_PAYSPRINT";
            case "GROWMORE" -> "FD_GROWMORE";
            default -> throw ApiException.of(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", "Unknown FD provider");
        };
        return ApiResponse.ok(admin.upsertCommissionRule(txnType, req.rule()));
    }
}
