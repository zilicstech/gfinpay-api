package com.fintech.onboarding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.agreement.AgreementService;
import com.fintech.finance.FinanceService;
import com.fintech.finance.RetailerBankService;
import com.fintech.identity.UserService;
import com.fintech.kyc.surepass.MockSurepassClient;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.sms.MockSmsSender;
import com.fintech.verification.AddressFormatter;
import com.fintech.verification.DigilockerDocumentService;
import com.fintech.verification.VerificationGate;
import com.fintech.verification.VerificationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = {
        "app.sms.provider=mock",
        "app.surepass.mode=mock",
        "app.esign.mode=mock"
})
@ActiveProfiles("test")
class OnboardingAndVerificationFlowTest {

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
    @Autowired ReverifyService reverifyService;
    @Autowired UserService userService;
    @Autowired FinanceService financeService;
    @Autowired RetailerBankService retailerBankService;
    @Autowired VerificationService verificationService;
    @Autowired AgreementService agreementService;
    @Autowired DigilockerDocumentService digilockerDocumentService;
    @Autowired VerificationGate verificationGate;
    @Autowired MockSmsSender smsSender;
    @Autowired MockSurepassClient mockSurepass;
    @Autowired PasswordEncoder passwordEncoder;

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
    void fullSelfServiceRetailerOnboardingActivationBankAndBusinessLifecycle() {
        UUID hubId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hubs (id, code, name, city, state, status, created_by)
                VALUES (?, 'HUBTEST01', 'Test Hub', 'Bengaluru', 'KA', 'ACTIVE', ?)
                """, hubId, superAdmin.userId());

        UUID distributorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, code, full_name, mobile, password_hash, user_type, status, hub_id, created_by)
                VALUES (?, 'GFINDIST1', 'Parent Distributor', '9800000001', ?, 'MASTER_DISTRIBUTOR', 'ACTIVE', ?, ?)
                """, distributorId, passwordEncoder.encode("DistPass1"), hubId, superAdmin.userId());

        String mobile = "9876543210";

        // 1. Admin creates an onboarding invite for a Retailer under the distributor
        Map<String, Object> inviteCreated = inviteService.createInvite(
                superAdmin, "RETAILER", "Rajesh Kumar Sharma", mobile,
                "rajesh@example.com", distributorId, null, "127.0.0.1", "JUnit");
        String link = String.valueOf(inviteCreated.get("onboardingUrl"));
        assertTrue(link.contains("/onboard/"));
        String token = String.valueOf(inviteCreated.get("token"));

        // 2. Partner opens link
        Map<String, Object> opened = publicOnboarding.openLink(token, "127.0.0.1", "JUnit");
        assertEquals("RETAILER", opened.get("role"));
        assertEquals("LINK_OPENED", opened.get("currentStep"));

        // 3. Send & verify phone OTP -> returns X-Onboarding-Session token
        publicOnboarding.sendOtp(token, "127.0.0.1", "JUnit");
        String otp = smsSender.lastFor(mobile).params().get("otp");
        assertNotNull(otp);
        Map<String, Object> verifiedOtp = publicOnboarding.verifyOtp(token, otp, "127.0.0.1", "JUnit");
        String sessionToken = String.valueOf(verifiedOtp.get("sessionToken"));
        assertNotNull(sessionToken);

        // 4. Capture GPS location
        Map<String, Object> loc = publicOnboarding.captureLocation(
                sessionToken, 12.9716, 77.5946, 15.0, "127.0.0.1", "JUnit");
        assertEquals("LOCATION_CAPTURED", loc.get("currentStep"));

        // 5. DigiLocker Aadhaar + PAN verification via DigiBoost SDK token flow
        Map<String, Object> dlStart = publicOnboarding.startDigilocker(
                sessionToken, "127.0.0.1", "JUnit");
        assertNotNull(dlStart.get("token"));
        assertEquals("production", dlStart.get("gateway"));
        String state = String.valueOf(dlStart.get("state"));
        Map<String, Object> dlDone = publicOnboarding.completeDigilocker(
                sessionToken, state, "127.0.0.1", "JUnit");
        assertEquals("AADHAAR_VERIFIED", dlDone.get("currentStep"));
        assertEquals(true, dlDone.get("panInDigilocker"));
        assertEquals(true, dlDone.get("digilockerPanAvailable"));
        assertEquals("ABCDE1234F", dlDone.get("digilockerPanNumber"));
        Map<String, Object> permAddr = (Map<String, Object>) dlDone.get("permanentAddress");
        assertNotNull(permAddr);
        assertEquals("560038", permAddr.get("pincode"));

        // 6. PAN verification using autofilled DigiLocker PAN (no need to re-enter PAN) + Aadhaar last-4 & link check
        Map<String, Object> panRes = publicOnboarding.verifyPan(
                sessionToken, "", "127.0.0.1", "JUnit");
        assertEquals("PAN_VERIFIED", panRes.get("currentStep"));
        assertEquals("234F", panRes.get("panLast4"));
        assertEquals("DIGILOCKER", panRes.get("panSource"));
        assertEquals(true, panRes.get("aadhaarPanLinked"));
        assertEquals(true, panRes.get("panAadhaarLast4Match"));

        // 7. Live selfie capture -> queued for manual admin face verification (PENDING)
        MockMultipartFile selfie = new MockMultipartFile(
                "file", "selfie.jpg", "image/jpeg", "SELFIE_BYTES".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> selfieRes = publicOnboarding.verifySelfie(
                sessionToken, selfie, "127.0.0.1", "JUnit");
        assertEquals("SELFIE_VERIFIED", selfieRes.get("currentStep"));
        assertEquals("PENDING", selfieRes.get("faceReviewStatus"));

        // 8. Set Password + 6-digit PIN -> creates user in PENDING status
        Map<String, Object> credRes = publicOnboarding.setCredentials(
                sessionToken, "StrongPass9", "482917", "127.0.0.1", "JUnit");
        String gfinCode = String.valueOf(credRes.get("code"));
        UUID retailerId = UUID.fromString(String.valueOf(credRes.get("userId")));
        assertTrue(gfinCode.startsWith("GFIN"));

        // Verify shop_address is NULL after onboarding (separate from Aadhaar permanentAddress)
        String shopAddressRaw = jdbc.queryForObject(
                "SELECT shop_address::text FROM agent_kyc_profiles WHERE user_id = ?",
                String.class, retailerId);
        assertNull(shopAddressRaw);
        Boolean aadhaarPanLinkedCol = jdbc.queryForObject(
                "SELECT aadhaar_pan_linked FROM agent_kyc_profiles WHERE user_id = ?",
                Boolean.class, retailerId);
        assertEquals(Boolean.TRUE, aadhaarPanLinkedCol);

        // Verify DigiLocker documents were linked to retailerId and mandatory PDFs + Aadhaar photo are STORED
        List<Map<String, Object>> dlDocs = digilockerDocumentService.listDocumentsForUser(retailerId);
        assertTrue(dlDocs.size() >= 3);
        assertTrue(dlDocs.stream().anyMatch(d -> "ADHAR".equals(d.get("docType")) && "pdf".equals(d.get("fileType")) && "STORED".equals(d.get("fetchStatus"))));
        assertTrue(dlDocs.stream().anyMatch(d -> "PANCR".equals(d.get("docType")) && "pdf".equals(d.get("fileType")) && "STORED".equals(d.get("fetchStatus"))));
        assertTrue(dlDocs.stream().anyMatch(d -> "AADHAAR_PHOTO".equals(d.get("docType")) && "STORED".equals(d.get("fetchStatus"))));

        // Admin streams and decrypts the Aadhaar PDF, recording an access log entry
        UUID aadhaarPdfId = (UUID) dlDocs.stream()
                .filter(d -> "ADHAR".equals(d.get("docType")) && "pdf".equals(d.get("fileType")))
                .findFirst().orElseThrow().get("id");
        DigilockerDocumentService.DecryptedDocument decrypted =
                digilockerDocumentService.loadDecryptedDocument(superAdmin, retailerId, aadhaarPdfId, "127.0.0.1", "JUnit");
        assertTrue(new String(decrypted.bytes(), StandardCharsets.UTF_8).startsWith("%PDF-"));
        Integer accessLogs = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM digilocker_document_access_log WHERE document_id = ? AND action = 'VIEW'",
                Integer.class, aadhaarPdfId);
        assertEquals(1, accessLogs);

        // 9. Partner signs in: Step 1 (password only) returns VERIFY_PIN, Step 2 (with PIN) logs in -> nextStep is ACTIVATION
        UserService.LoginResult step1Res = userService.login(mobile, "StrongPass9", null, "127.0.0.1", "JUnit");
        assertEquals("VERIFY_PIN", step1Res.body().get("step"));
        UserService.LoginResult loginRes = userService.login(
                mobile, "StrongPass9", "482917", "127.0.0.1", "JUnit");
        assertEquals("ACTIVATION", loginRes.body().get("nextStep"));

        AuthPrincipal retailerPrincipal = new AuthPrincipal(
                retailerId, "Rajesh Kumar Sharma", "RETAILER", null, "FULL");

        // Before activation, even identity services are blocked (USER_NOT_ACTIVE)
        assertThrows(ApiException.class, () -> verificationGate.requireIdentity(retailerId));

        // 10. Retailer submits activation request with UTR + proof; Auditor approves -> ACTIVE immediately
        setAuth(retailerPrincipal);
        MockMultipartFile proof = new MockMultipartFile(
                "proof", "payment.png", "image/png", "PROOF_BYTES".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> actReq = financeService.submit(
                retailerPrincipal, "ACTIVATION", null, new BigDecimal("999.00"),
                "UTR123456789", "Activation fee", proof);
        UUID reqId = (UUID) actReq.get("id");

        setAuth(superAdmin);
        financeService.receive(superAdmin, reqId, "Verified UTR");

        // Now Retailer is ACTIVE! Identity-tier services (FD Card, BBPS, HospiCash) are unlocked!
        assertDoesNotThrow(() -> verificationGate.requireIdentity(retailerId));
        // But Bank & Business-tier services are still locked
        assertThrows(ApiException.class, () -> verificationGate.requireBank(retailerId));
        assertThrows(ApiException.class, () -> verificationGate.requireBusiness(retailerId));

        // 11. Bank account penny-drop verification -> activates wallet
        setAuth(retailerPrincipal);
        Map<String, Object> bankCreated = retailerBankService.create(
                retailerPrincipal, "Rajesh Kumar Sharma", "123456789012", "HDFC0001234", "HDFC Bank");
        UUID bankId = (UUID) bankCreated.get("id");
        Map<String, Object> bankVerified = retailerBankService.verify(retailerPrincipal, bankId);
        assertEquals("VERIFIED", bankVerified.get("verification_status"));

        // Now Bank gate passes, Business gate still requires business verification
        assertDoesNotThrow(() -> verificationGate.requireBank(retailerId));
        assertThrows(ApiException.class, () -> verificationGate.requireBusiness(retailerId));

        // 12. Business registration with structured business address different from Aadhaar address
        AddressFormatter.BusinessAddressInput bizAddr = new AddressFormatter.BusinessAddressInput(
                "Shop 4, Commercial Street", "2nd Cross", "Near Metro", "Mysuru", "Mysuru", "Karnataka", "570001", false);
        verificationService.saveBusiness(
                retailerPrincipal, "SOLE_PROPRIETORSHIP", "Rajesh Kumar Sharma", "Sharma Fin Care",
                null, bizAddr, false, "Rajesh Kumar Sharma", "29ABCDE1234F1Z5", null, null,
                "12.9716", "77.5946");
        MockMultipartFile shopPhoto = new MockMultipartFile(
                "file", "shop.jpg", "image/jpeg", "SHOP_BYTES".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile addrProof = new MockMultipartFile(
                "file", "address.pdf", "application/pdf", "ADDR_BYTES".getBytes(StandardCharsets.UTF_8));
        verificationService.addDocument(retailerPrincipal, "SHOP_PHOTO", shopPhoto);
        verificationService.addDocument(retailerPrincipal, "ADDRESS_PROOF", addrProof);
        Map<String, Object> afterSign = verificationService.signAgreement(
                retailerPrincipal, "Rajesh Kumar Sharma", "12.9716", "77.5946");
        assertEquals("UNDER_REVIEW", afterSign.get("businessStatus"));
        Map<String, Object> savedBiz = (Map<String, Object>) afterSign.get("business");
        assertEquals(true, savedBiz.get("addressDiffersFromPersonal"));

        // Admin cannot approve business registration before face verification is MATCHED
        setAuth(superAdmin);
        ApiException faceRequired = assertThrows(ApiException.class, () ->
                verificationService.reviewBusiness(superAdmin, retailerId, true, "Try before face review"));
        assertEquals("FACE_REVIEW_REQUIRED", faceRequired.getCode());

        // Admin reviews and approves face verification (Selfie vs Aadhaar photo)
        Map<String, Object> afterFaceReview = verificationService.reviewFace(
                superAdmin, retailerId, true, "Selfie matches Aadhaar photograph");
        assertEquals("MATCHED", afterFaceReview.get("faceReviewStatus"));

        // Admin approves business registration
        Map<String, Object> afterReview = verificationService.reviewBusiness(
                superAdmin, retailerId, true, "All business docs verified");
        assertEquals("BUSINESS_VERIFIED", afterReview.get("businessStatus"));

        // Services still wait until the Partner Master Service Agreement is generated, released, and signed
        ApiException agreementReq = assertThrows(ApiException.class, () -> verificationGate.requireBusiness(retailerId));
        assertEquals("AGREEMENT_SIGNATURE_REQUIRED", agreementReq.getCode());

        // Admin generates and releases the Retailer MSA
        Map<String, Object> generated = agreementService.generate(superAdmin, retailerId, null, "127.0.0.1", "JUnit");
        Map<String, Object> genAgreement = (Map<String, Object>) generated.get("agreement");
        UUID agreementId = (UUID) genAgreement.get("id");
        assertEquals("DRAFT", genAgreement.get("status"));
        agreementService.release(superAdmin, agreementId, "127.0.0.1", "JUnit");

        // Retailer initiates and completes Aadhaar eSign
        setAuth(retailerPrincipal);
        Map<String, Object> esignInit = agreementService.startEsign(retailerPrincipal, "12.9716", "77.5946", "127.0.0.1", "JUnit");
        assertEquals("SIGN", esignInit.get("mode"));
        String clientId = String.valueOf(esignInit.get("clientId"));
        agreementService.performMockAction(clientId, "complete", "Rajesh Kumar Sharma");

        // Now Business + Agreement gate passes!
        assertDoesNotThrow(() -> verificationGate.requireBusiness(retailerId));
    }

    @Test
    void existingPartnerReverifyFlowAndFaceMismatchTriggeringReverify() {
        UUID partnerId = UUID.randomUUID();
        String mobile = "9876500011";
        String pwdHash = passwordEncoder.encode("ExistingPass1");

        jdbc.update("""
                INSERT INTO users (id, code, full_name, mobile, password_hash, user_type, status, created_by)
                VALUES (?, 'GFINREV01', 'Priya Verma', ?, ?, 'RETAILER', 'ACTIVE', ?)
                """, partnerId, mobile, pwdHash, superAdmin.userId());
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, identity_status, bank_verification_status, business_status, agreement_status)
                VALUES (?, 'NOT_VERIFIED', 'NOT_VERIFIED', 'NOT_VERIFIED', 'NOT_SIGNED')
                """, partnerId);

        // Partner logs in without a PIN yet: Step 1 returns SET_PIN, Step 2 sets newPin and returns REVERIFY nextStep
        UserService.LoginResult step1 = userService.login(mobile, "ExistingPass1", null, "127.0.0.1", "JUnit");
        assertEquals("SET_PIN", step1.body().get("step"));
        UserService.LoginResult loginRes = userService.login(mobile, "ExistingPass1", null, "593827", "127.0.0.1", "JUnit");
        assertEquals("REVERIFY", loginRes.body().get("nextStep"));

        AuthPrincipal reverifyPrincipal = new AuthPrincipal(
                partnerId, "Priya Verma", "RETAILER", null, "REVERIFY");
        setAuth(reverifyPrincipal);

        reverifyService.status(reverifyPrincipal);
        reverifyService.sendOtp(reverifyPrincipal, "127.0.0.1", "JUnit");
        String otp = smsSender.lastFor(mobile).params().get("otp");
        reverifyService.verifyOtp(reverifyPrincipal, otp, "127.0.0.1", "JUnit");
        reverifyService.captureLocation(reverifyPrincipal, 12.9716, 77.5946, 12.0, "127.0.0.1", "JUnit");

        Map<String, Object> dl = reverifyService.startDigilocker(
                reverifyPrincipal, "127.0.0.1", "JUnit");
        reverifyService.completeDigilocker(
                reverifyPrincipal, String.valueOf(dl.get("state")),
                "127.0.0.1", "JUnit");
        reverifyService.verifyPan(reverifyPrincipal, "BCDEF2345G", "127.0.0.1", "JUnit");

        MockMultipartFile selfie = new MockMultipartFile(
                "file", "selfie.jpg", "image/jpeg", "SELFIE_BYTES".getBytes(StandardCharsets.UTF_8));
        reverifyService.verifySelfie(reverifyPrincipal, selfie, "127.0.0.1", "JUnit");

        Map<String, Object> done = reverifyService.completeReverification(
                reverifyPrincipal, "593827", null, "127.0.0.1", "JUnit");
        assertEquals(true, done.get("completed"));

        // Subsequent login requires the new 6-digit PIN and lands on DASHBOARD
        UserService.LoginResult postLogin = userService.login(
                mobile, "ExistingPass1", "593827", "127.0.0.1", "JUnit");
        assertEquals("DASHBOARD", postLogin.body().get("nextStep"));

        // Admin marks face mismatch -> sets identity_status = REVERIFY_REQUIRED and revokes active sessions
        setAuth(superAdmin);
        Map<String, Object> mismatchRes = verificationService.reviewFace(
                superAdmin, partnerId, false, "Selfie does not match Aadhaar photo");
        assertEquals("MISMATCH", mismatchRes.get("faceReviewStatus"));
        assertEquals("REVERIFY_REQUIRED", mismatchRes.get("identityStatus"));

        // Next login redirects partner back to REVERIFY
        UserService.LoginResult afterMismatchLogin = userService.login(
                mobile, "ExistingPass1", "593827", "127.0.0.1", "JUnit");
        assertEquals("REVERIFY", afterMismatchLogin.body().get("nextStep"));

        String firstClientId = jdbc.queryForObject(
                "SELECT aadhaar_ref_key FROM agent_kyc_profiles WHERE user_id = ?",
                String.class, partnerId);
        assertNotNull(firstClientId);
        List<Map<String, Object>> firstDocs = digilockerDocumentService.listDocumentsForUser(partnerId);
        assertFalse(firstDocs.isEmpty());
        // Admin reviews one of the documents from the first verification (creates an access log entry)
        UUID firstDocId = (UUID) firstDocs.get(0).get("id");
        digilockerDocumentService.reviewDocument(superAdmin, partnerId, firstDocId, true, "OK", "127.0.0.1", "JUnit");

        // Partner re-verifies a second time with a new DigiLocker session
        jdbc.update("DELETE FROM phone_otp_challenges WHERE mobile = ?", mobile);
        setAuth(reverifyPrincipal);
        reverifyService.status(reverifyPrincipal);
        reverifyService.sendOtp(reverifyPrincipal, "127.0.0.1", "JUnit");
        String otp2 = smsSender.lastFor(mobile).params().get("otp");
        reverifyService.verifyOtp(reverifyPrincipal, otp2, "127.0.0.1", "JUnit");
        reverifyService.captureLocation(reverifyPrincipal, 12.9716, 77.5946, 12.0, "127.0.0.1", "JUnit");
        Map<String, Object> dl2 = reverifyService.startDigilocker(reverifyPrincipal, "127.0.0.1", "JUnit");
        reverifyService.completeDigilocker(reverifyPrincipal, String.valueOf(dl2.get("state")), "127.0.0.1", "JUnit");
        reverifyService.verifyPan(reverifyPrincipal, "BCDEF2345G", "127.0.0.1", "JUnit");
        reverifyService.verifySelfie(reverifyPrincipal, selfie, "127.0.0.1", "JUnit");
        reverifyService.completeReverification(reverifyPrincipal, "593827", null, "127.0.0.1", "JUnit");

        String secondClientId = jdbc.queryForObject(
                "SELECT aadhaar_ref_key FROM agent_kyc_profiles WHERE user_id = ?",
                String.class, partnerId);
        assertNotNull(secondClientId);
        assertFalse(firstClientId.equals(secondClientId));

        // Only the documents from the latest DigiLocker client_id remain and are listed
        List<Map<String, Object>> secondDocs = digilockerDocumentService.listDocumentsForUser(partnerId);
        assertEquals(firstDocs.size(), secondDocs.size());
        Integer oldClientDocsCount = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM digilocker_documents WHERE client_id = ?",
                Integer.class, firstClientId);
        assertEquals(0, oldClientDocsCount);
    }

    @Test
    void digilockerPanMissingFallsBackToManualPanAndImageUploadAndTracksAadhaarPanLinkedFlag() {
        UUID partnerId = UUID.randomUUID();
        String mobile = "9876500022";
        jdbc.update("""
                INSERT INTO users (id, code, full_name, mobile, password_hash, pin_hash, user_type, status, created_by)
                VALUES (?, 'GFINREV02', 'Amit Patel', ?, ?, ?, 'RETAILER', 'ACTIVE', ?)
                """, partnerId, mobile, passwordEncoder.encode("Pass1234"), passwordEncoder.encode("123456"), superAdmin.userId());
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, identity_status, bank_verification_status, business_status, agreement_status)
                VALUES (?, 'NOT_VERIFIED', 'NOT_VERIFIED', 'NOT_VERIFIED', 'NOT_SIGNED')
                """, partnerId);

        AuthPrincipal principal = new AuthPrincipal(partnerId, "Amit Patel", "RETAILER", null, "REVERIFY");
        setAuth(principal);

        reverifyService.sendOtp(principal, "127.0.0.1", "JUnit");
        String otp = smsSender.lastFor(mobile).params().get("otp");
        reverifyService.verifyOtp(principal, otp, "127.0.0.1", "JUnit");
        reverifyService.captureLocation(principal, 19.0760, 72.8777, 10.0, "127.0.0.1", "JUnit");

        Map<String, Object> dl = reverifyService.startDigilocker(principal, "127.0.0.1", "JUnit");
        String clientId = jdbc.queryForObject(
                "SELECT identity->>'digilockerClientId' FROM onboarding_journeys WHERE user_id = ? ORDER BY started_at DESC LIMIT 1",
                String.class, partnerId);
        mockSurepass.setOmitPanForClient(clientId, true);

        // When PAN is not in DigiLocker, completeDigilocker still succeeds for Aadhaar and sets digilockerPanAvailable=false
        Map<String, Object> completed = reverifyService.completeDigilocker(
                principal, String.valueOf(dl.get("state")), "127.0.0.1", "JUnit");
        assertEquals("AADHAAR_VERIFIED", completed.get("currentStep"));
        assertEquals(false, completed.get("panInDigilocker"));
        assertEquals(false, completed.get("digilockerPanAvailable"));
        assertEquals("", completed.get("digilockerPanNumber"));

        // Without a PAN image upload, verifyPan fails with PAN_IMAGE_REQUIRED
        ApiException imgEx = assertThrows(ApiException.class, () ->
                reverifyService.verifyPan(principal, "DEFPK5678L", null, "127.0.0.1", "JUnit"));
        assertEquals("PAN_IMAGE_REQUIRED", imgEx.getCode());

        // Simulate unlinked Aadhaar on this PAN so we verify aadhaar_pan_linked = FALSE is tracked
        mockSurepass.setPanAadhaarOverride("DEFPK5678L", false, "XXXXXXXX9999");
        try {
            MockMultipartFile panImage = new MockMultipartFile(
                    "file", "pan-card.jpg", "image/jpeg", "PAN_IMAGE_BYTES".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> panRes = reverifyService.verifyPan(
                    principal, "DEFPK5678L", panImage, "127.0.0.1", "JUnit");
            assertEquals("PAN_VERIFIED", panRes.get("currentStep"));
            assertEquals("MANUAL_UPLOAD", panRes.get("panSource"));
            assertEquals(false, panRes.get("aadhaarPanLinked"));
            assertEquals(false, panRes.get("panAadhaarLast4Match"));
        } finally {
            mockSurepass.clearPanAadhaarOverride("DEFPK5678L");
        }

        MockMultipartFile selfie = new MockMultipartFile(
                "file", "selfie.jpg", "image/jpeg", "SELFIE_BYTES".getBytes(StandardCharsets.UTF_8));
        reverifyService.verifySelfie(principal, selfie, "127.0.0.1", "JUnit");
        reverifyService.completeReverification(principal, "593827", null, "127.0.0.1", "JUnit");

        Boolean aadhaarPanLinked = jdbc.queryForObject(
                "SELECT aadhaar_pan_linked FROM agent_kyc_profiles WHERE user_id = ?",
                Boolean.class, partnerId);
        assertEquals(Boolean.FALSE, aadhaarPanLinked);

        // Verify all 3 reviewable documents (ADHAR pdf, AADHAAR_PHOTO jpg, and manually uploaded PANCR jpg) are listed for Admin
        List<Map<String, Object>> docs = digilockerDocumentService.listDocumentsForUser(partnerId);
        assertEquals(3, docs.size());
        assertTrue(docs.stream().anyMatch(d -> "PANCR".equals(d.get("docType")) && "MANUAL_UPLOAD".equals(d.get("source"))));
    }
}
