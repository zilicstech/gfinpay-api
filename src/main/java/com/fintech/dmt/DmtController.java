package com.fintech.dmt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.kyc.CustomerKycService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    public record CaptureKycRequest(
            @NotBlank String ovdType,
            @NotBlank String ovdLast4,
            @NotBlank String addressLine,
            String pan) {}

    public record OtpRequest(@NotBlank String purpose) {}

    public record OtpVerifyRequest(
            @NotBlank String purpose,
            @NotBlank @Pattern(regexp = "\\d{6}") String otp) {}

    public record AddBeneficiaryRequest(
            @NotBlank String name,
            @NotBlank String accountNumber,
            @NotBlank String ifsc) {}

    public record InitiateDmtRequest(
            @NotNull UUID senderId,
            @NotNull UUID beneficiaryId,
            @NotNull @DecimalMin("1.00") BigDecimal amount,
            @NotBlank @Pattern(regexp = "IMPS|NEFT") String transferMode,
            String consentOtp) {}

    private final DmtService dmtService;
    private final CustomerKycService kyc;
    private final ObjectMapper mapper;
    private final PlatformServiceGate services;

    public DmtController(DmtService dmtService, CustomerKycService kyc, ObjectMapper mapper,
                         PlatformServiceGate services) {
        this.dmtService = dmtService;
        this.kyc = kyc;
        this.mapper = mapper;
        this.services = services;
    }

    private void requireDmt() {
        services.requireEnabled(PlatformServiceGate.DMT);
    }

    @GetMapping("/senders")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> senderByMobile(@RequestParam String mobile) {
        return ApiResponse.ok(dmtService.findSenderByMobile(DmtService.digits(mobile)));
    }

    @GetMapping("/senders/{senderId}")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> senderById(@PathVariable UUID senderId) {
        return ApiResponse.ok(dmtService.getSender(senderId));
    }

    @PostMapping("/senders")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> registerSender(@AuthenticationPrincipal AuthPrincipal me,
                                                           @Valid @RequestBody RegisterSenderRequest req) {
        return ApiResponse.ok(dmtService.registerSender(DmtService.digits(req.mobile()), req.fullName().trim(), me.userId()));
    }

    @PatchMapping("/senders/{senderId}/kyc")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> captureKyc(@PathVariable UUID senderId,
                                                       @Valid @RequestBody CaptureKycRequest req) {
        requireDmt();
        return ApiResponse.ok(kyc.captureOvd(senderId, req.ovdType(), req.ovdLast4(), req.addressLine(), req.pan()));
    }

    @PostMapping("/senders/{senderId}/otp")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> sendOtp(@PathVariable UUID senderId,
                                                    @Valid @RequestBody OtpRequest req) {
        requireDmt();
        return ApiResponse.ok(kyc.sendOtp(senderId, req.purpose().trim().toUpperCase()));
    }

    @PostMapping("/senders/{senderId}/otp/verify")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> verifyOtp(@PathVariable UUID senderId,
                                                      @Valid @RequestBody OtpVerifyRequest req) {
        requireDmt();
        return ApiResponse.ok(kyc.verifyOtp(senderId, req.purpose().trim().toUpperCase(), req.otp()));
    }

    @PostMapping("/senders/{senderId}/beneficiaries")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<Map<String, Object>> addBeneficiary(@PathVariable UUID senderId,
                                                           @Valid @RequestBody AddBeneficiaryRequest req) {
        return ApiResponse.ok(dmtService.addBeneficiary(senderId, req.name(), req.accountNumber(), req.ifsc()));
    }

    @GetMapping("/senders/{senderId}/beneficiaries")
    @PreAuthorize("hasAuthority('senders.manage')")
    public ApiResponse<List<Map<String, Object>>> beneficiaries(@PathVariable UUID senderId) {
        return ApiResponse.ok(dmtService.listBeneficiaries(senderId));
    }

    /** Doc 05 contract: 202 Accepted, Idempotency-Key + X-Agent-Id headers, envelope body. */
    @PostMapping("/transactions")
    @PreAuthorize("hasAuthority('dmt.initiate')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initiate(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @Valid @RequestBody InitiateDmtRequest req) throws Exception {

        if (agentIdHeader != null && !agentIdHeader.isBlank() && !me.userId().toString().equals(agentIdHeader)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                    "X-Agent-Id does not match the authenticated user");
        }
        String canonical = mapper.writeValueAsString(req);
        DmtService.InitiationResult result = dmtService.initiate(
                me.userId(), idempotencyKey, req.senderId(), req.beneficiaryId(),
                req.amount(), req.transferMode(), req.consentOtp(), canonical);

        ApiResponse<Map<String, Object>> body =
                result.replay() ? ApiResponse.replay(result.body()) : ApiResponse.ok(result.body());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("X-Idempotency-Replay", String.valueOf(result.replay()))
                .header("Location", "/api/v1/transactions/" + result.body().get("transactionId"))
                .body(body);
    }
}
