package com.fintech.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.agreement.AgreementController;
import com.fintech.finance.FinanceController;
import com.fintech.identity.AccessCatalog;
import com.fintech.identity.AdminPlatformController;
import com.fintech.identity.DeskController;
import com.fintech.identity.PartnerInquiryController;
import com.fintech.identity.PartnerInquiryService;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.AdminProviderController;
import com.fintech.sms.MockSmsSender;
import com.fintech.verification.VerificationController;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
class DistributorOnboardingAccessTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATASOURCE_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("TEST_DATASOURCE_USERNAME", "postgres"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("TEST_DATASOURCE_PASSWORD", ""));
            return;
        }
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired InviteController inviteController;
    @Autowired PublicOnboardingService publicOnboarding;
    @Autowired DeskController deskController;
    @Autowired PartnerInquiryController partnerInquiryController;
    @Autowired PartnerInquiryService partnerInquiryService;
    @Autowired FinanceController financeController;
    @Autowired VerificationController verificationController;
    @Autowired AgreementController agreementController;
    @Autowired AdminProviderController adminProviderController;
    @Autowired AdminPlatformController adminPlatformController;
    @Autowired MockSmsSender smsSender;
    @Autowired PasswordEncoder passwordEncoder;

    private AuthPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        superAdmin = new AuthPrincipal(adminId, "Test Super Admin", "SUPER_ADMIN", null);
        authenticateAs(superAdmin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(AuthPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + principal.userType()));
        AccessCatalog.forUserType(principal.userType())
                .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private static String randomTenDigitMobile(String prefix2) {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 100_000_000L;
        return prefix2 + String.format("%08d", suffix);
    }

    @Test
    @SuppressWarnings("unchecked")
    void distributorCanOnlySendAndTrackRetailerOnboardingLinksWithoutApprovalsOrSensitiveData() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        UUID hubId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hubs (id, code, name, city, state, status, created_by)
                VALUES (?, ?, 'Dist Access Hub', 'Bengaluru', 'KA', 'ACTIVE', ?)
                """, hubId, "HUB" + suffix, superAdmin.userId());

        UUID distributorId = UUID.randomUUID();
        String distMobile = randomTenDigitMobile("97");
        jdbc.update("""
                INSERT INTO users (id, code, full_name, mobile, password_hash, user_type, status, hub_id, created_by)
                VALUES (?, ?, 'Distributor One', ?, ?, 'MASTER_DISTRIBUTOR', 'ACTIVE', ?, ?)
                """, distributorId, "GFIN" + suffix, distMobile, passwordEncoder.encode("DistPass1"), hubId, superAdmin.userId());
        AuthPrincipal distributor = new AuthPrincipal(distributorId, "Distributor One", "MASTER_DISTRIBUTOR", UUID.randomUUID());

        UUID otherDistId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, code, full_name, mobile, password_hash, user_type, status, hub_id, created_by)
                VALUES (?, ?, 'Other Distributor', ?, ?, 'MASTER_DISTRIBUTOR', 'ACTIVE', ?, ?)
                """, otherDistId, "GFO" + suffix, randomTenDigitMobile("96"), passwordEncoder.encode("DistPass1"), hubId, superAdmin.userId());

        // 1. Distributor CANNOT invite a MASTER_DISTRIBUTOR
        authenticateAs(distributor);
        ApiException roleBlocked = assertThrows(ApiException.class, () -> inviteController.create(
                distributor,
                new InviteController.CreateInviteRequest(
                        "MASTER_DISTRIBUTOR", "Blocked Dist", randomTenDigitMobile("95"), "bd@example.com", null, hubId),
                "127.0.0.1",
                "JUnit"));
        assertEquals(HttpStatus.FORBIDDEN, roleBlocked.getStatus());

        // 2. Distributor CANNOT directly create an outlet with password
        ApiException directCreateBlocked = assertThrows(ApiException.class, () -> deskController.createOutlet(
                distributor,
                new DeskController.CreateOutletRequest(
                        "Direct Retailer", randomTenDigitMobile("94"), "Password123",
                        "Bengaluru", "Karnataka", "560001", "direct@example.com")));
        assertEquals(HttpStatus.FORBIDDEN, directCreateBlocked.getStatus());

        // 3. Distributor CAN create, resend, and revoke a RETAILER onboarding link
        String revocableMobile = randomTenDigitMobile("93");
        Map<String, Object> revocableInvite = inviteController.create(
                distributor,
                new InviteController.CreateInviteRequest(
                        "RETAILER", "Temp Retailer", revocableMobile, "temp." + suffix + "@example.com", null, null),
                "127.0.0.1",
                "JUnit").data();
        UUID revocableId = (UUID) revocableInvite.get("id");
        assertNotNull(revocableInvite.get("onboardingUrl"));
        assertEquals(distributorId, revocableInvite.get("parent_id"));
        assertEquals(hubId, revocableInvite.get("hub_id"));

        Map<String, Object> resent = inviteController.resend(distributor, revocableId, "127.0.0.1", "JUnit").data();
        assertNotNull(resent.get("onboardingUrl"));
        Map<String, Object> revoked = inviteController.revoke(distributor, revocableId, "127.0.0.1", "JUnit").data();
        assertEquals("REVOKED", revoked.get("status"));

        // 4. Distributor CAN send an onboarding invite from an assigned Partner Inquiry (and CANNOT direct-convert)
        String inquiryMobile = randomTenDigitMobile("92");
        authenticateAs(superAdmin);
        Map<String, Object> inquiry = partnerInquiryService.submit(
                "Rajesh Kumar Sharma", inquiryMobile, "rajesh.inq." + suffix + "@example.com", "Bengaluru", "Want to become retailer");
        UUID inquiryId = (UUID) inquiry.get("id");
        UUID hubAdminId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, code, full_name, mobile, password_hash, user_type, status, hub_id, created_by)
                VALUES (?, ?, 'Hub Admin', ?, ?, 'HUBADMIN', 'ACTIVE', ?, ?)
                """, hubAdminId, "GFH" + suffix, randomTenDigitMobile("91"), passwordEncoder.encode("HubPass12"), hubId, superAdmin.userId());
        jdbc.update("INSERT INTO hub_admin_assignments (hub_id, user_id) VALUES (?, ?)", hubId, hubAdminId);
        partnerInquiryService.assignHubAdmin(inquiryId, hubAdminId);
        partnerInquiryService.assignDistributor(inquiryId, distributorId);

        authenticateAs(distributor);
        assertThrows(ApiException.class, () -> partnerInquiryController.convertRetailer(
                distributor,
                inquiryId,
                new PartnerInquiryController.ConvertRetailerRequest("Password123", "Bengaluru", "Karnataka", "560001")));

        Map<String, Object> invitedInquiry = partnerInquiryController.inviteRetailer(
                distributor, inquiryId, "127.0.0.1", "JUnit").data();
        assertEquals("CONVERTED", invitedInquiry.get("status"));
        Map<String, Object> inviteFromInquiry = (Map<String, Object>) invitedInquiry.get("invite");
        assertNotNull(inviteFromInquiry);
        UUID inviteId = (UUID) inviteFromInquiry.get("id");
        String rawToken = String.valueOf(inviteFromInquiry.get("token"));

        // 5. Retailer completes self-service onboarding (OTP -> GPS -> DigiLocker Aadhaar+PAN -> Selfie -> Credentials)
        publicOnboarding.openLink(rawToken, "127.0.0.1", "JUnit");
        publicOnboarding.sendOtp(rawToken, "127.0.0.1", "JUnit");
        String otp = smsSender.lastFor(inquiryMobile).params().get("otp");
        String sessionToken = String.valueOf(
                publicOnboarding.verifyOtp(rawToken, otp, "127.0.0.1", "JUnit").get("sessionToken"));
        publicOnboarding.captureLocation(sessionToken, 12.9716, 77.5946, 12.0, "10.0.0.5", "MobileSafari");

        String dlState = String.valueOf(publicOnboarding.startDigilocker(sessionToken, "10.0.0.5", "MobileSafari").get("state"));
        publicOnboarding.completeDigilocker(sessionToken, dlState, "10.0.0.5", "MobileSafari");
        publicOnboarding.verifyPan(sessionToken, "", "10.0.0.5", "MobileSafari");
        MockMultipartFile selfie = new MockMultipartFile(
                "file", "selfie.jpg", "image/jpeg", "JPEGDATA".getBytes(StandardCharsets.UTF_8));
        publicOnboarding.verifySelfie(sessionToken, selfie, "10.0.0.5", "MobileSafari");
        Map<String, Object> credDone = publicOnboarding.setCredentials(
                sessionToken, "RetailerPass1", "482917", "10.0.0.5", "MobileSafari");
        UUID retailerId = UUID.fromString(String.valueOf(credDone.get("userId")));
        AuthPrincipal retailer = new AuthPrincipal(retailerId, "Rajesh Kumar Sharma", "RETAILER", UUID.randomUUID());

        // 6. Verify Invite redaction: Distributor sees NO identity PII or event GPS/IP/detail; SuperAdmin sees full review metadata
        authenticateAs(distributor);
        Map<String, Object> distInviteView = inviteController.get(distributor, inviteId).data();
        Map<String, Object> distIdentity = (Map<String, Object>) distInviteView.get("identity");
        assertTrue(distIdentity.isEmpty(), "Distributor must not receive Aadhaar/PAN/DOB/scores in invite identity");
        List<Map<String, Object>> distEvents = (List<Map<String, Object>>) distInviteView.get("events");
        assertFalse(distEvents.isEmpty());
        for (Map<String, Object> ev : distEvents) {
            assertFalse(ev.containsKey("latitude"));
            assertFalse(ev.containsKey("longitude"));
            assertFalse(ev.containsKey("detail"));
            assertFalse(ev.containsKey("ip"));
            assertFalse(ev.containsKey("user_agent"));
        }

        List<Map<String, Object>> distInviteList = inviteController.list(distributor, null, null, null).data();
        assertTrue(distInviteList.stream().allMatch(r -> ((Map<?, ?>) r.get("identity")).isEmpty()));

        authenticateAs(superAdmin);
        Map<String, Object> adminInviteView = inviteController.get(superAdmin, inviteId).data();
        Map<String, Object> adminIdentity = (Map<String, Object>) adminInviteView.get("identity");
        assertNotNull(adminIdentity.get("aadhaarLast4"));

        // 7. Distributor CANNOT access another distributor's invite
        Map<String, Object> otherInvite = inviteController.create(
                superAdmin,
                new InviteController.CreateInviteRequest(
                        "RETAILER", "Other Net Retailer", randomTenDigitMobile("90"), "other." + suffix + "@example.com", otherDistId, null),
                "127.0.0.1",
                "JUnit").data();
        UUID otherInviteId = (UUID) otherInvite.get("id");

        authenticateAs(distributor);
        assertThrows(ApiException.class, () -> inviteController.get(distributor, otherInviteId));

        // 8. Distributor CANNOT approve/review invites, KYC, DigiLocker docs, agreements, activate retailers, reset passwords, or view admin logs
        assertThrows(AccessDeniedException.class, () -> inviteController.review(
                distributor, inviteId, new InviteController.ReviewInviteRequest(true, "looks ok"), "127.0.0.1", "JUnit"));
        assertThrows(AccessDeniedException.class, () -> verificationController.forUser(retailerId));
        assertThrows(AccessDeniedException.class, () -> verificationController.listDigilockerDocuments(retailerId));
        assertThrows(AccessDeniedException.class, () -> verificationController.reviewPersonal(
                distributor, retailerId, new VerificationController.BusinessReviewRequest(true, "ok")));
        assertThrows(AccessDeniedException.class, () -> verificationController.reviewFace(
                distributor, retailerId, new VerificationController.FaceReviewRequest(true, "ok")));
        assertThrows(AccessDeniedException.class, () -> agreementController.adminGetForUser(distributor, retailerId));
        assertThrows(AccessDeniedException.class, () -> financeController.activate(distributor, retailerId));
        assertThrows(AccessDeniedException.class, () -> adminProviderController.listGlobalEmeraldLogs(null, null, null, null, 10));
        assertThrows(AccessDeniedException.class, () -> adminProviderController.listUserEmeraldLogs(retailerId, 10));
        assertThrows(AccessDeniedException.class, () -> adminPlatformController.txn(UUID.randomUUID()));
        assertThrows(ApiException.class, () -> deskController.kycOne(distributor, retailerId));
        assertThrows(ApiException.class, () -> deskController.outletPassword(
                distributor, retailerId, new DeskController.PasswordRequest("NewPass123")));

        // 9. Desk outlet detail for Distributor strips sensitive KYC fields, banks, and GPS locations while keeping high-level verification flags
        Map<String, Object> distOutlet = deskController.outlet(distributor, retailerId).data();
        assertFalse(distOutlet.containsKey("aadhaar_pan_linked"));
        Map<String, Object> distKyc = (Map<String, Object>) distOutlet.get("kyc");
        assertNotNull(distKyc);
        assertEquals("VERIFIED", distKyc.get("identity_status"));
        assertNotNull(distKyc.get("personal_identity_verified"));
        assertNotNull(distKyc.get("business_verified"));
        assertNull(distKyc.get("aadhaar_last4"));
        assertNull(distKyc.get("pan_last4"));
        assertNull(distKyc.get("aadhaar_pan_linked"));
        assertNull(distKyc.get("gstin"));
        assertNull(distKyc.get("banks"));
        assertNull(distKyc.get("locations"));
        assertNull(distKyc.get("rejection_reason"));
        assertNull(distKyc.get("business_review_note"));
        Map<String, Object> distActivation = (Map<String, Object>) distOutlet.get("activation");
        assertEquals(false, distActivation.get("canActivate"));

        // 10. Retailer uploads an activation payment proof -> Distributor CANNOT view retailer's uploaded document
        authenticateAs(retailer);
        MockMultipartFile receipt = new MockMultipartFile(
                "proof", "utr_receipt.png", "image/png", "PNGPROOF".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> actReq = financeController.submitActivation(
                retailer, null, new BigDecimal("999.00"), "UTR987654321", "Paid via UPI", receipt).data();
        UUID requestId = (UUID) actReq.get("id");

        authenticateAs(distributor);
        List<Map<String, Object>> distFinanceRows = financeController.list(
                distributor, null, null, retailerId, null).data();
        assertEquals(1, distFinanceRows.size());
        assertNull(distFinanceRows.get(0).get("proof_filename"),
                "Distributor must not receive proof_filename for documents uploaded by retailer");
        ApiException proofBlocked = assertThrows(ApiException.class,
                () -> financeController.proof(distributor, requestId));
        assertEquals(HttpStatus.FORBIDDEN, proofBlocked.getStatus());
    }
}
