package com.fintech.verification;

import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/verification")
public class VerificationController {

    public record BusinessRequest(
            String entityType,
            String legalName,
            String tradeName,
            String address,
            AddressFormatter.BusinessAddressInput businessAddress,
            Boolean sameAsPersonal,
            String signatoryName,
            String gstin,
            String udyam,
            String cin,
            String latitude,
            String longitude
    ) {}

    public record IndividualBusinessRequest(
            String locationSource,
            String latitude,
            String longitude,
            String shopName,
            String tradeName,
            AddressFormatter.BusinessAddressInput address,
            String email,
            String aadhaarNumber,
            String gstin
    ) {
        public IndividualBusinessRequest(
                String locationSource,
                String latitude,
                String longitude,
                String shopName,
                String tradeName,
                AddressFormatter.BusinessAddressInput address) {
            this(locationSource, latitude, longitude, shopName, tradeName, address, null, null, null);
        }
    }

    public record CompanyLookupRequest(String cin) {}

    public record CompanyBusinessRequest(
            String cin,
            String din,
            String shopName,
            String tradeName,
            String signatoryMobile,
            String latitude,
            String longitude,
            AddressFormatter.BusinessAddressInput address,
            String email,
            String aadhaarNumber,
            String gstin
    ) {
        public CompanyBusinessRequest(
                String cin,
                String din,
                String shopName,
                String tradeName,
                String signatoryMobile,
                String latitude,
                String longitude) {
            this(cin, din, shopName, tradeName, signatoryMobile, latitude, longitude, null, null, null, null);
        }
    }

    public record RegistryLookupRequest(String number, String gstin, String udyam, String cin) {}

    public record SaveAadhaarRequest(String aadhaarNumber) {}

    public record AgreementRequest(String signatoryName, String latitude, String longitude) {}

    public record BusinessReviewRequest(@NotNull Boolean approve, String note) {}

    public record FaceReviewRequest(@NotNull Boolean matched, String note) {}

    public record DigilockerDocReviewRequest(@NotNull Boolean approve, String note) {}

    public record CashRequest(String mode, String amount) {}

    public record PayoutRequest(String amount, String accountName, String accountNumber, String ifsc) {}

    private final VerificationService verification;
    private final DigilockerDocumentService digilockerDocuments;
    private final VerificationGate gate;
    private final PlatformServiceGate services;
    private final CashProvider cash;
    private final PayoutProvider payout;

    public VerificationController(
            VerificationService verification,
            DigilockerDocumentService digilockerDocuments,
            VerificationGate gate,
            PlatformServiceGate services,
            CashProvider cash,
            PayoutProvider payout) {
        this.verification = verification;
        this.digilockerDocuments = digilockerDocuments;
        this.gate = gate;
        this.services = services;
        this.cash = cash;
        this.payout = payout;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> mine(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(verification.status(me));
    }

    @PostMapping("/aadhaar")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> saveAadhaar(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody(required = false) SaveAadhaarRequest body) {
        return ApiResponse.ok(verification.saveFullAadhaar(me, body == null ? null : body.aadhaarNumber()));
    }

    @PostMapping("/business/gstin")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> verifyGstin(@AuthenticationPrincipal AuthPrincipal me,
                                                        @RequestBody RegistryLookupRequest body) {
        String val = body.gstin() != null ? body.gstin() : body.number();
        return ApiResponse.ok(verification.lookupGstin(me, val));
    }

    @PostMapping("/business/udyam")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> verifyUdyam(@AuthenticationPrincipal AuthPrincipal me,
                                                        @RequestBody RegistryLookupRequest body) {
        String val = body.udyam() != null ? body.udyam() : body.number();
        return ApiResponse.ok(verification.lookupUdyam(me, val));
    }

    @PostMapping("/business/cin")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> verifyCin(@AuthenticationPrincipal AuthPrincipal me,
                                                      @RequestBody RegistryLookupRequest body) {
        String val = body.cin() != null ? body.cin() : body.number();
        return ApiResponse.ok(verification.lookupCin(me, val));
    }

    @GetMapping("/business/kyc-location")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> kycLocation(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(verification.kycLocation(me));
    }

    @PostMapping("/business/individual")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> submitIndividual(@AuthenticationPrincipal AuthPrincipal me,
                                                             @RequestBody IndividualBusinessRequest body) {
        String name = (body.shopName() != null && !body.shopName().isBlank()) ? body.shopName() : body.tradeName();
        return ApiResponse.ok(verification.submitIndividualBusiness(
                me,
                body.locationSource(),
                body.latitude(),
                body.longitude(),
                name,
                body.address(),
                body.email(),
                body.aadhaarNumber(),
                body.gstin()));
    }

    @PostMapping("/business/company/lookup")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> lookupCompany(@AuthenticationPrincipal AuthPrincipal me,
                                                          @RequestBody CompanyLookupRequest body) {
        return ApiResponse.ok(verification.lookupCompanyDetails(me, body.cin()));
    }

    @PostMapping("/business/company")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> submitCompany(@AuthenticationPrincipal AuthPrincipal me,
                                                          @RequestBody CompanyBusinessRequest body) {
        String name = (body.shopName() != null && !body.shopName().isBlank()) ? body.shopName() : body.tradeName();
        return ApiResponse.ok(verification.submitCompanyBusiness(
                me,
                body.cin(),
                body.din(),
                name,
                body.signatoryMobile(),
                body.latitude(),
                body.longitude(),
                body.address(),
                body.email(),
                body.aadhaarNumber(),
                body.gstin()));
    }

    @PostMapping("/business/signatory/resend")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> resendSignatory(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(verification.resendSignatoryLink(me));
    }

    @PostMapping("/business/signatory/change")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> changeSignatory(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(verification.changeSignatory(me));
    }

    @PostMapping("/business")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> business(@AuthenticationPrincipal AuthPrincipal me,
                                                     @RequestBody BusinessRequest body) {
        return ApiResponse.ok(verification.saveBusiness(
                me,
                body.entityType(),
                body.legalName(),
                body.tradeName(),
                body.address(),
                body.businessAddress(),
                body.sameAsPersonal(),
                body.signatoryName(),
                body.gstin(),
                body.udyam(),
                body.cin(),
                body.latitude(),
                body.longitude()));
    }

    @PostMapping("/business/documents")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> document(@AuthenticationPrincipal AuthPrincipal me,
                                                     @RequestParam String docType,
                                                     @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(verification.addDocument(me, docType, file));
    }

    @PostMapping("/agreement")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> agreement(@AuthenticationPrincipal AuthPrincipal me,
                                                      @RequestBody AgreementRequest body) {
        return ApiResponse.ok(verification.signAgreement(me, body.signatoryName(), body.latitude(), body.longitude()));
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> forUser(@PathVariable UUID id) {
        return ApiResponse.ok(verification.statusFor(id));
    }

    @PostMapping("/users/{id}/face-review")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> reviewFace(@AuthenticationPrincipal AuthPrincipal me,
                                                       @PathVariable UUID id,
                                                       @Valid @RequestBody FaceReviewRequest body) {
        return ApiResponse.ok(verification.reviewFace(me, id, body.matched(), body.note()));
    }

    @PostMapping("/users/{id}/signatory/face-review")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> reviewSignatoryFace(@AuthenticationPrincipal AuthPrincipal me,
                                                                @PathVariable UUID id,
                                                                @Valid @RequestBody FaceReviewRequest body) {
        return ApiResponse.ok(verification.reviewSignatoryFace(me, id, body.matched(), body.note()));
    }

    @PostMapping("/users/{id}/personal-review")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> reviewPersonal(@AuthenticationPrincipal AuthPrincipal me,
                                                           @PathVariable UUID id,
                                                           @Valid @RequestBody BusinessReviewRequest body) {
        return ApiResponse.ok(verification.reviewPersonal(me, id, body.approve(), body.note()));
    }

    @PostMapping("/users/{id}/business-review")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<Map<String, Object>> reviewBusiness(@AuthenticationPrincipal AuthPrincipal me,
                                                           @PathVariable UUID id,
                                                           @Valid @RequestBody BusinessReviewRequest body) {
        return ApiResponse.ok(verification.reviewBusiness(me, id, body.approve(), body.note()));
    }

    @GetMapping("/users/{id}/digilocker-documents")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<List<Map<String, Object>>> listDigilockerDocuments(@PathVariable UUID id) {
        return ApiResponse.ok(digilockerDocuments.listDocumentsForUser(id));
    }

    @GetMapping("/users/{id}/digilocker-documents/{docId}/file")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ResponseEntity<byte[]> streamDigilockerDocument(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @PathVariable UUID docId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        DigilockerDocumentService.DecryptedDocument doc = digilockerDocuments.loadDecryptedDocument(
                me, id, docId, ip, userAgent);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(doc.mimeType()));
        headers.setContentDisposition(ContentDisposition.inline().filename(doc.filename()).build());
        headers.setCacheControl("no-store, private");
        headers.add("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(doc.bytes());
    }

    @PostMapping("/users/{id}/digilocker-documents/{docId}/review")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<List<Map<String, Object>>> reviewDigilockerDocument(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id,
            @PathVariable UUID docId,
            @Valid @RequestBody DigilockerDocReviewRequest body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(digilockerDocuments.reviewDocument(
                me, id, docId, body.approve(), body.note(), ip, userAgent));
    }

    @PostMapping("/users/{id}/digilocker-documents/refetch")
    @PreAuthorize("hasAuthority('users.onboard')")
    public ApiResponse<List<Map<String, Object>>> refetchDigilockerDocuments(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable UUID id) {
        return ApiResponse.ok(digilockerDocuments.refetchForUser(me, id));
    }

    @PostMapping("/cash")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> cash(@AuthenticationPrincipal AuthPrincipal me,
                                                 @RequestBody CashRequest body) {
        gate.requireBusiness(me.userId());
        String mode = body.mode() == null ? "" : body.mode().trim().toUpperCase();
        if (!"UPI".equals(mode) && !"AEPS".equals(mode)) {
            mode = "UPI";
        }
        services.requireEnabled("AEPS".equals(mode) ? PlatformServiceGate.AEPS : PlatformServiceGate.UPI_CASHOUT);
        cash.start(mode);
        return ApiResponse.ok(Map.of("mode", mode));
    }

    @PostMapping("/payout")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> payout(@AuthenticationPrincipal AuthPrincipal me,
                                                   @RequestBody PayoutRequest body) {
        gate.requireBusiness(me.userId());
        services.requireEnabled(PlatformServiceGate.PAYOUT);
        payout.execute();
        return ApiResponse.ok(Map.of("status", "NOT_CONFIGURED"));
    }
}
