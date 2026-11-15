package com.fintech.cashout;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cashout")
public class CashoutController {

    public record GenerateUpiQrRequest(
            @NotNull @Min(1) Integer amount,
            @NotBlank String customerMobile,
            String customerName,
            String latitude,
            String longitude
    ) {}

    private final CashoutService cashout;

    public CashoutController(CashoutService cashout) {
        this.cashout = cashout;
    }

    @GetMapping("/upi/limits")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> limits(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(cashout.limits(me));
    }

    @PostMapping("/upi/generate-qr")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> generateQr(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody GenerateUpiQrRequest body) {
        return ApiResponse.ok(cashout.generateQr(
                me,
                body.amount(),
                body.customerMobile(),
                body.customerName(),
                body.latitude(),
                body.longitude(),
                idempotencyKey));
    }

    @GetMapping("/upi/latest")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> latest(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(cashout.latest(me));
    }

    @GetMapping("/upi/history")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> history(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(cashout.history(me));
    }

    @GetMapping("/upi/{id}")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(cashout.get(me, id));
    }

    @PostMapping("/upi/{id}/refresh")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> refresh(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(cashout.refresh(me, id));
    }

    @PostMapping("/upi/{id}/dispense")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> dispense(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(cashout.dispense(me, id));
    }
}
