package com.fintech.hospicash;

import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/hospi-cash")
@PreAuthorize("hasAuthority('settings.manage')")
public class AdminHospiCashController {

    private final HospiCashService hospi;

    public AdminHospiCashController(HospiCashService hospi) {
        this.hospi = hospi;
    }

    @GetMapping("/pricing")
    public ApiResponse<Map<String, Object>> pricing() {
        return ApiResponse.ok(hospi.adminPricing());
    }

    @PutMapping("/pricing")
    public ApiResponse<Map<String, Object>> savePricing(@Valid @RequestBody PricingRequest body) {
        return ApiResponse.ok(hospi.savePricing(body.sellingPrice(), body.discount()));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String activation,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to,
                                                       @RequestParam(required = false) String q) {
        return ApiResponse.ok(hospi.adminList(activation, day(from), day(to), q));
    }

    @PostMapping("/{id}/vendor-payment")
    public ApiResponse<Map<String, Object>> vendorPayment(@PathVariable UUID id) {
        return ApiResponse.ok(hospi.markVendorPaid(id));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String activation,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String q) {
        byte[] body = hospi.exportActivation(activation, day(from), day(to), q);
        String status = activation == null || activation.isBlank() ? "pending" : activation.trim().toLowerCase();
        String start = from == null || from.isBlank() ? "today" : from.trim();
        String end = to == null || to.isBlank() ? start : to.trim();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"hospi-cash-" + status + "-" + start + "-" + end + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @PostMapping(value = "/recon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> recon(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(hospi.importRecon(file));
    }

    @GetMapping("/earnings")
    public ApiResponse<List<Map<String, Object>>> earnings() {
        return ApiResponse.ok(hospi.earnings());
    }

    @PostMapping("/earnings/{id}/settle")
    public ApiResponse<Map<String, Object>> settle(@PathVariable UUID id) {
        return ApiResponse.ok(hospi.settleEarning(id));
    }

    public record PricingRequest(@NotNull BigDecimal sellingPrice, @NotNull BigDecimal discount) {}

    private static LocalDate day(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DATE", "Use YYYY-MM-DD");
        }
    }
}
