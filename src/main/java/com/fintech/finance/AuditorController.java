package com.fintech.finance;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import com.fintech.platform.web.PageWindow;
import com.fintech.sales.CustomerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auditor")
public class AuditorController {

    public record WalletAdjustRequest(
            @NotNull BigDecimal amount,
            @NotBlank String direction,
            String narration) {}

    private final FinanceService finance;
    private final CustomerService customers;

    public AuditorController(FinanceService finance, CustomerService customers) {
        this.finance = finance;
        this.customers = customers;
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<List<Map<String, Object>>> customers(@AuthenticationPrincipal AuthPrincipal me,
                                                            @RequestParam(required = false) String mobile) {
        return ApiResponse.ok(customers.list(me, mobile, null, null));
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<Map<String, Object>> customer(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(customers.get(me, id));
    }

    @GetMapping("/retailers")
    @PreAuthorize("hasAuthority('finance.wallet.adjust')")
    public ApiResponse<List<Map<String, Object>>> retailers(@RequestParam(required = false) Integer limit,
                                                            @RequestParam(required = false) Integer offset) {
        return ApiResponse.ok(finance.listRetailers(PageWindow.limit(limit), PageWindow.offset(offset)));
    }

    @GetMapping("/retailers/{id}")
    @PreAuthorize("hasAuthority('finance.wallet.adjust')")
    public ApiResponse<Map<String, Object>> retailer(@PathVariable UUID id) {
        return ApiResponse.ok(finance.getRetailer(id));
    }

    @PostMapping("/retailers/{id}/wallet/adjust")
    @PreAuthorize("hasAuthority('finance.wallet.adjust')")
    public ApiResponse<Map<String, Object>> adjust(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody WalletAdjustRequest req) {
        return ApiResponse.ok(finance.adjustRetailerWallet(me, id, req.amount(), req.direction(), req.narration()));
    }
}
