package com.fintech.identity;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
public class PartnerInquiryController {

    public record AssignHubRequest(@NotNull UUID hubAdminId) {}

    public record AssignDistributorRequest(@NotNull UUID distributorId) {}

    public record ConvertDistributorRequest(
            @NotBlank @Size(min = 8) String password,
            @NotNull UUID hubId) {}

    public record ConvertRetailerRequest(
            @NotBlank @Size(min = 8) String password,
            @NotBlank String city,
            @NotBlank String state,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "6-digit pincode required") String pincode) {}

    private final PartnerInquiryService inquiries;

    public PartnerInquiryController(PartnerInquiryService inquiries) {
        this.inquiries = inquiries;
    }

    @GetMapping("/api/v1/admin/inquiries")
    @PreAuthorize("hasAuthority('inquiries.manage')")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(inquiries.listForStaff());
    }

    @GetMapping("/api/v1/admin/inquiries/{id}")
    @PreAuthorize("hasAuthority('inquiries.manage')")
    public ApiResponse<Map<String, Object>> get(@PathVariable UUID id) {
        return ApiResponse.ok(inquiries.getForStaff(id));
    }

    @PostMapping("/api/v1/admin/inquiries/{id}/retire")
    @PreAuthorize("hasAuthority('inquiries.manage')")
    public ApiResponse<Map<String, Object>> retire(@PathVariable UUID id) {
        return ApiResponse.ok(inquiries.retire(id));
    }

    @PostMapping("/api/v1/admin/inquiries/{id}/not-interested")
    @PreAuthorize("hasAuthority('inquiries.manage')")
    public ApiResponse<Map<String, Object>> notInterested(@PathVariable UUID id) {
        return ApiResponse.ok(inquiries.notInterested(id));
    }

    @PostMapping("/api/v1/admin/inquiries/{id}/assign-hub")
    @PreAuthorize("hasAuthority('inquiries.manage')")
    public ApiResponse<Map<String, Object>> assignHub(@PathVariable UUID id,
                                                      @Valid @RequestBody AssignHubRequest req) {
        return ApiResponse.ok(inquiries.assignHubAdmin(id, req.hubAdminId()));
    }

    @PostMapping("/api/v1/admin/inquiries/{id}/assign-distributor")
    @PreAuthorize("hasAuthority('inquiries.manage')")
    public ApiResponse<Map<String, Object>> assignDistributor(@PathVariable UUID id,
                                                              @Valid @RequestBody AssignDistributorRequest req) {
        return ApiResponse.ok(inquiries.assignDistributor(id, req.distributorId()));
    }

    @PostMapping("/api/v1/admin/inquiries/{id}/convert-distributor")
    @PreAuthorize("hasAuthority('inquiries.manage')")
    public ApiResponse<Map<String, Object>> convertDistributor(@PathVariable UUID id,
                                                               @Valid @RequestBody ConvertDistributorRequest req) {
        return ApiResponse.ok(inquiries.convertDistributor(id, req.password(), req.hubId()));
    }

    @GetMapping("/api/v1/desk/inquiries")
    @PreAuthorize("hasAuthority('outlets.manage')")
    public ApiResponse<List<Map<String, Object>>> deskList(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(inquiries.listForDistributor(me));
    }

    @GetMapping("/api/v1/desk/inquiries/{id}")
    @PreAuthorize("hasAuthority('outlets.manage')")
    public ApiResponse<Map<String, Object>> deskGet(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(inquiries.getForDistributor(me, id));
    }

    @PostMapping("/api/v1/desk/inquiries/{id}/convert-retailer")
    @PreAuthorize("hasAuthority('outlets.manage')")
    public ApiResponse<Map<String, Object>> convertRetailer(@AuthenticationPrincipal AuthPrincipal me,
                                                            @PathVariable UUID id,
                                                            @Valid @RequestBody ConvertRetailerRequest req) {
        return ApiResponse.ok(inquiries.convertRetailer(me, id, req.password(), req.city(), req.state(), req.pincode()));
    }
}
