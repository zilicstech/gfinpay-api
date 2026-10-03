package com.fintech.vendor;

import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/vendors")
public class AdminVendorController {

    private final VendorService vendors;

    public AdminVendorController(VendorService vendors) {
        this.vendors = vendors;
    }

    public record CreateVendorRequest(
            @NotBlank String fullName,
            @NotBlank String mobile,
            String email,
            @NotNull UUID hubId) {}

    @GetMapping
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(vendors.listAdmin());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> one(@PathVariable UUID id) {
        return ApiResponse.ok(vendors.getAdmin(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateVendorRequest req) {
        return ApiResponse.ok(vendors.create(req.fullName(), req.mobile(), req.email(), req.hubId()));
    }
}
