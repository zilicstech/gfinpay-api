package com.fintech.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.kyc.surepass.LiveSurepassClient;
import com.fintech.kyc.surepass.SurepassClient;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.sms.MockSmsSender;
import com.fintech.verification.DigilockerDocumentService;
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
class BusinessRegistrationFlowTest {

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
    @Autowired VerificationService verificationService;
    @Autowired SignatoryKycService signatoryKycService;
    @Autowired DigilockerDocumentService digilockerDocumentService;
    @Autowired MockSmsSender smsSender;
    @Autowired ObjectMapper json;

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
    void liveSurepassCompanyDetailsParser_parsesNestedCompanyInfoAndDirectors() throws Exception {
        String rawSurepassDataJson = """
                {
                  "client_id": "corporate_company_details_v2_test",
                  "company_id": "U62099KA2024PTC183699",
                  "company_name": "ZILICS VENTURES PRIVATE LIMITED",
                  "company_type": "Company",
                  "details": {
                    "company_info": {
                      "cin": "U62099KA2024PTC183699",
                      "roc_code": "RoC-Bangalore",
                      "class_of_company": "Private",
                      "date_of_incorporation": "2024-10-16",
                      "registered_address": "132/2, 2nd Floor, Rainbow, Residency Layout, Carmelaram, Bangalore, Bangalore South, Karnataka, India, 560035",
                      "company_status": "Active",
                      "email_id": "hemant@zilics.com"
                    },
                    "directors": [
                      {
                        "din_or_pan": "10808826",
                        "director_name": "ANNU JHAMNANI",
                        "start_date": "2024-10-16",
                        "end_date": "1800-01-01",
                        "surrendered_din": ""
                      },
                      {
                        "din_or_pan": "10808827",
                        "director_name": "HEMANT MISHRA",
                        "start_date": "2024-10-16",
                        "end_date": "1800-01-01",
                        "surrendered_din": ""
                      },
                      {
                        "din_or_pan": "09999999",
                        "director_name": "FORMER DIRECTOR",
                        "start_date": "2024-10-16",
                        "end_date": "2025-02-01",
                        "surrendered_din": ""
                      }
                    ]
                  }
                }
                """;
        Map<String, Object> data = json.readValue(rawSurepassDataJson, new TypeReference<>() {});
        SurepassClient.CompanyRecord record = LiveSurepassClient.parseCompanyRecord("U62099KA2024PTC183699", data);

        assertEquals("U62099KA2024PTC183699", record.cin());
        assertEquals("ZILICS VENTURES PRIVATE LIMITED", record.companyName());
        assertEquals("Company", record.companyType());
        assertEquals("Private", record.classOfCompany());
        assertEquals("Active", record.status());
        assertEquals("2024-10-16", record.dateOfIncorporation());
        assertEquals("RoC-Bangalore", record.rocCode());
        assertEquals("560035", record.pincode());
        assertEquals("corporate_company_details_v2_test", record.providerRef());
        assertEquals(3, record.directors().size());

        SurepassClient.Director d1 = record.directors().get(0);
        assertEquals("10808826", d1.din());
        assertEquals("ANNU JHAMNANI", d1.name());
        assertTrue(d1.active(), "1800-01-01 sentinel must be treated as active director");
        assertEquals("", d1.endDate());

        SurepassClient.Director d3 = record.directors().get(2);
        assertEquals("09999999", d3.din());
        assertFalse(d3.active(), "Director with real end_date must be inactive");
        assertEquals("2025-02-01", d3.endDate());
    }

    @Test
    @SuppressWarnings("unchecked")
    void individualBusinessRegistration_usesKycLocationAndAadhaarAddress_movesToUnderReview() {
        UUID retailerUserId = onboardAndApprovePersonalIdentity("9811122201", "RAJESH KUMAR SHARMA");
        AuthPrincipal retailer = new AuthPrincipal(retailerUserId, "RAJESH KUMAR SHARMA", "RETAILER", null, "FULL");
        setAuth(retailer);

        Map<String, Object> kycLoc = verificationService.kycLocation(retailer);
        assertEquals(true, kycLoc.get("available"));
        assertEquals(0, new BigDecimal("19.0760000").compareTo(new BigDecimal(String.valueOf(kycLoc.get("latitude")))));
        assertEquals(0, new BigDecimal("72.8777000").compareTo(new BigDecimal(String.valueOf(kycLoc.get("longitude")))));

        // Shop / Trade name is mandatory
        ApiException missingShopName = assertThrows(ApiException.class, () ->
                verificationService.submitIndividualBusiness(retailer, "KYC_LOCATION", null, null, "   ", null));
        assertEquals("INVALID_INPUT", missingShopName.getCode());

        // Optional shop photo upload (and re-upload replaces previous SHOP_PHOTO)
        MockMultipartFile firstShopPhoto = new MockMultipartFile(
                "file", "shop-old.jpg", "image/jpeg", "old-shop-bytes".getBytes(StandardCharsets.UTF_8));
        verificationService.addDocument(retailer, "SHOP_PHOTO", firstShopPhoto);
        MockMultipartFile latestShopPhoto = new MockMultipartFile(
                "file", "shop-camera.jpg", "image/jpeg", "new-shop-bytes".getBytes(StandardCharsets.UTF_8));
        verificationService.addDocument(retailer, "SHOP_PHOTO", latestShopPhoto);

        Map<String, Object> result = verificationService.submitIndividualBusiness(
                retailer,
                "KYC_LOCATION",
                null,
                null,
                "Sharma Recharge & Kirana",
                null
        );

        assertEquals("INDIVIDUAL", result.get("businessType"));
        assertEquals("UNDER_REVIEW", result.get("businessStatus"));
        assertEquals(false, result.get("businessVerified"));
        assertEquals("Sharma Recharge & Kirana", result.get("shopName"));
        List<Map<String, Object>> docs = (List<Map<String, Object>>) result.get("documents");
        assertEquals(1, docs.size(), "Re-uploading SHOP_PHOTO should replace the previous SHOP_PHOTO");
        assertEquals("SHOP_PHOTO", docs.get(0).get("doc_type"));
        assertEquals("shop-camera.jpg", docs.get(0).get("filename"));

        Map<String, Object> workLocation = (Map<String, Object>) result.get("workLocation");
        assertNotNull(workLocation);
        assertEquals("KYC_LOCATION", workLocation.get("source"));
        assertEquals(0, new BigDecimal("19.0760000").compareTo(new BigDecimal(String.valueOf(workLocation.get("latitude")))));

        Map<String, Object> workAddress = (Map<String, Object>) result.get("workAddress");
        assertNotNull(workAddress);
        assertEquals(true, workAddress.get("sameAsPersonal"));
        assertNotNull(workAddress.get("fullAddress"));

        Map<String, Object> biz = (Map<String, Object>) result.get("business");
        assertEquals(true, biz.get("isSelfSignatory"));
        assertEquals("INDIVIDUAL", biz.get("businessType"));

        // Admin approves Individual Business Registration
        setAuth(superAdmin);
        Map<String, Object> approved = verificationService.reviewBusiness(
                superAdmin, retailerUserId, true, "Individual work location and Aadhaar work address verified");
        assertEquals("BUSINESS_VERIFIED", approved.get("businessStatus"));
        assertEquals(true, approved.get("businessVerified"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void companyBusinessRegistration_selfDirector_autoVerifiesSignatoryAndMovesToUnderReview() {
        UUID retailerUserId = onboardAndApprovePersonalIdentity("9811122202", "ANNU JHAMNANI");
        AuthPrincipal retailer = new AuthPrincipal(retailerUserId, "ANNU JHAMNANI", "RETAILER", null, "FULL");
        setAuth(retailer);

        Map<String, Object> lookup = verificationService.lookupCompanyDetails(retailer, "U62099KA2024PTC183699");
        assertEquals("U62099KA2024PTC183699", lookup.get("cin"));
        assertEquals("ZILICS VENTURES PRIVATE LIMITED", lookup.get("companyName"));
        assertEquals("10472494", lookup.get("suggestedDin"), "ANNU JHAMNANI should be auto-suggested as self director");

        List<Map<String, Object>> directors = (List<Map<String, Object>>) lookup.get("directors");
        assertEquals(2, directors.size());
        assertEquals(true, directors.get(0).get("isSelf"));

        // Shop / Trade name is mandatory for Company registration as well
        ApiException missingCompanyShopName = assertThrows(ApiException.class, () ->
                verificationService.submitCompanyBusiness(
                        retailer, "U62099KA2024PTC183699", "10472494", "", null, "12.9121000", "77.6446000"));
        assertEquals("INVALID_INPUT", missingCompanyShopName.getCode());

        Map<String, Object> submitted = verificationService.submitCompanyBusiness(
                retailer,
                "U62099KA2024PTC183699",
                "10472494",
                "Zilics FinTech Desk",
                null,
                "12.9121000",
                "77.6446000"
        );

        assertEquals("COMPANY", submitted.get("businessType"));
        assertEquals("UNDER_REVIEW", submitted.get("businessStatus"));
        assertEquals("Zilics FinTech Desk", submitted.get("shopName"));
        Map<String, Object> signatory = (Map<String, Object>) submitted.get("signatory");
        assertNotNull(signatory);
        assertEquals("ANNU JHAMNANI", signatory.get("directorName"));
        assertEquals("10472494", signatory.get("din"));
        assertEquals(true, signatory.get("isSelf"));
        assertEquals("SELF_VERIFIED", signatory.get("status"));

        // Admin approves Company Business Registration directly since signatory is self-verified
        setAuth(superAdmin);
        Map<String, Object> approved = verificationService.reviewBusiness(
                superAdmin, retailerUserId, true, "Self director verified via personal KYC");
        assertEquals("BUSINESS_VERIFIED", approved.get("businessStatus"));
        assertEquals(true, approved.get("businessVerified"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void companyBusinessRegistration_externalDirector_completesSignatoryKyc_andAdminApproves() {
        UUID retailerUserId = onboardAndApprovePersonalIdentity("9811122203", "VIKRAM ADITYA PATEL");
        AuthPrincipal retailer = new AuthPrincipal(retailerUserId, "VIKRAM ADITYA PATEL", "RETAILER", null, "FULL");
        setAuth(retailer);

        Map<String, Object> lookup = verificationService.lookupCompanyDetails(retailer, "U62099KA2024PTC183699");
        assertNull(lookup.get("suggestedDin"), "No director should match VIKRAM ADITYA PATEL");

        Map<String, Object> submitted = verificationService.submitCompanyBusiness(
                retailer,
                "U62099KA2024PTC183699",
                "09278529",
                "Zilics Retail Hub",
                "9811122299",
                "12.9121000",
                "77.6446000"
        );

        assertEquals("COMPANY", submitted.get("businessType"));
        assertEquals("AWAITING_SIGNATORY_KYC", submitted.get("businessStatus"));
        Map<String, Object> signatory = (Map<String, Object>) submitted.get("signatory");
        assertNotNull(signatory);
        assertEquals("HEMANT MISHRA", signatory.get("directorName"));
        assertEquals("09278529", signatory.get("din"));
        assertEquals(false, signatory.get("isSelf"));
        assertEquals("LINK_SENT", signatory.get("status"));
        String signatoryUrl = (String) signatory.get("signatoryUrl");
        assertNotNull(signatoryUrl);
        String sigToken = signatoryUrl.substring(signatoryUrl.lastIndexOf('/') + 1);

        // Admin cannot approve while signatory KYC is pending
        setAuth(superAdmin);
        ApiException prematureApproval = assertThrows(ApiException.class, () ->
                verificationService.reviewBusiness(superAdmin, retailerUserId, true, "Trying early approval"));
        assertEquals("SIGNATORY_KYC_REQUIRED", prematureApproval.getCode());

        // Signatory completes public KYC journey via /api/v1/public/signatory/{token}
        SecurityContextHolder.clearContext();
        Map<String, Object> sigInitState = signatoryKycService.openLink(sigToken, "127.0.0.1", "JUnit");
        assertEquals("ZILICS VENTURES PRIVATE LIMITED", sigInitState.get("companyName"));
        assertEquals("HEMANT MISHRA", sigInitState.get("directorName"));
        assertEquals("LINK_OPENED", sigInitState.get("currentStep"));

        // Step 1: OTP
        signatoryKycService.sendOtp(sigToken, "127.0.0.1", "JUnit");
        String otpCode = smsSender.lastFor("9811122299").params().get("otp");
        assertNotNull(otpCode);
        Map<String, Object> afterOtp = signatoryKycService.verifyOtp(sigToken, otpCode, "127.0.0.1", "JUnit");
        String sigSessionToken = String.valueOf(afterOtp.get("sessionToken"));
        assertNotNull(sigSessionToken);
        assertEquals("PHONE_VERIFIED", afterOtp.get("currentStep"));

        // Step 2: Location
        Map<String, Object> afterLoc = signatoryKycService.captureLocation(
                sigSessionToken, 12.9121, 77.6446, 8.0, "127.0.0.1", "JUnit");
        assertEquals("LOCATION_CAPTURED", afterLoc.get("currentStep"));

        // Step 3: DigiLocker
        Map<String, Object> dlInit = signatoryKycService.startDigilocker(sigSessionToken, "127.0.0.1", "JUnit");
        String dlState = String.valueOf(dlInit.get("state"));
        Map<String, Object> afterDl = signatoryKycService.completeDigilocker(
                sigSessionToken, dlState, "127.0.0.1", "JUnit");
        assertEquals("AADHAAR_VERIFIED", afterDl.get("currentStep"));
        assertEquals("ABCDE1234F", afterDl.get("digilockerPanNumber"));

        // Step 4: PAN verification
        Map<String, Object> afterPan = signatoryKycService.verifyPan(
                sigSessionToken, "", null, "127.0.0.1", "JUnit");
        assertEquals("PAN_VERIFIED", afterPan.get("currentStep"));
        assertEquals(true, afterPan.get("aadhaarPanLinked"));

        // Step 5: Live Selfie -> completes Signatory KYC and moves partner business_status to UNDER_REVIEW
        MockMultipartFile sigSelfie = new MockMultipartFile(
                "file", "signatory-selfie.jpg", "image/jpeg", "fake-signatory-selfie-bytes".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> afterSelfie = signatoryKycService.verifySelfie(
                sigSessionToken, sigSelfie, "127.0.0.1", "JUnit");
        assertEquals("COMPLETED", afterSelfie.get("status"));
        assertEquals("KYC_COMPLETED", afterSelfie.get("signatoryStatus"));
        assertEquals("UNDER_REVIEW", afterSelfie.get("businessStatus"));

        // Inspect Admin view: Partner's personal DigiLocker docs (3) must NOT include Signatory's docs (3)
        setAuth(superAdmin);
        Map<String, Object> adminView = verificationService.statusFor(retailerUserId);
        assertEquals("UNDER_REVIEW", adminView.get("businessStatus"));
        List<Map<String, Object>> partnerDocs = (List<Map<String, Object>>) adminView.get("digilockerDocuments");
        assertEquals(3, partnerDocs.size(), "Partner's personal DigiLocker vault must only have partner's 3 docs");

        Map<String, Object> sigView = (Map<String, Object>) adminView.get("signatory");
        assertNotNull(sigView);
        assertEquals("KYC_COMPLETED", sigView.get("status"));
        assertEquals("PENDING", sigView.get("faceReviewStatus"));
        List<Map<String, Object>> sigDocs = (List<Map<String, Object>>) sigView.get("digilockerDocuments");
        assertEquals(3, sigDocs.size(), "Signatory's DigiLocker vault must have signatory's 3 docs");

        // Admin can also decrypt a signatory's DigiLocker document via DigilockerDocumentService
        UUID sigDocId = UUID.fromString(String.valueOf(sigDocs.get(0).get("id")));
        DigilockerDocumentService.DecryptedDocument decryptedSigDoc =
                digilockerDocumentService.loadDecryptedDocument(superAdmin, retailerUserId, sigDocId, "127.0.0.1", "JUnit");
        assertTrue(decryptedSigDoc.bytes().length > 0);

        // Admin cannot approve business before marking signatory face matched
        ApiException missingSigFace = assertThrows(ApiException.class, () ->
                verificationService.reviewBusiness(superAdmin, retailerUserId, true, "Trying before signatory face match"));
        assertEquals("SIGNATORY_FACE_REVIEW_REQUIRED", missingSigFace.getCode());

        // Admin marks Signatory Face Matched
        verificationService.reviewSignatoryFace(superAdmin, retailerUserId, true, "Signatory live selfie matches Aadhaar photo");

        // Admin approves Company Business Registration
        Map<String, Object> approved = verificationService.reviewBusiness(
                superAdmin, retailerUserId, true, "Company CIN and Signatory HEMANT MISHRA verified");
        assertEquals("BUSINESS_VERIFIED", approved.get("businessStatus"));
        assertEquals(true, approved.get("businessVerified"));
    }

    @SuppressWarnings("unchecked")
    private UUID onboardAndApprovePersonalIdentity(String phone, String fullName) {
        UUID hubId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hubs (id, code, name, city, state, status, created_by)
                VALUES (?, ?, 'Test Hub', 'Bengaluru', 'KA', 'ACTIVE', ?)
                """, hubId, "HUB" + phone.substring(phone.length() - 4), superAdmin.userId());

        UUID distId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, code, full_name, mobile, password_hash, user_type, status, hub_id, created_by)
                VALUES (?, ?, 'Parent Dist', ?, 'hash', 'MASTER_DISTRIBUTOR', 'ACTIVE', ?, ?)
                """, distId, "GFIND" + phone.substring(phone.length() - 4), "90" + phone.substring(2), hubId, superAdmin.userId());

        Map<String, Object> inviteCreated = inviteService.createInvite(
                superAdmin, "RETAILER", fullName, phone,
                phone + "@example.com", distId, null, "127.0.0.1", "JUnit");
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

        // Mark user ACTIVE and approve personal identity (Face Match + 3 DigiLocker docs)
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", userId);
        setAuth(superAdmin);
        verificationService.reviewFace(superAdmin, userId, true, "Face matches Aadhaar");
        Map<String, Object> view = verificationService.statusFor(userId);
        List<Map<String, Object>> dlDocs = (List<Map<String, Object>>) view.get("digilockerDocuments");
        for (Map<String, Object> doc : dlDocs) {
            UUID docId = UUID.fromString(String.valueOf(doc.get("id")));
            digilockerDocumentService.reviewDocument(superAdmin, userId, docId, true, "Approved", "127.0.0.1", "JUnit");
        }
        Map<String, Object> afterDocs = verificationService.statusFor(userId);
        assertEquals(true, afterDocs.get("personalIdentityVerified"));
        return userId;
    }
}
