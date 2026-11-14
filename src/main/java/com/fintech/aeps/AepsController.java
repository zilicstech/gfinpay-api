package com.fintech.aeps;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
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
@RequestMapping("/api/v1/aeps")
public class AepsController {

    public record AepsTransactRequest(
            String mode,
            String aadhaarNumber,
            String bankIin,
            String customerMobile,
            Integer amount,
            String pidXml,
            String latitude,
            String longitude
    ) {}

    private final AepsService aeps;

    public AepsController(AepsService aeps) {
        this.aeps = aeps;
    }

    @GetMapping("/banks")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, String>>> banks() {
        return ApiResponse.ok(aeps.listBanks());
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
                body.latitude(),
                body.longitude()));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> history(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(aeps.history(me));
    }
}

