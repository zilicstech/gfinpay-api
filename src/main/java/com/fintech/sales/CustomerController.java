package com.fintech.sales;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    public record CustomerRequest(
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "\\d{10}") String mobile,
            @NotBlank String city,
            @NotBlank String state,
            @NotBlank @Pattern(regexp = "\\d{6}") String pincode) {}

    public record UpdateRequest(
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "\\d{10}") String mobile,
            @NotBlank String city,
            @NotBlank String state,
            @NotBlank @Pattern(regexp = "\\d{6}") String pincode) {}

    public record LeadRequest(@NotNull UUID catalogItemId, @NotNull @Positive BigDecimal budget) {}

    public record EligibilityRequest(@NotBlank String categoryCode, @NotNull @Positive BigDecimal budget) {}

    public record EkycStartRequest(@NotBlank String ovdType, @NotBlank String ovdLast4) {}

    public record EkycVerifyRequest(@NotBlank @Pattern(regexp = "\\d{6}") String otp) {}

    private final CustomerService customers;
    private final CustomerEkycService ekyc;
    private final ProductEligibilityService eligibility;
    private final SalesLeadService sales;

    public CustomerController(CustomerService customers, CustomerEkycService ekyc,
                              ProductEligibilityService eligibility, SalesLeadService sales) {
        this.customers = customers;
        this.ekyc = ekyc;
        this.eligibility = eligibility;
        this.sales = sales;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal AuthPrincipal me,
                                                       @RequestParam(required = false) String mobile,
                                                       @RequestParam(required = false) UUID retailerId,
                                                       @RequestParam(required = false) UUID distributorId) {
        return ApiResponse.ok(customers.list(me, mobile, retailerId, distributorId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sales.create')")
    public ApiResponse<Map<String, Object>> create(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Valid @RequestBody CustomerRequest req) {
        return ApiResponse.ok(customers.create(me, req.fullName(), req.mobile(), req.city(), req.state(), req.pincode()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('sales.view')")
    public ApiResponse<Map<String, Object>> one(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(customers.get(me, id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('sales.create')")
    public ApiResponse<Map<String, Object>> update(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody UpdateRequest req) {
        return ApiResponse.ok(customers.update(me, id, req.fullName(), req.mobile(), req.city(), req.state(), req.pincode()));
    }

    @PostMapping("/{id}/ekyc")
    @PreAuthorize("hasAuthority('sales.create')")
    public ApiResponse<Map<String, Object>> startEkyc(@AuthenticationPrincipal AuthPrincipal me,
                                                      @PathVariable UUID id,
                                                      @Valid @RequestBody EkycStartRequest req) {
        return ApiResponse.ok(ekyc.start(me, id, req.ovdType(), req.ovdLast4()));
    }

    @PostMapping("/{id}/ekyc/verify")
    @PreAuthorize("hasAuthority('sales.create')")
    public ApiResponse<Map<String, Object>> verifyEkyc(@AuthenticationPrincipal AuthPrincipal me,
                                                       @PathVariable UUID id,
                                                       @Valid @RequestBody EkycVerifyRequest req) {
        return ApiResponse.ok(ekyc.verify(me, id, req.otp()));
    }

    @PostMapping("/{id}/eligibility")
    @PreAuthorize("hasAuthority('sales.create')")
    public ApiResponse<Map<String, Object>> eligibility(@AuthenticationPrincipal AuthPrincipal me,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody EligibilityRequest req) {
        return ApiResponse.ok(eligibility.check(me, id, req.categoryCode(), req.budget()));
    }

    @PostMapping("/{id}/leads")
    @PreAuthorize("hasAuthority('sales.create')")
    public ApiResponse<Map<String, Object>> lead(@AuthenticationPrincipal AuthPrincipal me,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody LeadRequest req) {
        return ApiResponse.ok(sales.create(me, id, req.catalogItemId(), req.budget()));
    }
}
