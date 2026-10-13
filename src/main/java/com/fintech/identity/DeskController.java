package com.fintech.identity;

import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scoped APIs for distributor and retailer desks. */
@RestController
@RequestMapping("/api/v1/desk")
public class DeskController {

    public record CreateOutletRequest(
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "10-digit mobile required") String mobile,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String city,
            @NotBlank String state,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "6-digit pincode required") String pincode,
            @NotBlank @Email(message = "A valid email is required") String email) {}

    public record StatusRequest(@NotBlank String status) {}

    public record PasswordRequest(@NotBlank @Size(min = 8) String password) {}

    public record WalletFreezeRequest(@NotNull Boolean frozen) {}

    public record WalletAdjustRequest(
            @NotNull BigDecimal amount,
            @NotBlank String direction,
            String narration) {}

    private final DeskService desk;
    private final PlatformServiceGate platformServices;
    private final RetailerPaysprintEkycService paysprintEkyc;

    public DeskController(DeskService desk, PlatformServiceGate platformServices,
                          RetailerPaysprintEkycService paysprintEkyc) {
        this.desk = desk;
        this.platformServices = platformServices;
        this.paysprintEkyc = paysprintEkyc;
    }

    @GetMapping("/platform-services")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> platformServices() {
        return ApiResponse.ok(platformServices.listEnabled());
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> overview(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(desk.overview(me));
    }

    @GetMapping("/outlets")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<List<Map<String, Object>>> outlets(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(desk.listOutlets(me));
    }

    @PostMapping("/outlets")
    @PreAuthorize("hasAnyAuthority('outlets.manage','reports.view')")
    public ApiResponse<Map<String, Object>> createOutlet(@AuthenticationPrincipal AuthPrincipal me,
                                                         @Valid @RequestBody CreateOutletRequest req) {
        return ApiResponse.ok(desk.createOutlet(
                me, req.fullName(), req.mobile(), req.email(), req.password(),
                req.city(), req.state(), req.pincode()));
    }

    @GetMapping("/outlets/{id}")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> outlet(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(desk.getOutlet(me, id));
    }

    @PostMapping("/outlets/{id}/paysprint-onboard")
    @PreAuthorize("hasAuthority('outlets.manage')")
    public ApiResponse<Map<String, Object>> paysprintOnboard(@AuthenticationPrincipal AuthPrincipal me,
                                                             @PathVariable UUID id,
                                                             @RequestBody(required = false) Map<String, String> body) {
        desk.getOutlet(me, id);
        String email = body == null ? null : body.get("email");
        return ApiResponse.ok(paysprintEkyc.issue(id, email));
    }

    @PostMapping("/outlets/{id}/paysprint-onboard/status")
    @PreAuthorize("hasAuthority('outlets.manage')")
    public ApiResponse<Map<String, Object>> paysprintOnboardStatus(@AuthenticationPrincipal AuthPrincipal me,
                                                                   @PathVariable UUID id) {
        desk.getOutlet(me, id);
        return ApiResponse.ok(paysprintEkyc.checkStatus(id));
    }

    @PostMapping("/outlets/{id}/paysprint-onboard/refresh")
    @PreAuthorize("hasAuthority('outlets.manage')")
    public ApiResponse<Map<String, Object>> paysprintOnboardRefresh(@AuthenticationPrincipal AuthPrincipal me,
                                                                    @PathVariable UUID id) {
        desk.getOutlet(me, id);
        return ApiResponse.ok(paysprintEkyc.refreshOnboardUrl(id));
    }

    @GetMapping("/outlets/{id}/performance")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> performance(@AuthenticationPrincipal AuthPrincipal me,
                                                        @PathVariable UUID id) {
        return ApiResponse.ok(desk.outletPerformance(me, id));
    }

    @GetMapping("/outlets/{id}/customers")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<List<Map<String, Object>>> outletCustomers(@AuthenticationPrincipal AuthPrincipal me,
                                                                  @PathVariable UUID id) {
        return ApiResponse.ok(desk.listCustomers(me, id));
    }

    @PatchMapping("/outlets/{id}/status")
    @PreAuthorize("hasAnyAuthority('outlets.manage','reports.view')")
    public ApiResponse<Map<String, Object>> outletStatus(@AuthenticationPrincipal AuthPrincipal me,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody StatusRequest req) {
        return ApiResponse.ok(desk.updateOutletStatus(me, id, req.status()));
    }

    @PostMapping("/outlets/{id}/password")
    @PreAuthorize("hasAnyAuthority('outlets.manage','reports.view')")
    public ApiResponse<Map<String, Object>> outletPassword(@AuthenticationPrincipal AuthPrincipal me,
                                                           @PathVariable UUID id,
                                                           @Valid @RequestBody PasswordRequest req) {
        return ApiResponse.ok(desk.resetOutletPassword(me, id, req.password()));
    }

    @PatchMapping("/outlets/{id}/wallet/freeze")
    @PreAuthorize("hasAnyAuthority('outlets.manage','reports.view')")
    public ApiResponse<Map<String, Object>> freeze(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody WalletFreezeRequest req) {
        return ApiResponse.ok(desk.freezeOutletWallet(me, id, req.frozen()));
    }

    @PostMapping("/outlets/{id}/wallet/adjust")
    @PreAuthorize("hasAnyAuthority('outlets.manage','reports.view')")
    public ApiResponse<Map<String, Object>> adjustWallet(@AuthenticationPrincipal AuthPrincipal me,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody WalletAdjustRequest req) {
        return ApiResponse.ok(desk.adjustOutletWallet(me, id, req.amount(), req.direction(), req.narration()));
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> transactions(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(desk.listTransactions(me));
    }

    @GetMapping("/transactions/{id}")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> transaction(@AuthenticationPrincipal AuthPrincipal me,
                                                        @PathVariable UUID id) {
        return ApiResponse.ok(desk.getTransaction(me, id));
    }

    @GetMapping("/kyc")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<List<Map<String, Object>>> kyc(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(desk.listKyc(me));
    }

    @GetMapping("/kyc/{userId}")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> kycOne(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable UUID userId) {
        return ApiResponse.ok(desk.getKyc(me, userId));
    }

    @GetMapping("/earnings")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> earnings(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(desk.earnings(me));
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<List<Map<String, Object>>> customers(@AuthenticationPrincipal AuthPrincipal me,
                                                            @RequestParam(required = false) UUID outletId) {
        return ApiResponse.ok(desk.listCustomers(me, outletId));
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('reports.view')")
    public ApiResponse<Map<String, Object>> customer(@AuthenticationPrincipal AuthPrincipal me,
                                                     @PathVariable UUID id) {
        return ApiResponse.ok(desk.getCustomer(me, id));
    }
}
