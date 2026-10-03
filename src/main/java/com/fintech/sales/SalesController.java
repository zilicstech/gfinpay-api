package com.fintech.sales;

import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SalesController {

    private final SalesLeadService sales;
    private final PlatformServiceGate platformServices;

    public SalesController(SalesLeadService sales, PlatformServiceGate platformServices) {
        this.sales = sales;
        this.platformServices = platformServices;
    }

    @GetMapping("/api/v1/sales")
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal AuthPrincipal me,
                                                       @RequestParam(required = false) UUID retailerId,
                                                       @RequestParam(required = false) String channel) {
        platformServices.requireLeadSalesEnabled();
        return ApiResponse.ok(sales.list(me, retailerId, channel));
    }

    @GetMapping("/api/v1/sales/{id}")
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<Map<String, Object>> one(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        platformServices.requireLeadSalesEnabled();
        return ApiResponse.ok(sales.get(me, id));
    }

    @PostMapping("/api/v1/sales/{id}/utm-status")
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<Map<String, Object>> utmStatus(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        platformServices.requireLeadSalesEnabled();
        return ApiResponse.ok(sales.checkPaysprintUtmStatus(me, id));
    }

    @GetMapping("/api/v1/public/apply/{token}")
    public ApiResponse<Map<String, Object>> publicStatus(@PathVariable String token) {
        return ApiResponse.ok(sales.publicStatus(token));
    }

    @PostMapping("/api/v1/public/apply/{token}/start")
    public ApiResponse<Map<String, String>> start(@PathVariable String token) {
        return ApiResponse.ok(sales.start(token));
    }
}
