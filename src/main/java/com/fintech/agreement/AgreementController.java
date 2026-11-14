package com.fintech.agreement;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AgreementController {

    public record GenerateAgreementRequest(BigDecimal onboardingFee) {}

    public record VoidAgreementRequest(String reason) {}

    public record ReviewAgreementRequest(@NotNull Boolean approve, String note) {}

    public record StartEsignRequest(String latitude, String longitude) {}

    public record MockActionRequest(String action, String signerName) {}

    private final AgreementService agreements;

    public AgreementController(AgreementService agreements) {
        this.agreements = agreements;
    }

    // ─── Admin Endpoints ──────────────────────────────────────────────────────

    @GetMapping("/api/v1/agreements/users/{userId}")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> adminGetForUser(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID userId) {
        return ApiResponse.ok(agreements.getForAdmin(me, userId));
    }

    @PostMapping("/api/v1/agreements/users/{userId}/generate")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> adminGenerate(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID userId,
            @RequestBody(required = false) GenerateAgreementRequest body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        BigDecimal fee = body == null ? null : body.onboardingFee();
        return ApiResponse.ok(agreements.generate(me, userId, fee, ip, userAgent));
    }

    @PostMapping("/api/v1/agreements/{agreementId}/release")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> adminRelease(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID agreementId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(agreements.release(me, agreementId, ip, userAgent));
    }

    @PostMapping("/api/v1/agreements/{agreementId}/void")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> adminVoid(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID agreementId,
            @RequestBody VoidAgreementRequest body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(agreements.voidAgreement(me, agreementId, body == null ? null : body.reason(), ip, userAgent));
    }

    @PostMapping("/api/v1/agreements/{agreementId}/review")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> adminReview(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID agreementId,
            @Valid @RequestBody ReviewAgreementRequest body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(agreements.review(me, agreementId, body.approve(), body.note(), ip, userAgent));
    }

    @GetMapping("/api/v1/agreements/{agreementId}/file")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ResponseEntity<byte[]> adminFile(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID agreementId,
            @RequestParam(defaultValue = "unsigned") String variant,
            @RequestParam(defaultValue = "false") boolean download) {
        AgreementService.LoadedPdf pdf = agreements.loadFileForAdmin(me, agreementId, variant);
        return pdfResponse(pdf, download);
    }

    // ─── Partner (Retailer / Distributor) Endpoints ───────────────────────────

    @GetMapping("/api/v1/agreements/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> partnerMine(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(agreements.getForPartner(me));
    }

    @GetMapping("/api/v1/agreements/me/file")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> partnerFile(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue = "unsigned") String variant,
            @RequestParam(defaultValue = "false") boolean download,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        AgreementService.LoadedPdf pdf = agreements.loadFileForPartner(me, variant, ip, userAgent);
        return pdfResponse(pdf, download);
    }

    @PostMapping("/api/v1/agreements/me/esign/start")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> partnerStartEsign(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody(required = false) StartEsignRequest body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        String lat = body == null ? null : body.latitude();
        String lng = body == null ? null : body.longitude();
        return ApiResponse.ok(agreements.startEsign(me, lat, lng, ip, userAgent));
    }

    @PostMapping("/api/v1/agreements/me/esign/copied")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> partnerCopiedEsignLink(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(agreements.recordLinkCopied(me, ip, userAgent));
    }

    @PostMapping("/api/v1/agreements/me/esign/refresh")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> partnerRefreshEsign(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(agreements.refreshMyEsign(me, ip, userAgent));
    }

    @PostMapping("/api/v1/agreements/me/upload-signed")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> partnerUploadSigned(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String latitude,
            @RequestParam(required = false) String longitude,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(agreements.uploadSigned(me, file, latitude, longitude, ip, userAgent));
    }

    // ─── Public Return Status & Local/Dev Mock Endpoints ──────────────────────

    @GetMapping("/api/v1/public/esign/return-status")
    public ApiResponse<Map<String, Object>> publicReturnStatus(@RequestParam("a") UUID agreementId) {
        return ApiResponse.ok(agreements.publicReturnStatus(agreementId));
    }

    @GetMapping("/api/v1/public/esign/mock/{clientId}")
    public ApiResponse<Map<String, Object>> publicMockSession(@PathVariable String clientId) {
        return ApiResponse.ok(agreements.getMockSessionInfo(clientId));
    }

    @PostMapping("/api/v1/public/esign/mock/{clientId}/action")
    public ApiResponse<Map<String, Object>> publicMockAction(
            @PathVariable String clientId,
            @RequestBody(required = false) MockActionRequest body) {
        String action = body == null ? "complete" : body.action();
        String signerName = body == null ? null : body.signerName();
        return ApiResponse.ok(agreements.performMockAction(clientId, action, signerName));
    }

    private static ResponseEntity<byte[]> pdfResponse(AgreementService.LoadedPdf pdf, boolean download) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(pdf.filename())
                .build();
        headers.setContentDisposition(disposition);
        headers.setCacheControl("no-store, private");
        headers.add("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(pdf.bytes());
    }
}

