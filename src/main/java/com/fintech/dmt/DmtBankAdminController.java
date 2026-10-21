package com.fintech.dmt;

import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/dmt-banks")
public class DmtBankAdminController {

    public record CreateBankRequest(@NotBlank String bankId, @NotBlank String bankName) {}

    public record ActiveRequest(@NotNull Boolean active) {}

    private final DmtBankListService banks;

    public DmtBankAdminController(DmtBankListService banks) {
        this.banks = banks;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) Boolean active,
                                                 @RequestParam(required = false) Integer limit,
                                                 @RequestParam(required = false) Integer offset) {
        return ApiResponse.ok(banks.list(q, active, limit, offset));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateBankRequest req) {
        return ApiResponse.ok(banks.create(req.bankId(), req.bankName()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> active(@PathVariable UUID id, @Valid @RequestBody ActiveRequest req) {
        return ApiResponse.ok(banks.setActive(id, req.active()));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(banks.upload(file));
    }
}
