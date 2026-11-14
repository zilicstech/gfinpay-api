package com.fintech.agreement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.agreement.esign.MockEsignClient;
import com.fintech.finance.RetailerBankService;
import com.fintech.onboarding.InviteService;
import com.fintech.onboarding.PublicOnboardingService;
import com.fintech.onboarding.SignatoryKycService;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.sms.MockSmsSender;
import com.fintech.verification.DigilockerDocumentService;
import com.fintech.verification.VerificationGate;
import com.fintech.verification.VerificationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = {
        "app.sms.provider=mock",
        "app.surepass.mode=mock",
        "app.esign.mode=mock",
        "app.esign.status-check-throttle-seconds=20"
})
@ActiveProfiles("test")
class AgreementFlowTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATASOURCE_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DATASOURCE_USERNAME", "postgres"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DATASOURCE_PASSWORD", ""));
            return;
        }
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired InviteService inviteService;
    @Autowired PublicOnboardingService publicOnboarding;
    @Autowired SignatoryKycService signatoryKycService;
    @Autowired VerificationService verificationService;
    @Autowired DigilockerDocumentService digilockerDocumentService;
    @Autowired RetailerBankService retailerBankService;
    @Autowired AgreementService agreementService;
    @Autowired MockEsignClient mockEsignClient;
    @Autowired VerificationGate verificationGate;
    @Autowired MockSmsSender smsSender;

    private AuthPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        superAdmin = new AuthPrincipal(adminId, "Test Super Admin", "SUPER_ADMIN", null);
        setAuth(superAdmin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuth(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retailerIndividualFlow_generateBlockedBeforeBusinessVerified_draftReleasePhysicalRejectAndEsignUnlock() throws Exception {
        String suffix = String.valueOf(1000 + (int) (Math.random() * 8999));
        UUID retailerId = onboardAndApprovePersonal("981113" + suffix, "RAJESH KUMAR SHARMA", "RETAILER");
        AuthPrincipal retailer = new AuthPrincipal(retailerId, "RAJESH KUMAR SHARMA", "RETAILER", null, "FULL");

        // Verify bank account
        setAuth(retailer);
        Map<String, Object> bank = retailerBankService.create(
                retailer, "RAJESH KUMAR SHARMA", "123456789012", "HDFC0001234", "HDFC Bank");
        retailerBankService.verify(retailer, (UUID) bank.get("id"));

        // Retailer submits individual business registration (status = UNDER_REVIEW)
        verificationService.submitIndividualBusiness(
                retailer, "KYC_LOCATION", null, null, "Sharma FinTech Kendra", null);

        // 1. Admin CANNOT generate agreement before business is BUSINESS_VERIFIED
        setAuth(superAdmin);
        ApiException blocked = assertThrows(ApiException.class, () ->
                agreementService.generate(superAdmin, retailerId, null, "127.0.0.1", "JUnit"));
        assertEquals("BUSINESS_NOT_VERIFIED", blocked.getCode());

        // 2. Admin approves business registration -> BUSINESS_VERIFIED
        Map<String, Object> bizApproved = verificationService.reviewBusiness(
                superAdmin, retailerId, true, "Business location and Aadhaar verified");
        assertEquals("BUSINESS_VERIFIED", bizApproved.get("businessStatus"));
        assertEquals(false, bizApproved.get("servicesUnlocked"), "Services must remain locked until MSA is signed");

        // VerificationGate blocks services with AGREEMENT_SIGNATURE_REQUIRED
        ApiException gateBlocked = assertThrows(ApiException.class, () -> verificationGate.requireBusiness(retailerId));
        assertEquals("AGREEMENT_SIGNATURE_REQUIRED", gateBlocked.getCode());

        // 3. Admin generates Retailer MSA -> status = DRAFT
        Map<String, Object> genResult = agreementService.generate(superAdmin, retailerId, null, "127.0.0.1", "JUnit");
        Map<String, Object> agreement = (Map<String, Object>) genResult.get("agreement");
        assertNotNull(agreement);
        UUID agreementId = (UUID) agreement.get("id");
        String agreementNo = String.valueOf(agreement.get("agreementNo"));
        assertTrue(agreementNo.startsWith("GFP-MSA-"));
        assertEquals("RETAILER_MSA", agreement.get("agreementType"));
        assertEquals("DRAFT", agreement.get("status"));
        assertEquals("NIL", agreement.get("onboardingFeeDisplay"));
        assertEquals(true, agreement.get("signatoryIsSelf"));
        assertEquals("SIGN", agreement.get("esignMode"));

        // Verify generated PDF text using PDFBox
        AgreementService.LoadedPdf draftPdf = agreementService.loadFileForAdmin(superAdmin, agreementId, "unsigned");
        String pdfText = extractPdfText(draftPdf.bytes());
        assertTrue(pdfText.contains("MASTER SERVICE AGREEMENT"), "MSA title must be present");
        assertTrue(pdfText.contains("Retailer MSA (retailer-msa-v1)"), "Retailer MSA category must be present");
        assertTrue(pdfText.contains("GATEWAYLINE FINTECH PRIVATE LIMITED"), "First Party name must be present");
        assertTrue(pdfText.contains("Sharma FinTech Kendra") || pdfText.contains("RAJESH KUMAR SHARMA"),
                "Second Party name must be populated");
        assertTrue(pdfText.contains("ANNEXURE A"), "Annexure A must be present");

        // 4. While in DRAFT, Retailer CANNOT see or download the agreement
        setAuth(retailer);
        Map<String, Object> partnerDraftView = agreementService.getForPartner(retailer);
        assertEquals(false, partnerDraftView.get("available"));
        assertEquals("NOT_RELEASED", partnerDraftView.get("status"));
        assertNull(partnerDraftView.get("agreement"));
        assertThrows(ApiException.class, () ->
                agreementService.loadFileForPartner(retailer, "unsigned", "127.0.0.1", "JUnit"));

        // 5. Admin releases the agreement -> status = RELEASED
        setAuth(superAdmin);
        Map<String, Object> releasedResult = agreementService.release(superAdmin, agreementId, "127.0.0.1", "JUnit");
        Map<String, Object> releasedAg = (Map<String, Object>) releasedResult.get("agreement");
        assertEquals("RELEASED", releasedAg.get("status"));

        // Retailer can now view & download the released agreement
        setAuth(retailer);
        Map<String, Object> partnerReleasedView = agreementService.getForPartner(retailer);
        assertEquals(true, partnerReleasedView.get("available"));
        assertEquals("RELEASED", partnerReleasedView.get("status"));
        AgreementService.LoadedPdf partnerPdf = agreementService.loadFileForPartner(retailer, "unsigned", "127.0.0.1", "JUnit");
        assertTrue(partnerPdf.bytes().length > 1000);

        // 6. Option A: Retailer uploads wet-ink signed PDF -> UNDER_REVIEW
        MockMultipartFile wetInkPdf = new MockMultipartFile(
                "file", "signed-msa.pdf", "application/pdf", draftPdf.bytes());
        Map<String, Object> afterUpload = agreementService.uploadSigned(
                retailer, wetInkPdf, "19.0760", "72.8777", "127.0.0.1", "JUnit");
        assertEquals("UNDER_REVIEW", afterUpload.get("status"));

        // Admin rejects physical upload without note -> fails; with note -> returns to RELEASED
        setAuth(superAdmin);
        assertThrows(ApiException.class, () ->
                agreementService.review(superAdmin, agreementId, false, "  ", "127.0.0.1", "JUnit"));
        Map<String, Object> afterReject = agreementService.review(
                superAdmin, agreementId, false, "Missing stamp on page 8", "127.0.0.1", "JUnit");
        Map<String, Object> rejectedAg = (Map<String, Object>) afterReject.get("agreement");
        assertEquals("RELEASED", rejectedAg.get("status"));
        assertEquals("Missing stamp on page 8", rejectedAg.get("reviewNote"));

        // 7. Option B: Retailer initiates Aadhaar eSign (self signatory -> SIGN mode)
        setAuth(retailer);
        Map<String, Object> esignStart = agreementService.startEsign(
                retailer, "19.0760", "72.8777", "127.0.0.1", "JUnit");
        assertEquals("SIGN", esignStart.get("mode"));
        assertEquals(true, esignStart.get("signatoryIsSelf"));
        String clientId = String.valueOf(esignStart.get("clientId"));
        assertTrue(clientId.startsWith("esign_mock_"));
        assertNotNull(esignStart.get("signingUrl"));

        // Complete mock eSign and verify public return status syncs & marks SIGNED
        agreementService.performMockAction(clientId, "complete", "RAJESH KUMAR SHARMA");
        Map<String, Object> returnStatus = agreementService.publicReturnStatus(agreementId);
        assertEquals("SIGNED", returnStatus.get("status"));
        assertEquals(true, returnStatus.get("signed"));

        // Signed PDF is stored and downloadable
        AgreementService.LoadedPdf signedPdf = agreementService.loadFileForPartner(
                retailer, "signed", "127.0.0.1", "JUnit");
        String signedText = extractPdfText(signedPdf.bytes());
        assertTrue(signedText.contains("Digitally Signed via Aadhaar eSign"));
        assertTrue(signedText.contains("RAJESH KUMAR SHARMA"));

        // Services are now unlocked!
        Map<String, Object> finalVerif = verificationService.status(retailer);
        assertEquals("SIGNED", finalVerif.get("agreementStatus"));
        assertEquals(true, finalVerif.get("servicesUnlocked"));
        verificationGate.requireBusiness(retailerId);

        // Once SIGNED, calling getForPartner or getForAdmin NEVER calls Surepass eSign status API again
        int statusCallsAfterSigned = mockEsignClient.statusCalls();
        agreementService.getForPartner(retailer);
        setAuth(superAdmin);
        agreementService.getForAdmin(superAdmin, retailerId);
        assertEquals(statusCallsAfterSigned, mockEsignClient.statusCalls(),
                "Once agreement is SIGNED in DB, Surepass eSign status API must never be called again");
    }

    @Test
    @SuppressWarnings("unchecked")
    void distributorCompanyExternalDirectorFlow_generatesDistributorMsa_andReturnsShareLinkModeForSignatory() throws Exception {
        String suffix = String.valueOf(1000 + (int) (Math.random() * 8999));
        UUID distributorId = onboardAndApprovePersonal("982223" + suffix, "VIKRAM ADITYA PATEL", "MASTER_DISTRIBUTOR");
        AuthPrincipal distributor = new AuthPrincipal(
                distributorId, "VIKRAM ADITYA PATEL", "MASTER_DISTRIBUTOR", null, "FULL");

        // Distributor submits Company business registration with external director HEMANT MISHRA (DIN 09278529)
        setAuth(distributor);
        String sigPhone = "983333" + suffix;
        Map<String, Object> submitted = verificationService.submitCompanyBusiness(
                distributor,
                "U62099KA2024PTC183699",
                "09278529",
                "Zilics Distributor Network",
                sigPhone,
                "12.9121000",
                "77.6446000"
        );
        Map<String, Object> sig = (Map<String, Object>) submitted.get("signatory");
        String sigUrl = String.valueOf(sig.get("signatoryUrl"));
        String sigToken = sigUrl.substring(sigUrl.lastIndexOf('/') + 1);

        // External director completes signatory KYC
        SecurityContextHolder.clearContext();
        signatoryKycService.openLink(sigToken, "127.0.0.1", "JUnit");
        signatoryKycService.sendOtp(sigToken, "127.0.0.1", "JUnit");
        String otp = smsSender.lastFor(sigPhone).params().get("otp");
        String sigSession = String.valueOf(signatoryKycService.verifyOtp(sigToken, otp, "127.0.0.1", "JUnit").get("sessionToken"));
        signatoryKycService.captureLocation(sigSession, 12.9121, 77.6446, 8.0, "127.0.0.1", "JUnit");
        String dlState = String.valueOf(signatoryKycService.startDigilocker(sigSession, "127.0.0.1", "JUnit").get("state"));
        signatoryKycService.completeDigilocker(sigSession, dlState, "127.0.0.1", "JUnit");
        signatoryKycService.verifyPan(sigSession, "", null, "127.0.0.1", "JUnit");
        MockMultipartFile sigSelfie = new MockMultipartFile(
                "file", "sig-selfie.jpg", "image/jpeg", "sig-selfie".getBytes(StandardCharsets.UTF_8));
        signatoryKycService.verifySelfie(sigSession, sigSelfie, "127.0.0.1", "JUnit");

        // Admin approves signatory face match and approves business
        setAuth(superAdmin);
        verificationService.reviewSignatoryFace(superAdmin, distributorId, true, "Director face matched");
        verificationService.reviewBusiness(superAdmin, distributorId, true, "Company & director verified");

        // Admin generates Distributor MSA (onboarding fee defaults to NIL)
        Map<String, Object> genResult = agreementService.generate(superAdmin, distributorId, BigDecimal.ZERO, "127.0.0.1", "JUnit");
        Map<String, Object> ag = (Map<String, Object>) genResult.get("agreement");
        UUID agreementId = (UUID) ag.get("id");
        assertEquals("DISTRIBUTOR_MSA", ag.get("agreementType"));
        assertEquals("MASTER_DISTRIBUTOR", ag.get("partnerRole"));
        assertEquals("HEMANT MISHRA", ag.get("signatoryName"));
        assertEquals(false, ag.get("signatoryIsSelf"));
        assertEquals("SHARE", ag.get("esignMode"));
        assertEquals("NIL", ag.get("onboardingFeeDisplay"));

        // Verify Distributor MSA PDF contents
        AgreementService.LoadedPdf distPdf = agreementService.loadFileForAdmin(superAdmin, agreementId, "unsigned");
        String pdfText = extractPdfText(distPdf.bytes()).replaceAll("\\s+", " ");
        assertTrue(pdfText.contains("MASTER SERVICE AGREEMENT"));
        assertTrue(pdfText.contains("Distributor MSA"));
        assertTrue(pdfText.contains("APPOINTMENT OF RETAILER"));
        assertTrue(pdfText.contains("ZILICS VENTURES PRIVATE LIMITED"));
        assertTrue(pdfText.contains("HEMANT MISHRA"));

        // Admin releases agreement
        agreementService.release(superAdmin, agreementId, "127.0.0.1", "JUnit");

        // Distributor initiates eSign -> since signatory is an external director, mode MUST be SHARE
        setAuth(distributor);
        Map<String, Object> esignStart = agreementService.startEsign(
                distributor, "12.9121", "77.6446", "127.0.0.1", "JUnit");
        assertEquals("SHARE", esignStart.get("mode"),
                "When authorized signatory is another director, partner must get SHARE link mode");
        assertFalse((Boolean) esignStart.get("signatoryIsSelf"));
        assertEquals("HEMANT MISHRA", esignStart.get("signatoryName"));
        assertEquals("XXXXXX" + suffix, esignStart.get("signatoryMobileMasked"));

        // Distributor copies link to share with director
        Map<String, Object> copied = agreementService.recordLinkCopied(distributor, "127.0.0.1", "JUnit");
        assertEquals(true, copied.get("copied"));

        // External director completes Aadhaar eSign via the shared link
        String clientId = String.valueOf(esignStart.get("clientId"));
        agreementService.performMockAction(clientId, "complete", "HEMANT MISHRA");

        // Distributor refreshes status -> transitions to SIGNED
        Map<String, Object> refreshed = agreementService.refreshMyEsign(distributor, "127.0.0.1", "JUnit");
        assertEquals("SIGNED", refreshed.get("status"));
    }

    private static String extractPdfText(byte[] bytes) throws Exception {
        try (PDDocument doc = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @SuppressWarnings("unchecked")
    private UUID onboardAndApprovePersonal(String phone, String fullName, String role) {
        UUID hubId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hubs (id, code, name, city, state, status, created_by)
                VALUES (?, ?, 'Test Hub', 'Bengaluru', 'KA', 'ACTIVE', ?)
                """, hubId, "HB" + phone.substring(phone.length() - 4), superAdmin.userId());

        UUID parentId = null;
        if ("RETAILER".equals(role)) {
            parentId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO users (id, code, full_name, mobile, password_hash, user_type, status, hub_id, created_by)
                    VALUES (?, ?, 'Parent Dist', ?, 'hash', 'MASTER_DISTRIBUTOR', 'ACTIVE', ?, ?)
                    """, parentId, "GFD" + phone.substring(phone.length() - 4), "91" + phone.substring(2), hubId, superAdmin.userId());
        }

        Map<String, Object> inviteCreated = inviteService.createInvite(
                superAdmin, role, fullName, phone,
                phone + "@example.com", parentId, "MASTER_DISTRIBUTOR".equals(role) ? hubId : null, "127.0.0.1", "JUnit");
        String token = String.valueOf(inviteCreated.get("token"));

        publicOnboarding.openLink(token, "127.0.0.1", "JUnit");
        publicOnboarding.sendOtp(token, "127.0.0.1", "JUnit");
        String otpCode = smsSender.lastFor(phone).params().get("otp");
        Map<String, Object> verifiedOtp = publicOnboarding.verifyOtp(token, otpCode, "127.0.0.1", "JUnit");
        String sessionToken = String.valueOf(verifiedOtp.get("sessionToken"));

        publicOnboarding.captureLocation(sessionToken, 19.0760, 72.8777, 10.5, "127.0.0.1", "JUnit");
        Map<String, Object> dlInit = publicOnboarding.startDigilocker(sessionToken, "127.0.0.1", "JUnit");
        publicOnboarding.completeDigilocker(sessionToken, String.valueOf(dlInit.get("state")), "127.0.0.1", "JUnit");
        publicOnboarding.verifyPan(sessionToken, "", "127.0.0.1", "JUnit");

        MockMultipartFile selfie = new MockMultipartFile(
                "file", "selfie.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8));
        publicOnboarding.verifySelfie(sessionToken, selfie, "127.0.0.1", "JUnit");

        Map<String, Object> completed = publicOnboarding.setCredentials(
                sessionToken, "StrongPass#2026", "258012", "127.0.0.1", "JUnit");
        UUID userId = UUID.fromString(String.valueOf(completed.get("userId")));

        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", userId);
        setAuth(superAdmin);
        verificationService.reviewFace(superAdmin, userId, true, "Face matches Aadhaar");
        Map<String, Object> view = verificationService.statusFor(userId);
        List<Map<String, Object>> dlDocs = (List<Map<String, Object>>) view.get("digilockerDocuments");
        for (Map<String, Object> doc : dlDocs) {
            UUID docId = UUID.fromString(String.valueOf(doc.get("id")));
            digilockerDocumentService.reviewDocument(superAdmin, userId, docId, true, "Approved", "127.0.0.1", "JUnit");
        }
        return userId;
    }
}
