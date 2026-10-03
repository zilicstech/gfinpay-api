package com.fintech.vendor;

import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicVendorController {

    private final VendorService vendors;

    public PublicVendorController(VendorService vendors) {
        this.vendors = vendors;
    }

    public record MintAffiliateRequest(@NotBlank String employeeName, @NotBlank String employeeCode) {}

    public record GenerateLinkRequest(
            @NotBlank String catalogItemId,
            @NotBlank String customerName,
            @NotBlank String mobile,
            @NotBlank String city,
            @NotBlank String state,
            String pincode) {}

    @GetMapping("/vendors/{code}")
    public ApiResponse<Map<String, Object>> vendor(@PathVariable String code) {
        return ApiResponse.ok(vendors.publicVendor(code));
    }

    @PostMapping("/vendors/{code}/affiliates")
    public ApiResponse<Map<String, Object>> mintAffiliate(@PathVariable String code,
                                                          @Valid @RequestBody MintAffiliateRequest req) {
        return ApiResponse.ok(vendors.mintAffiliateLink(code, req.employeeName(), req.employeeCode()));
    }

    @GetMapping("/affiliates/{token}")
    public ApiResponse<Map<String, Object>> affiliate(@PathVariable String token) {
        return ApiResponse.ok(vendors.publicAffiliate(token));
    }

    @PostMapping("/affiliates/{token}/generate-link")
    public ApiResponse<Map<String, Object>> generateLink(@PathVariable String token,
                                                         @Valid @RequestBody GenerateLinkRequest req) {
        return ApiResponse.ok(vendors.generateCustomerCardLink(
                token,
                req.customerName(),
                req.mobile(),
                req.city(),
                req.state(),
                req.pincode(),
                UUID.fromString(req.catalogItemId())));
    }
}
