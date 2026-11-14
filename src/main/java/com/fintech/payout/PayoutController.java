package com.fintech.payout;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    public record InitiatePayoutRequest(
            BigDecimal amount,
            String transferMode,
            String remarks,
            String latitude,
            String longitude
    ) {}

    private final PayoutService payouts;

    public PayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    @GetMapping("/eligibility")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> eligibility(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(payouts.checkEligibility(me));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> initiate(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody InitiatePayoutRequest body) {
        return ApiResponse.ok(payouts.initiatePayout(
                me,
                body.amount(),
                body.transferMode(),
                body.remarks(),
                body.latitude(),
                body.longitude()));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> history(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(payouts.history(me));
    }
}

