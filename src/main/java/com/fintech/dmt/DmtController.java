package com.fintech.dmt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.PlatformServiceGate;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dmt")
public class DmtController {

    public record RegisterSenderRequest(
            @NotBlank @Pattern(regexp = "\\d{10}") String mobile,
            @NotBlank String fullName) {}

    public record BeneOtpRequest(
            @NotBlank String name,
            @NotBlank String accountNumber,
            @NotBlank String ifsc,
            @NotBlank String bankId) {}

    public record AddBeneRequest(@NotNull UUID sessionId, @NotBlank String otp) {}

    public record TxnOtpRequest(
            @NotNull UUID senderId,
            @NotNull UUID beneficiaryId,
            @NotNull @DecimalMin("1.00") BigDecimal amount,
            @NotBlank @Pattern(regexp = "IMPS|NEFT") String transferMode) {}

    public record ProcessRequest(@NotNull UUID sessionId, @NotBlank String otp) {}

    public record RefundRequest(@NotBlank String otp) {}

    private final DmtService dmtService;
    private final DmtCasaService casa;
    private final DmtBankListService banks;
    private final ObjectMapper mapper;
    private final PlatformServiceGate services;

    public DmtController(DmtService dmtService, DmtCasaService casa, DmtBankListService banks,
                         ObjectMapper mapper, PlatformServiceGate services) {
        this.dmtService = dmtService;
        this.casa = casa;
        this.banks = banks;
        this.mapper = mapper;
        this.services = services;
    }

    private void requireDmt() {
        services.requireEnabled(PlatformServiceGate.DMT);
    }

    @GetMapping("/banks")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<List<Map<String, Object>>> banks(@RequestParam(required = false) String q) {
        requireDmt();
        return ApiResponse.ok(banks.active(q, 30));
    }

    @GetMapping("/remitter")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> remitter(@AuthenticationPrincipal AuthPrincipal me,
                                                     @RequestParam String mobile) {
        return ApiResponse.ok(casa.queryRemitter(me, mobile));
    }

    @GetMapping("/senders")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> senderByMobile(@AuthenticationPrincipal AuthPrincipal me,
                                                           @RequestParam String mobile) {
        return ApiResponse.ok(dmtService.findSenderByMobile(DmtService.digits(mobile), me.userId()));
    }

    @GetMapping("/senders/{senderId}")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> senderById(@AuthenticationPrincipal AuthPrincipal me,
                                                       @PathVariable UUID senderId) {
        return ApiResponse.ok(dmtService.getSender(senderId, me.userId()));
    }

    @PostMapping("/senders")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> registerSender(@AuthenticationPrincipal AuthPrincipal me,
                                                           @Valid @RequestBody RegisterSenderRequest req) {
        return ApiResponse.ok(dmtService.registerSender(DmtService.digits(req.mobile()), req.fullName().trim(), me.userId()));
    }

    @PostMapping("/senders/{senderId}/beneficiaries/otp")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> beneOtp(@AuthenticationPrincipal AuthPrincipal me,
                                                    @PathVariable UUID senderId,
                                                    @Valid @RequestBody BeneOtpRequest req) {
        return ApiResponse.ok(casa.sendBeneOtp(me, senderId, req.name(), req.accountNumber(), req.ifsc(), req.bankId()));
    }

    @PostMapping("/senders/{senderId}/beneficiaries")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> addBeneficiary(@AuthenticationPrincipal AuthPrincipal me,
                                                           @PathVariable UUID senderId,
                                                           @Valid @RequestBody AddBeneRequest req) {
        return ApiResponse.ok(casa.addBeneficiary(me, senderId, req.sessionId(), req.otp()));
    }

    @GetMapping("/senders/{senderId}/beneficiaries")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<List<Map<String, Object>>> beneficiaries(@AuthenticationPrincipal AuthPrincipal me,
                                                                @PathVariable UUID senderId) {
        return ApiResponse.ok(casa.beneficiaries(me, senderId));
    }

    @PostMapping("/beneficiaries/{id}/verify")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> verifyBeneficiary(@AuthenticationPrincipal AuthPrincipal me,
                                                              @PathVariable UUID id) {
        return ApiResponse.ok(casa.verifyBeneficiary(me, id));
    }

    @DeleteMapping("/beneficiaries/{id}")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> deleteBeneficiary(@AuthenticationPrincipal AuthPrincipal me,
                                                              @PathVariable UUID id) {
        return ApiResponse.ok(casa.deleteBeneficiary(me, id));
    }

    @PostMapping("/transactions/otp")
    @PreAuthorize("hasAuthority('dmt.initiate')")
    public ApiResponse<Map<String, Object>> txnOtp(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Valid @RequestBody TxnOtpRequest req) {
        return ApiResponse.ok(casa.sendTxnOtp(me, req.senderId(), req.beneficiaryId(), req.amount(), req.transferMode()));
    }

    @PostMapping("/transactions")
    @PreAuthorize("hasAuthority('dmt.initiate')")
    public ApiResponse<Map<String, Object>> process(@AuthenticationPrincipal AuthPrincipal me,
                                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                    @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
                                                    @Valid @RequestBody ProcessRequest req) throws Exception {
        if (agentIdHeader != null && !agentIdHeader.isBlank() && !me.userId().toString().equals(agentIdHeader)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                    "X-Agent-Id does not match the authenticated user");
        }
        return ApiResponse.ok(casa.process(me, req.sessionId(), req.otp(), idempotencyKey, mapper.writeValueAsString(req)));
    }

    @GetMapping("/transactions/{txnId}/status")
    @PreAuthorize("hasAuthority('dmt.initiate')")
    public ApiResponse<Map<String, Object>> status(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID txnId) {
        return ApiResponse.ok(casa.status(me, txnId));
    }

    @PostMapping("/transactions/{txnId}/refund/otp")
    @PreAuthorize("hasAuthority('dmt.initiate')")
    public ApiResponse<Map<String, Object>> refundOtp(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID txnId) {
        return ApiResponse.ok(casa.refundOtp(me, txnId));
    }

    @PostMapping("/transactions/{txnId}/refund")
    @PreAuthorize("hasAuthority('dmt.initiate')")
    public ApiResponse<Map<String, Object>> refund(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable UUID txnId,
                                                   @Valid @RequestBody RefundRequest req) {
        return ApiResponse.ok(casa.refundClaim(me, txnId, req.otp()));
    }
}
