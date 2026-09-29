package com.fintech.cashout;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cashout")
public class CashoutController {

    public record StartUpiRequest(
            @NotNull @DecimalMin("1.00") BigDecimal amount,
            @NotBlank String customerName,
            @NotBlank @Pattern(regexp = "\\d{10}") String customerMobile) {}

    private final CashoutService cashout;

    public CashoutController(CashoutService cashout) {
        this.cashout = cashout;
    }

    @PostMapping("/upi")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> start(@AuthenticationPrincipal AuthPrincipal me,
                                                  @Valid @RequestBody StartUpiRequest req) {
        return ApiResponse.ok(cashout.startUpi(me, req.amount(), req.customerName(), req.customerMobile()));
    }

    @GetMapping("/upi/{id}")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(cashout.get(me, id));
    }

    @PostMapping("/upi/{id}/paid")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> paid(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(cashout.markPaid(me, id));
    }

    @PostMapping("/upi/{id}/dispense")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> dispense(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(cashout.dispense(me, id));
    }
}
