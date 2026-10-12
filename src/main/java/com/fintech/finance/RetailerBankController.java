package com.fintech.finance;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet/banks")
public class RetailerBankController {

    public record BankRequest(
            @NotBlank String accountName,
            @NotBlank String accountNumber,
            @NotBlank String ifsc,
            @NotBlank String bankName) {}

    public record ActiveRequest(@NotNull Boolean active) {}

    private final RetailerBankService banks;

    public RetailerBankController(RetailerBankService banks) {
        this.banks = banks;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(banks.list(me.userId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> create(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Valid @RequestBody BankRequest body) {
        return ApiResponse.ok(banks.create(me, body.accountName(), body.accountNumber(), body.ifsc(), body.bankName()));
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> update(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody BankRequest body) {
        return ApiResponse.ok(banks.update(me, id, body.accountName(), body.accountNumber(), body.ifsc(), body.bankName()));
    }

    @PostMapping("/{id}/active")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> active(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody ActiveRequest body) {
        return ApiResponse.ok(banks.setActive(me, id, body.active()));
    }
}
