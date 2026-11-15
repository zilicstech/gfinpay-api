package com.fintech.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.AdminPlatformService;
import com.fintech.platform.idempotency.ExternalRefs;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {

    public record NotesRequest(String notes) {}

    private final FinanceService finance;
    private final AdminPlatformService admin;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public FinanceController(FinanceService finance, AdminPlatformService admin,
                             IdempotencyService idempotency, ObjectMapper mapper) {
        this.finance = finance;
        this.admin = admin;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @PostMapping(value = "/activation-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> submitActivation(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(required = false) UUID retailerId,
            @RequestParam BigDecimal amount,
            @RequestParam String utr,
            @RequestParam(required = false) String remarks,
            @RequestParam("proof") MultipartFile proof) {
        return ApiResponse.ok(finance.submit(me, FinanceService.ACTIVATION, retailerId, amount, utr, remarks, proof));
    }

    @PostMapping(value = "/wallet-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> submitWallet(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false) UUID retailerId,
            @RequestParam BigDecimal amount,
            @RequestParam String utr,
            @RequestParam UUID collectionAccountId,
            @RequestParam String transferMode,
            @RequestParam(required = false) String remarks,
            @RequestParam("proof") MultipartFile proof) {
        String key = ExternalRefs.requireKey(idempotencyKey);
        String canonical = amount.toPlainString() + "|" + utr + "|" + collectionAccountId + "|" + transferMode + "|" + retailerId;
        IdempotencyService.Claim claim = idempotency.claim(me.userId(), key, canonical);
        if (claim.replay()) {
            try {
                return ApiResponse.ok(mapper.readValue(claim.responseBody(), new TypeReference<>() {}));
            } catch (Exception e) {
                throw ApiException.of(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY", "Stored response could not be read");
            }
        }
        Map<String, Object> result = finance.submit(me, FinanceService.WALLET_TOPUP, retailerId, amount, utr, remarks, proof,
                collectionAccountId, transferMode);
        try {
            idempotency.complete(claim.keyId(), 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "IDEMPOTENCY_STORE", "Could not store the wallet request");
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/requests")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> list(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID retailerId,
            @RequestParam(required = false) UUID auditorId) {
        return ApiResponse.ok(finance.list(me, kind, status, retailerId, auditorId));
    }

    @GetMapping("/requests/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> one(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(finance.get(me, id));
    }

    @GetMapping("/requests/{id}/proof")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> proof(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return ApiResponse.ok(finance.proofUrl(me, id));
    }

    @PostMapping("/requests/{id}/receive")
    @PreAuthorize("hasAuthority('finance.review')")
    public ApiResponse<Map<String, Object>> receive(@AuthenticationPrincipal AuthPrincipal me,
                                                    @PathVariable UUID id,
                                                    @RequestBody(required = false) NotesRequest req) {
        return ApiResponse.ok(finance.receive(me, id, req == null ? null : req.notes()));
    }

    @PostMapping(value = "/requests/{id}/transfer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('finance.review')")
    public ApiResponse<Map<String, Object>> transfer(@AuthenticationPrincipal AuthPrincipal me,
                                                     @PathVariable UUID id,
                                                     @RequestParam String utr,
                                                     @RequestParam UUID collectionAccountId,
                                                     @RequestParam(required = false) String notes,
                                                     @RequestParam("proof") MultipartFile proof) {
        return ApiResponse.ok(finance.transferWithdrawal(me, id, utr, notes, collectionAccountId, proof));
    }

    @PostMapping("/requests/{id}/reject")
    @PreAuthorize("hasAuthority('finance.review')")
    public ApiResponse<Map<String, Object>> reject(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable UUID id,
                                                   @RequestBody(required = false) NotesRequest req) {
        String notes = req == null ? null : req.notes();
        if (notes == null || notes.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOTES_REQUIRED",
                    "Add a reason when rejecting a payment");
        }
        return ApiResponse.ok(finance.reject(me, id, notes));
    }

    @PostMapping("/retailers/{id}/activate")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> activate(@AuthenticationPrincipal AuthPrincipal me,
                                                     @PathVariable UUID id) {
        finance.activateRetailer(me, id);
        return ApiResponse.ok(admin.getUser(id));
    }
}
