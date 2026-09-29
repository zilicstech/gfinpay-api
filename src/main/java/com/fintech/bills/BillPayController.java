package com.fintech.bills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bills")
public class BillPayController {

    public record FetchRequest(@NotNull Integer operatorId, @NotBlank String consumerNumber) {}

    public record PayRequest(
            @NotNull Integer operatorId,
            @NotBlank String consumerNumber,
            @NotNull @DecimalMin("1.00") BigDecimal amount,
            String customerName,
            @NotBlank @Pattern(regexp = "\\d{10}") String customerMobile,
            Map<String, Object> billFetch) {}

    private final BillPayService bills;
    private final ObjectMapper mapper;

    public BillPayController(BillPayService bills, ObjectMapper mapper) {
        this.bills = bills;
        this.mapper = mapper;
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAnyAuthority('bills.pay','dmt.initiate')")
    public ApiResponse<List<Map<String, String>>> categories() {
        return ApiResponse.ok(bills.categories());
    }

    @GetMapping("/operators")
    @PreAuthorize("hasAnyAuthority('bills.pay','dmt.initiate')")
    public ApiResponse<List<Map<String, Object>>> operators(@RequestParam String category) {
        return ApiResponse.ok(bills.operators(category));
    }

    @PostMapping("/fetch")
    @PreAuthorize("hasAnyAuthority('bills.pay','dmt.initiate')")
    public ApiResponse<Map<String, Object>> fetch(@Valid @RequestBody FetchRequest req) {
        return ApiResponse.ok(bills.fetch(req.operatorId(), req.consumerNumber()));
    }

    @PostMapping("/pay")
    @PreAuthorize("hasAnyAuthority('bills.pay','dmt.initiate')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pay(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @Valid @RequestBody PayRequest req) throws Exception {
        if (agentIdHeader != null && !agentIdHeader.isBlank() && !me.userId().toString().equals(agentIdHeader)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                    "X-Agent-Id does not match the authenticated user");
        }
        String canonical = mapper.writeValueAsString(req);
        BillPayService.PayResult result = bills.pay(me.userId(), idempotencyKey, req.operatorId(),
                req.consumerNumber(), req.amount(), req.customerName(), req.customerMobile(),
                req.billFetch(), canonical);
        ApiResponse<Map<String, Object>> body =
                result.replay() ? ApiResponse.replay(result.body()) : ApiResponse.ok(result.body());
        return ResponseEntity.status(HttpStatus.OK)
                .header("X-Idempotency-Replay", String.valueOf(result.replay()))
                .header("Location", "/api/v1/transactions/" + result.body().get("transactionId"))
                .body(body);
    }
}
