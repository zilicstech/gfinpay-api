package com.fintech.finance;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class PlatformBankController {

    public record ActiveRequest(@NotNull Boolean active) {}

    private final PlatformBankService banks;

    public PlatformBankController(PlatformBankService banks) {
        this.banks = banks;
    }

    @GetMapping("/api/v1/finance/collection-accounts")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> active() {
        return ApiResponse.ok(banks.listActive());
    }

    @GetMapping("/api/v1/admin/banks")
    @PreAuthorize("hasAuthority('banks.manage')")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(banks.listAll());
    }

    @PostMapping(value = "/api/v1/admin/banks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('banks.manage')")
    public ApiResponse<Map<String, Object>> create(@AuthenticationPrincipal AuthPrincipal me,
                                                   @RequestParam String accountName,
                                                   @RequestParam String accountNumber,
                                                   @RequestParam String ifsc,
                                                   @RequestParam String bankName,
                                                   @RequestParam(required = false) String upiId,
                                                   @RequestParam(defaultValue = "true") boolean active,
                                                   @RequestParam(required = false) MultipartFile qr) {
        return ApiResponse.ok(banks.create(me, accountName, accountNumber, ifsc, bankName, upiId, active, qr));
    }

    @PostMapping(value = "/api/v1/admin/banks/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('banks.manage')")
    public ApiResponse<Map<String, Object>> update(@PathVariable UUID id,
                                                   @RequestParam String accountName,
                                                   @RequestParam String accountNumber,
                                                   @RequestParam String ifsc,
                                                   @RequestParam String bankName,
                                                   @RequestParam(required = false) String upiId,
                                                   @RequestParam(required = false) MultipartFile qr) {
        return ApiResponse.ok(banks.update(id, accountName, accountNumber, ifsc, bankName, upiId, qr));
    }

    @PostMapping("/api/v1/admin/banks/{id}/active")
    @PreAuthorize("hasAuthority('banks.manage')")
    public ApiResponse<Map<String, Object>> active(@PathVariable UUID id, @Valid @RequestBody ActiveRequest req) {
        return ApiResponse.ok(banks.setActive(id, req.active()));
    }
}
