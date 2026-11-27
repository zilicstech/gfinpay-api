package com.fintech.aeps;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/aeps")
public class AepsController {

    public record AepsOutletLoginBody(
            String pidXml,
            String captureType,
            String latitude,
            String longitude
    ) {}

    public record AepsTxnOtpBody(
            String bankIin,
            String aadhaarNumber,
            String customerMobile,
            Integer amount,
            String latitude,
            String longitude
    ) {}

    public record AepsTransactRequest(
            String mode,
            String aadhaarNumber,
            String bankIin,
            String customerMobile,
            Integer amount,
            String pidXml,
            String captureType,
            UUID otpSessionId,
            String latitude,
            String longitude
    ) {}

    private final AepsService aeps;

    public AepsController(AepsService aeps) {
        this.aeps = aeps;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> status(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(aeps.getStatus(me, refresh));
    }

    @PostMapping("/outlet-login")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> outletLogin(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody AepsOutletLoginBody body) {
        return ApiResponse.ok(aeps.submitOutletLogin(
                me,
                body.pidXml(),
                body.captureType(),
                body.latitude(),
                body.longitude()));
    }

    @GetMapping("/banks")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> banks(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(aeps.listBanks(me));
    }

    @PostMapping("/transaction-otp")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> requestTransactionOtp(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody AepsTxnOtpBody body) {
        return ApiResponse.ok(aeps.requestTransactionOtp(
                me,
                body.bankIin(),
                body.aadhaarNumber(),
                body.customerMobile(),
                body.amount(),
                body.latitude(),
                body.longitude()));
    }

    @PostMapping("/transact")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> transact(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody AepsTransactRequest body) {
        return ApiResponse.ok(aeps.transact(
                me,
                body.mode(),
                body.aadhaarNumber(),
                body.bankIin(),
                body.customerMobile(),
                body.amount(),
                body.pidXml(),
                body.captureType(),
                body.otpSessionId(),
                body.latitude(),
                body.longitude()));
    }

    @PostMapping("/transactions/{id}/refresh")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> refresh(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id) {
        return ApiResponse.ok(aeps.refreshTransaction(me, id));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> history(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(aeps.history(me));
    }
}
