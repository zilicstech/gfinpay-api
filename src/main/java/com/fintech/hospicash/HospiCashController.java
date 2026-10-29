package com.fintech.hospicash;

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
@RequestMapping("/api/v1/hospi-cash")
public class HospiCashController {

    private final HospiCashService hospi;

    public HospiCashController(HospiCashService hospi) {
        this.hospi = hospi;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sales.create')")
    public ApiResponse<Map<String, Object>> sell(@AuthenticationPrincipal AuthPrincipal me,
                                                 @RequestBody HospiCashService.Capture capture) {
        return ApiResponse.ok(hospi.sell(me, capture));
    }

    @GetMapping("/offer")
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<Map<String, Object>> offer() {
        return ApiResponse.ok(hospi.offer());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal AuthPrincipal me,
                                                       @RequestParam(required = false) String state) {
        return ApiResponse.ok(hospi.list(me, state));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<Map<String, Object>> one(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(hospi.get(me, id));
    }
}
