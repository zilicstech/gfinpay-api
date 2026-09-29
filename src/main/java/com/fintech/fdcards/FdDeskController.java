package com.fintech.fdcards;

import com.fintech.platform.web.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/desk")
public class FdDeskController {

    private final FdProviderGate gate;

    public FdDeskController(FdProviderGate gate) {
        this.gate = gate;
    }

    @GetMapping("/fd-providers")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> fdProviders() {
        return ApiResponse.ok(gate.listEnabledForDesk());
    }
}
