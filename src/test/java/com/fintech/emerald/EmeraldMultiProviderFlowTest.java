package com.fintech.emerald;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.biometric.BiometricActivationService;
import com.fintech.cashout.CashoutService;
import com.fintech.finance.RetailerBankService;
import com.fintech.identity.DeskService;
import com.fintech.ledger.TransactionStatusVerificationService;
import com.fintech.ledger.WalletService;
import com.fintech.onboarding.ReverifyService;
import com.fintech.payout.PayoutService;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ProviderOnboardingService;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.sms.MockSmsSender;
import com.fintech.verification.AadhaarValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * End-to-end integration test covering Steps 1–6 of the Emerald + Multi-Provider architecture:
 * Admin onboarding (default pipe INSTANTPAY), white-label Biometric KYC, live activation sync,
 * multi-provider Round-Robin / Primary-Fallback routing, UPI Cashout QR + webhook settlement,
 * Payout 7-transaction gate + HOLD -> SUCCESS / REVERSED ledger accounting, and manual Verify Status.
 */
@SpringBootTest(properties = {
        "app.sms.provider=mock",
        "app.surepass.mode=mock",
        "app.esign.mode=mock",
        "app.emerald.client-id=EMD_TEST_CLIENT",
        "app.emerald.client-secret=SEC_TEST_SECRET_KEY_123456",
        "app.emerald.entity-code=ENT000002",
        "app.emerald.default-pipe=INSTANTPAY",
        "app.emerald.callback-secret=CB_TEST_SECRET_999",
        "app.emerald.payout-status-path=/payout/v1/status",
        "app.payout.min-cashout-txns=7"
})
@ActiveProfiles("test")
class EmeraldMultiProviderFlowTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static HttpServer mockEmeraldServer;
    static final Map<String, Map<String, Object>> capturedRequests = new ConcurrentHashMap<>();
    static final Map<String, Map<String, String>> capturedHeaders = new ConcurrentHashMap<>();
    static final AtomicReference<String> mockQrStatusMode = new AtomicReference<>("PENDING");
    static final AtomicReference<String> mockPayoutPayOutcome = new AtomicReference<>("2001"); // PENDING initially
    static final AtomicReference<String> mockPayoutStatusOutcome = new AtomicReference<>("SUCCESS");

    private static final String SAMPLE_PID_XML = """
            <PidData>
              <Resp errCode="0" errInfo="Success." fCount="1" fType="2" nmPoints="46" qScore="85"/>
              <DeviceInfo dpId="MANTRA.MSIPL" rdsId="MANTRA.AND.001" rdsVer="1.0.8" mi="MFS110"
                          mc="CERT_B64" dc="dc-uuid-1234">
                <additional_info>
                  <Param name="srno" value="99881122"/>
                  <Param name="sysid" value="SYS001"/>
                  <Param name="ts" value="2026-11-13T12:00:00+05:30"/>
                </additional_info>
              </DeviceInfo>
              <Skey ci="20281029">SKEY_BASE64</Skey>
              <Hmac>HMAC_BASE64</Hmac>
              <Data type="X">PID_DATA_BASE64</Data>
            </PidData>
            """;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        startMockEmeraldIfNeeded();
        String mockBaseUrl = "http://127.0.0.1:" + mockEmeraldServer.getAddress().getPort();
        registry.add("app.emerald.base-url", () -> mockBaseUrl);

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

    @AfterAll
    static void stopServer() {
        if (mockEmeraldServer != null) {
            mockEmeraldServer.stop(0);
        }
    }

    private static synchronized void startMockEmeraldIfNeeded() throws IOException {
        if (mockEmeraldServer != null) return;
        ObjectMapper om = new ObjectMapper();
        mockEmeraldServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockEmeraldServer.createContext("/", (HttpExchange ex) -> {
            String path = ex.getRequestURI().getPath();
            String bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> reqJson = bodyStr.isBlank()
                    ? Map.of()
                    : om.readValue(bodyStr, new TypeReference<>() {});
            capturedRequests.put(path, reqJson);

            Map<String, String> hdrs = new ConcurrentHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) hdrs.put(k.toLowerCase(Locale.ROOT), v.get(0));
            });
            capturedHeaders.put(path, hdrs);

            String resp;
            int code = 200;
            switch (path) {
                case "/core/v1/outlets/create" -> resp = """
                        {
                          "statuscode": "2000",
                          "success": true,
                          "message": "Outlet created successfully",
                          "data": {
                            "outletCode": "OUT_02_99",
                            "pipes": [
                              {
                                "pipeCode": "INSTANTPAY",
                                "pipeOutletCode": "IPAY_889900",
                                "onboardingStatus": "ONBOARDED",
                                "kycStatus": "APPROVED",
                                "active": true
                              }
                            ]
                          }
                        }
                        """;
                case "/core/v1/outlets/bank-account" -> resp = """
                        {
                          "statuscode": "2000",
                          "success": true,
                          "message": "Bank account synced",
                          "data": {
                            "pipeSyncStatus": "SYNCED",
                            "pipeSyncRemarks": "Synced with InstantPay"
                          }
                        }
                        """;
                case "/core/v1/outlets/details" -> resp = """
                        {
                          "statuscode": "2000",
                          "success": true,
                          "message": "Outlet details",
                          "data": {
                            "outletCode": "OUT_02_99",
                            "pipes": [
                              {
                                "pipeCode": "INSTANTPAY",
                                "pipeOutletCode": "IPAY_889900",
                                "onboardingStatus": "ONBOARDED",
                                "kycStatus": "FULL_KYC",
                                "active": true
                              }
                            ],
                            "bankAccounts": [
                              {
                                "accountHolderName": "Ravi Sharma",
                                "bankAccountNo": "501002349988",
                                "bankIfsc": "HDFC0001234",
                                "primary": true,
                                "active": true,
                                "status": "ACTIVE",
                                "pipeSyncStatus": "SYNCED"
                              }
                            ]
                          }
                        }
                        """;
                case "/core/v1/outlets/kyc-request" -> resp = """
                        {
                          "statuscode": "EMD00000",
                          "success": true,
                          "message": "Biometric KYC initiated",
                          "data": {
                            "referenceKey": "REF_BIO_778899",
                            "pidWadh": "E0jzJ/P8UopUHAieZn8CKqS4WPMi5ZSYXgfnlfkWjrc=",
                            "referenceKeyType": "OTP_FREE"
                          }
                        }
                        """;
                case "/core/v1/outlets/biometric-kyc" -> resp = """
                        {
                          "statuscode": "EMD00000",
                          "success": true,
                          "message": "Biometric eKYC completed successfully",
                          "data": {
                            "kycStatus": "FULL_KYC",
                            "bankSyncStatus": "SYNCED"
                          }
                        }
                        """;
                case "/upicash/v1/uatm/generate-qr" -> {
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "REF1"));
                    String amt = String.valueOf(reqJson.getOrDefault("amount", "500"));
                    resp = """
                            {
                              "status": "UPITOCASH00000",
                              "success": true,
                              "message": "QR generated",
                              "data": {
                                "txnId": "EMD_UATM_%s",
                                "externalRef": "%s",
                                "qrStatus": "PENDING",
                                "qrString": "upi://pay?pa=merchant@icici&am=%s&tr=%s",
                                "displayExpirySec": 90,
                                "payableValue": "%s",
                                "transactionValue": "%s"
                              }
                            }
                            """.formatted(extRef, extRef, amt, extRef, amt, amt);
                }
                case "/upicash/v1/uatm/qr-status" -> {
                    String qrMode = mockQrStatusMode.get();
                    String refNo = String.valueOf(reqJson.getOrDefault("referenceNo", "REF1"));
                    resp = """
                            {
                              "status": "UPITOCASH00000",
                              "success": true,
                              "message": "QR status fetched",
                              "data": {
                                "txnId": "EMD_UATM_%s",
                                "qrStatus": "%s",
                                "qrStatusMsg": "Status %s",
                                "amount": "500.00",
                                "utr": "431899001122",
                                "payer": {
                                  "vpa": "customer@okaxis",
                                  "name": "AmitVerma"
                                }
                              }
                            }
                            """.formatted(refNo, qrMode, qrMode);
                }
                case "/payout/v1/pay" -> {
                    String pCode = mockPayoutPayOutcome.get();
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "POUT1"));
                    boolean ok = "2000".equals(pCode);
                    resp = """
                            {
                              "statuscode": "%s",
                              "success": %s,
                              "message": "Payout submitted",
                              "data": {
                                "txnId": "EMD_POUT_%s",
                                "transactionId": "IPAY_ORD_123",
                                "utr": "%s"
                              }
                            }
                            """.formatted(pCode, ok, extRef, ok ? "UTR99887766" : "");
                }
                case "/payout/v1/status" -> {
                    String st = mockPayoutStatusOutcome.get();
                    resp = """
                            {
                              "statuscode": "2000",
                              "success": true,
                              "message": "Status fetched",
                              "data": {
                                "status": "%s",
                                "utr": "UTR_RECON_554433"
                              }
                            }
                            """.formatted(st);
                }
                default -> {
                    code = 404;
                    resp = "{\"statuscode\":\"404\",\"message\":\"Not found\"}";
                }
            }

            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        mockEmeraldServer.start();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ProviderOnboardingService providerOnboarding;
    @Autowired ServiceProviderRouter router;
    @Autowired BiometricActivationService biometricService;
    @Autowired CashoutService cashoutService;
    @Autowired PayoutService payoutService;
    @Autowired EmeraldCallbackService callbackService;
    @Autowired TransactionStatusVerificationService verifyStatusService;
    @Autowired RetailerBankService retailerBankService;
    @Autowired WalletService walletService;
    @Autowired DeskService deskService;
    @Autowired EmeraldAuditLogService emeraldAuditLogs;
    @Autowired ReverifyService reverifyService;
    @Autowired MockSmsSender smsSender;
    @Autowired FieldCipher fieldCipher;

    private AuthPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        capturedRequests.clear();
        capturedHeaders.clear();
        mockQrStatusMode.set("PENDING");
        mockPayoutPayOutcome.set("2001");
        mockPayoutStatusOutcome.set("SUCCESS");

        // Ensure UPI_CASHOUT, AEPS, and PAYOUT platform service gates are enabled
        jdbc.update("UPDATE platform_services SET enabled = TRUE WHERE code IN ('UPI_CASHOUT', 'AEPS', 'PAYOUT')");
        jdbc.update("UPDATE service_routing_policies SET strategy = 'ROUND_ROBIN', active = TRUE");

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
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("wallet.view"),
                                new SimpleGrantedAuthority("users.onboard"),
                                new SimpleGrantedAuthority("settings.manage"))));
    }

    private static String validAadhaar(String elevenDigits) {
        for (int d = 0; d <= 9; d++) {
            String candidate = elevenDigits + d;
            if (AadhaarValidator.verhoeffValid(candidate)) return candidate;
        }
        throw new IllegalStateException("No Verhoeff digit");
    }

    private static String randomMobile() {
        long n = 9000000000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 999999999L);
        return String.valueOf(n);
    }

    private AuthPrincipal createVerifiedRetailerWithBank(String ignoredSeed, String aadhaar12) {
        String mobile = randomMobile();
        UUID userId = UUID.randomUUID();
        String code = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO users (id, user_type, full_name, mobile, email, password_hash, status, code)
                VALUES (?, 'RETAILER', 'Ravi Sharma', ?, ?, ?, 'ACTIVE', ?)
                """,
                userId, mobile, "ravi." + mobile + "@gfinpay.test", passwordEncoder.encode("Secret@123"), code);

        walletService.createForUser(userId);
        // Ensure wallet is ACTIVE
        jdbc.update("UPDATE wallets SET status = 'ACTIVE' WHERE user_id = ?", userId);

        String last4 = aadhaar12.substring(8);
        jdbc.update("""
                INSERT INTO agent_kyc_profiles
                    (user_id, kyc_status, identity_status, personal_status, personal_identity_verified,
                     business_status, business_verified, agreement_status, aadhaar_last4, pan_last4,
                     shop_name, gstin, work_latitude, work_longitude,
                     personal_details, shop_address)
                VALUES (?, 'COMPLETED', 'VERIFIED', 'PERSONAL_VERIFIED', TRUE,
                        'BUSINESS_VERIFIED', TRUE, 'SIGNED', ?, '4321',
                        'Sharma Fin Point', '29ABCDE1234F1Z5', 12.971600, 77.594600,
                        '{"fullName":"Ravi Sharma","dob":"1992-05-14","gender":"M","panNumber":"ABCDE4321F"}'::jsonb,
                        '{"line1":"Shop 12 MG Road","area":"Indiranagar","city":"Bengaluru","district":"Bengaluru Urban","state":"Karnataka","pincode":"560038"}'::jsonb)
                """, userId, last4);

        AuthPrincipal retailer = new AuthPrincipal(userId, "Ravi Sharma", "RETAILER", null);

        // Verify Surepass bank validation rejects mismatched holder (...0000) and non-existent account (...9999)
        ApiException mismatchEx = assertThrows(
                ApiException.class,
                () -> retailerBankService.verifyAndCreate(retailer, "501002340000", "BARB0DAMOHA", "SAVINGS"));
        assertEquals("BANK_NAME_MISMATCH", mismatchEx.getCode());

        ApiException nonExistentEx = assertThrows(
                ApiException.class,
                () -> retailerBankService.verifyAndCreate(retailer, "501002349999", "BARB0DAMOHA", "SAVINGS"));
        assertEquals("BANK_VERIFICATION_FAILED", nonExistentEx.getCode());

        // Verify & create bank account using ONLY Account Number + IFSC (avoiding 0000 / 9999 test sentinels)
        String safeSuffix = mobile.substring(6);
        if ("0000".equals(safeSuffix) || "9999".equals(safeSuffix)) {
            safeSuffix = "1234";
        }
        Map<String, Object> verifiedBank = retailerBankService.verifyAndCreate(
                retailer, "50100234" + safeSuffix, "BARB0DAMOHA", "SAVINGS");
        assertEquals("VERIFIED", verifiedBank.get("verification_status"));
        assertEquals("Ravi Sharma", verifiedBank.get("verified_name"));
        assertEquals("BARB Bank", verifiedBank.get("bank_name"));
        assertEquals("MAIN BRANCH", verifiedBank.get("branch_name"));
        assertNotNull(verifiedBank.get("imps_ref_no"));
        @SuppressWarnings("unchecked")
        Map<String, Object> savedIfscDetails = (Map<String, Object>) verifiedBank.get("ifsc_details");
        assertFalse(savedIfscDetails.containsKey("contact"));

        return retailer;
    }

    @Test
    void fullEndToEndEmeraldOnboardingBiometricCashoutWebhookPayoutAndVerifyFlow() {
        String aadhaar12 = validAadhaar("49911866524");
        AuthPrincipal retailer = createVerifiedRetailerWithBank("9876501234", aadhaar12);
        UUID userId = retailer.userId();

        // 1. Admin Onboards Retailer to Emerald (Default Pipe = INSTANTPAY)
        setAuth(superAdmin);
        Map<String, Object> adminView = providerOnboarding.onboardToEmerald(superAdmin, userId, aadhaar12, null, null);
        assertNotNull(adminView);

        // Verify Emerald Create Outlet used pipeCode = "INSTANTPAY" and HMAC millisecond headers
        Map<String, Object> createPayload = capturedRequests.get("/core/v1/outlets/create");
        assertNotNull(createPayload);
        assertEquals("INSTANTPAY", createPayload.get("pipeCode"));
        assertEquals(aadhaar12, createPayload.get("aadhaar"));
        assertEquals("ENT000002", capturedHeaders.get("/core/v1/outlets/create").get("x-entity-code"));
        assertTrue(capturedHeaders.get("/core/v1/outlets/create").get("x-timestamp").length() >= 13,
                "X-Timestamp must be Unix epoch milliseconds");

        // Verify CSV export contains 24 columns matching Vendor 2 Onboarding.xlsx
        String csv = providerOnboarding.exportVendor2Csv(userId);
        String[] csvLines = csv.trim().split("\n");
        assertEquals(2, csvLines.length);
        assertEquals(24, csvLines[0].split(",").length);

        // 2. Before Biometric KYC: Retailer sees white-labeled biometricRequired = true & cashServicesActive = false
        //    (Pass refresh = false here before biometric so mock /outlets/details FULL_KYC stub isn't queried prematurely)
        setAuth(retailer);
        Map<String, Object> preBio = biometricService.getCashActivation(retailer, false);
        assertTrue((Boolean) preBio.get("terminalProvisioned"));
        assertTrue((Boolean) preBio.get("biometricRequired"));
        assertFalse((Boolean) preBio.get("cashServicesActive"));
        // White-label check: zero "Emerald" or "InstantPay" in retailer response JSON
        String preBioStr = preBio.toString().toLowerCase(Locale.ROOT);
        assertFalse(preBioStr.contains("emerald"));
        assertFalse(preBioStr.contains("instantpay"));

        // Attempting UPI Cashout before Biometric KYC must be blocked cleanly
        ApiException blockedEx = assertThrows(
                ApiException.class,
                () -> cashoutService.generateQr(retailer, 500, "9123456789", "Customer", "12.9716", "77.5946"));
        assertEquals("BIOMETRIC_KYC_REQUIRED", blockedEx.getCode());

        // 3. Step 2: Retailer Completes White-Label Biometric KYC
        String channelKey = String.valueOf(preBio.get("pendingChannelKey"));
        Map<String, Object> initRes = biometricService.initiateBiometric(retailer, channelKey, null);
        UUID bioSessionId = (UUID) initRes.get("sessionId");
        assertNotNull(bioSessionId);

        Map<String, Object> postBio = biometricService.submitBiometric(
                retailer, channelKey, bioSessionId, SAMPLE_PID_XML, "12.9716", "77.5946");
        assertTrue((Boolean) postBio.get("biometricVerified"));
        assertTrue((Boolean) postBio.get("cashServicesActive"));
        assertTrue((Boolean) postBio.get("upiCashoutActive"));

        // Verify 26 biometric parameters were sent to /core/v1/outlets/biometric-kyc
        @SuppressWarnings("unchecked")
        Map<String, Object> sentBioData = (Map<String, Object>) capturedRequests.get("/core/v1/outlets/biometric-kyc").get("biometricData");
        assertEquals(26, sentBioData.size());
        assertEquals(aadhaar12, sentBioData.get("encryptedAadhaar"));

        // 4. Step 4 & Step 5: Generate 7 UPI Cashout QRs & Settle via Emerald Webhook (with Idempotency check!)
        for (int i = 1; i <= 7; i++) {
            // Before 7th completed cashout, Payout eligibility must be false!
            Map<String, Object> eligBefore = payoutService.checkEligibility(retailer);
            assertFalse((Boolean) eligBefore.get("eligible"), "Payout must be blocked at count=" + (i - 1));
            assertEquals(i - 1, eligBefore.get("completedCashoutCount"));

            Map<String, Object> qr = cashoutService.generateQr(retailer, 500, "9123456780", "Walkin " + i, "12.9716", "77.5946");
            String extRef = String.valueOf(qr.get("refid"));
            assertEquals("AWAITING_PAYMENT", qr.get("status"));

            // Send Emerald Webhook with WRONG secret -> must reject (authorized=false)
            String webhookJson = """
                    {
                      "productCode": "UPICASH",
                      "txnType": "QR",
                      "txnId": "EMD_UATM_%s",
                      "externalRef": "%s",
                      "outletCode": "OUT_02_99",
                      "status": "SUCCESS",
                      "amount": "500.00",
                      "utr": "UTR_CASH_%d",
                      "payer": { "name": "Walkin %d", "vpa": "walkin%d@upi" }
                    }
                    """.formatted(extRef, extRef, i, i, i);

            EmeraldCallbackService.CallbackOutcome badSecret = callbackService.handleInbound("WRONG_SECRET", webhookJson);
            assertFalse(badSecret.authorized());

            // Send valid Emerald Webhook -> settles wallet (+500)
            EmeraldCallbackService.CallbackOutcome accepted = callbackService.handleInbound("CB_TEST_SECRET_999", webhookJson);
            assertTrue(accepted.authorized());
            assertFalse(accepted.duplicate());
            assertNotNull(accepted.transactionId());

            // Replay exact same webhook -> deduplicated on (txnId, status), wallet NOT double-credited!
            EmeraldCallbackService.CallbackOutcome duplicate = callbackService.handleInbound("CB_TEST_SECRET_999", webhookJson);
            assertTrue(duplicate.authorized());
            assertTrue(duplicate.duplicate());
        }

        // Wallet balance after 7 x ₹500 cashouts must be exactly ₹3,500.00
        WalletService.WalletInfo walletAfterCashouts = walletService.getByUser(userId);
        assertMoney("3500.00", walletAfterCashouts.available());

        // 5. Payout Eligibility is NOW TRUE (completedCashoutCount == 7, recent7DayCashoutCount == 7, bankSynced == true)
        Map<String, Object> eligAfter = payoutService.checkEligibility(retailer);
        assertTrue((Boolean) eligAfter.get("eligible"));
        assertEquals(7, eligAfter.get("completedCashoutCount"));

        // Initiate ₹1,000 Payout -> upstream returns 2001 PENDING -> wallet places ₹1,000 HOLD (available = ₹2,500, hold = ₹1,000)
        Map<String, Object> payout = payoutService.initiatePayout(
                retailer, new BigDecimal("1000"), "IMPS", "Settlement 1", "12.9716", "77.5946");
        assertEquals("PENDING", payout.get("status"));
        UUID payoutTxnId = (UUID) payout.get("transactionId");
        String payoutRef = String.valueOf(payout.get("reference"));

        // Verify strict Payout v1 JSON body contract: NO payer, alertEmail, purpose, or outletCode in JSON body!
        Map<String, Object> payoutBody = capturedRequests.get("/payout/v1/pay");
        assertFalse(payoutBody.containsKey("payer"));
        assertFalse(payoutBody.containsKey("alertEmail"));
        assertFalse(payoutBody.containsKey("purpose"));
        assertFalse(payoutBody.containsKey("outletCode"));
        assertEquals("OUT_02_99", capturedHeaders.get("/payout/v1/pay").get("x-outlet-code"));

        WalletService.WalletInfo walletDuringHold = walletService.getByUser(userId);
        assertMoney("2500.00", walletDuringHold.available());
        assertMoney("1000.00", walletDuringHold.hold());

        // 6. Step 6: Manual Status Verification reconciles PENDING payout -> SUCCESS and consumes HOLD!
        Map<String, Object> verifyRes = verifyStatusService.verifyForDesk(retailer, payoutTxnId);
        assertTrue((Boolean) verifyRes.get("changed"));
        assertEquals("SUCCESS", verifyRes.get("currentState"));

        // White-label check on desk transaction view: partner_code must be stripped!
        Map<String, Object> deskTxn = deskService.getTransaction(retailer, payoutTxnId);
        assertFalse(deskTxn.containsKey("partner_code"));

        WalletService.WalletInfo walletAfterSettle = walletService.getByUser(userId);
        assertMoney("2500.00", walletAfterSettle.available());
        assertMoney("0.00", walletAfterSettle.hold());

        // 7. Post-settlement REVERSED webhook on Payout refunds ₹1,000 back to retailer's available wallet!
        String reversalWebhook = """
                {
                  "productCode": "PAYOUT",
                  "txnType": "IMPS",
                  "txnId": "EMD_POUT_%s",
                  "externalRef": "%s",
                  "outletCode": "OUT_02_99",
                  "status": "REVERSED",
                  "amount": "1000.00",
                  "message": "Emerald InstantPay beneficiary bank returned funds"
                }
                """.formatted(payoutRef, payoutRef);
        EmeraldCallbackService.CallbackOutcome revOutcome = callbackService.handleInbound("CB_TEST_SECRET_999", reversalWebhook);
        assertTrue(revOutcome.authorized());
        assertFalse(revOutcome.duplicate());

        WalletService.WalletInfo walletAfterReversal = walletService.getByUser(userId);
        assertMoney("3500.00", walletAfterReversal.available());
        assertMoney("0.00", walletAfterReversal.hold());

        // Also exercise Admin live status refresh (Step 3: /core/v1/outlets/details)
        setAuth(superAdmin);
        providerOnboarding.refreshEmeraldStatus(userId);

        // 8. Verify complete ASYNC Emerald Request & Response Audit Trail across every outbound call & inbound webhook!
        assertTrue(emeraldAuditLogs.awaitIdle(Duration.ofSeconds(5)),
                "Async Emerald audit executor should drain all queued log tasks cleanly");
        List<Map<String, Object>> userLogs = emeraldAuditLogs.listForUser(userId, 100);
        assertTrue(userLogs.size() >= 25,
                "Expected full trace of onboarding, bank sync, eKYC, 7 QRs, webhooks, payout & status check, got " + userLogs.size());

        // Verify outbound traces exist with both request & response bodies and sanitized headers/biometrics
        assertHasLog(userLogs, "OUTBOUND", "STEP1_ONBOARD:OUTLET_CREATE", "/core/v1/outlets/create", 200);
        assertHasLog(userLogs, "OUTBOUND", "STEP1_BANK_SYNC:OUTLET_ADD_BANK", "/core/v1/outlets/bank-account", 200);
        assertHasLog(userLogs, "OUTBOUND", "STEP2_EKYC_INIT:BIOMETRIC_INIT", "/core/v1/outlets/kyc-request", 200);
        Map<String, Object> bioSubmitLog = assertHasLog(
                userLogs, "OUTBOUND", "STEP2_EKYC_SUBMIT:BIOMETRIC_SUBMIT", "/core/v1/outlets/biometric-kyc", 200);
        assertHasLog(userLogs, "OUTBOUND", "STEP3_STATUS_SYNC:OUTLET_DETAILS", "/core/v1/outlets/details", 200);
        assertHasLog(userLogs, "OUTBOUND", "STEP4_UPI_QR:UATM_GENERATE_QR", "/upicash/v1/uatm/generate-qr", 200);
        assertHasLog(userLogs, "OUTBOUND", "STEP4_PAYOUT:PAYOUT_PAY", "/payout/v1/pay", 200);
        assertHasLog(userLogs, "OUTBOUND", "STEP6_PAYOUT_STATUS:PAYOUT_STATUS", "/payout/v1/status", 200);

        // Verify inbound webhook traces (unauthorized 401, accepted 200, duplicate 200, reversal 200)
        assertHasLog(userLogs, "INBOUND_WEBHOOK", "WEBHOOK_UPICASH_QR", "/emerald/v1/callback", 401);
        assertHasLog(userLogs, "INBOUND_WEBHOOK", "WEBHOOK_UPICASH_QR", "/emerald/v1/callback", 200);
        assertTrue(userLogs.stream().anyMatch(l ->
                "INBOUND_WEBHOOK".equals(l.get("direction"))
                        && String.valueOf(l.get("providerMessage")).startsWith("Duplicate event ignored")));
        assertHasLog(userLogs, "INBOUND_WEBHOOK", "WEBHOOK_PAYOUT_IMPS", "/emerald/v1/callback", 200);

        // Verify secret & biometric redaction on persisted biometric-kyc trace while preserving RD metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> bioReqHeaders = (Map<String, Object>) bioSubmitLog.get("requestHeaders");
        assertEquals("EMD_TEST_CLIENT", bioReqHeaders.get("X-Client-Id"));
        assertFalse(String.valueOf(bioReqHeaders.get("X-Client-Secret")).contains("SEC_TEST_SECRET_KEY_123456"));
        assertTrue(String.valueOf(bioReqHeaders.get("X-Client-Secret")).contains("****3456"));

        String bioReqBody = String.valueOf(bioSubmitLog.get("requestBody"));
        assertFalse(bioReqBody.contains(aadhaar12), "Raw 12-digit Aadhaar must not be persisted in emerald_api_logs");
        assertFalse(bioReqBody.contains("PID_DATA_BASE64"), "Raw biometric PID ciphertext must be redacted");
        assertFalse(bioReqBody.contains("SKEY_BASE64"), "Raw biometric sessionKey must be redacted");
        assertFalse(bioReqBody.contains("HMAC_BASE64"), "Raw biometric HMAC must be redacted");
        assertTrue(bioReqBody.contains("MANTRA.MSIPL"), "RD device metadata must remain intact for Emerald support");
        assertTrue(String.valueOf(bioSubmitLog.get("responseBody")).contains("FULL_KYC"));
    }

    private static Map<String, Object> assertHasLog(
            List<Map<String, Object>> logs,
            String direction,
            String flow,
            String endpointPath,
            int expectedHttpStatus) {
        return logs.stream()
                .filter(l -> direction.equals(l.get("direction"))
                        && flow.equals(l.get("flow"))
                        && endpointPath.equals(l.get("endpointPath"))
                        && Integer.valueOf(expectedHttpStatus).equals(l.get("httpStatus")))
                .findFirst()
                .map(l -> {
                    assertNotNull(l.get("requestBody"), "requestBody missing for " + flow + " " + endpointPath);
                    assertNotNull(l.get("responseBody"), "responseBody missing for " + flow + " " + endpointPath);
                    assertFalse(String.valueOf(l.get("responseBody")).isBlank(),
                            "responseBody blank for " + flow + " " + endpointPath);
                    return l;
                })
                .orElseThrow(() -> new AssertionError(
                        "Missing emerald_api_logs row for direction=" + direction + " flow=" + flow
                                + " path=" + endpointPath + " status=" + expectedHttpStatus));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "Expected " + expected + " but was null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "Expected money " + expected + " but was " + actual.toPlainString());
    }

    @Test
    void multiProviderRouterSupportsRoundRobinAndPrimaryFallback() {
        String aadhaar12 = validAadhaar("58822977635");
        AuthPrincipal retailer = createVerifiedRetailerWithBank("9876509988", aadhaar12);
        UUID userId = retailer.userId();

        // Provision 2 FULL_KYC channels for the same retailer (e.g. EMERALD/INSTANTPAY priority 10 & VENDOR2/BANKB priority 20)
        UUID ch1 = jdbc.queryForObject(
                "SELECT id FROM service_provider_channels WHERE provider_code = 'EMERALD' AND pipe_code = 'INSTANTPAY'",
                UUID.class);
        UUID ch2 = UUID.randomUUID();
        String ch2Key = "ch_cash_" + ch2.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO service_provider_channels
                    (id, channel_key, provider_code, pipe_code, display_label, supports_upi_cashout, active, priority_order)
                VALUES (?, ?, 'VENDOR_TWO', ?, 'Secondary Switch', TRUE, TRUE, 20)
                """, ch2, ch2Key, "PIPE_" + ch2.toString().substring(0, 6).toUpperCase(Locale.ROOT));

        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_merchant_accounts (id, user_id, provider_code, entity_code, outlet_code, status)
                VALUES (?, ?, 'EMERALD', 'ENT000002', 'OUT_RR_1', 'ACTIVE'),
                       (?, ?, 'VENDOR_TWO', 'V2_ENT', 'OUT_RR_2', 'ACTIVE')
                """, m1, userId, m2, userId);

        jdbc.update("""
                INSERT INTO provider_pipe_activations
                    (id, user_id, channel_id, merchant_account_id, pipe_outlet_code, onboarding_status, kyc_status, bank_sync_status, enabled_for_retailer)
                VALUES (?, ?, ?, ?, 'P1', 'ONBOARDED', 'FULL_KYC', 'SYNCED', TRUE),
                       (?, ?, ?, ?, 'P2', 'ONBOARDED', 'FULL_KYC', 'SYNCED', TRUE)
                """, UUID.randomUUID(), userId, ch1, m1, UUID.randomUUID(), userId, ch2, m2);

        // 1. ROUND_ROBIN alternates cleanly across active FULL_KYC channels
        router.updatePolicy("UPI_CASHOUT", "ROUND_ROBIN", true);
        ServiceProviderRouter.RoutedChannel r1 = router.selectChannel(userId, "UPI_CASHOUT");
        ServiceProviderRouter.RoutedChannel r2 = router.selectChannel(userId, "UPI_CASHOUT");
        ServiceProviderRouter.RoutedChannel r3 = router.selectChannel(userId, "UPI_CASHOUT");
        assertFalse(r1.channelId().equals(r2.channelId()), "Round-robin must alternate between the two active channels");
        assertEquals(r1.channelId(), r3.channelId(), "3rd call wraps back to 1st channel");

        // 2. PRIMARY_FALLBACK always picks lowest priority_order (EMERALD/INSTANTPAY = 10) unless paused
        router.updatePolicy("UPI_CASHOUT", "PRIMARY_FALLBACK", true);
        assertEquals("EMERALD", router.selectChannel(userId, "UPI_CASHOUT").providerCode());
        assertEquals("EMERALD", router.selectChannel(userId, "UPI_CASHOUT").providerCode());

        // Pause primary channel globally -> automatically fails over to VENDOR_TWO!
        router.updateChannel(ch1, false, 10, null);
        assertEquals("VENDOR_TWO", router.selectChannel(userId, "UPI_CASHOUT").providerCode());

        // Restore ch1 for clean state
        router.updateChannel(ch1, true, 10, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyRetailerReverificationStoresEncryptedAadhaarAndAutoPassesDecryptedToEmeraldOnboarding() {
        String mobile = randomMobile();
        UUID userId = UUID.randomUUID();
        String code = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO users (id, user_type, full_name, mobile, email, password_hash, pin_hash, status, code)
                VALUES (?, 'RETAILER', 'Nitin Jhamnani', ?, ?, ?, ?, 'ACTIVE', ?)
                """,
                userId, mobile, "nitin." + mobile + "@gfinpay.test",
                passwordEncoder.encode("Secret@123"), passwordEncoder.encode("482915"), code);
        walletService.createForUser(userId);
        jdbc.update("UPDATE wallets SET status = 'ACTIVE' WHERE user_id = ?", userId);

        // Old retailer already has Business + Agreement + Bank verified, but aadhaar_enc IS NULL and needs re-verification
        jdbc.update("""
                INSERT INTO agent_kyc_profiles
                    (user_id, kyc_status, identity_status, personal_status, personal_identity_verified,
                     business_status, business_verified, agreement_status, aadhaar_enc, aadhaar_last4, pan_last4,
                     shop_name, gstin, work_latitude, work_longitude,
                     personal_details, shop_address)
                VALUES (?, 'PENDING', 'NOT_VERIFIED', 'NOT_VERIFIED', FALSE,
                        'BUSINESS_VERIFIED', TRUE, 'SIGNED', NULL, NULL, NULL,
                        'NITIN FIN DESK', '29ABCDE8300M1Z5', 12.891647, 77.667052,
                        '{"fullName":"Nitin Jhamnani","dob":"1994-08-20","gender":"M"}'::jsonb,
                        '{"line1":"HSR Layout","area":"Sector 2","city":"Bengaluru","district":"Bengaluru Urban","state":"Karnataka","pincode":"560102"}'::jsonb)
                """, userId);

        String safeSuffix = mobile.substring(6);
        if ("0000".equals(safeSuffix) || "9999".equals(safeSuffix)) {
            safeSuffix = "6001";
        }
        AuthPrincipal retailer = new AuthPrincipal(userId, "Nitin Jhamnani", "RETAILER", null);
        retailerBankService.verifyAndCreate(retailer, "05456166" + safeSuffix, "KKBK0006406", "SAVINGS");

        // Before re-verification: Admin dossier reports hasFullAadhaar = false and missing 12-digit Aadhaar
        Map<String, Object> beforeAdminView = providerOnboarding.getRetailerProviders(userId);
        Map<String, Object> beforeDossier = (Map<String, Object>) beforeAdminView.get("dossier");
        assertEquals(false, beforeDossier.get("hasFullAadhaar"));

        // Retailer runs /reverify
        AuthPrincipal reverifyPrincipal = new AuthPrincipal(userId, "Nitin Jhamnani", "RETAILER", null, "REVERIFY");
        setAuth(reverifyPrincipal);
        reverifyService.status(reverifyPrincipal);
        reverifyService.sendOtp(reverifyPrincipal, "127.0.0.1", "JUnit");
        String otp = smsSender.lastFor(mobile).params().get("otp");
        reverifyService.verifyOtp(reverifyPrincipal, otp, "127.0.0.1", "JUnit");
        reverifyService.captureLocation(reverifyPrincipal, 12.891647, 77.667052, 10.0, "127.0.0.1", "JUnit");

        Map<String, Object> dl = reverifyService.startDigilocker(reverifyPrincipal, "127.0.0.1", "JUnit");
        Map<String, Object> dlDone = reverifyService.completeDigilocker(
                reverifyPrincipal, String.valueOf(dl.get("state")), "127.0.0.1", "JUnit");
        String maskedFromDl = String.valueOf(dlDone.get("maskedAadhaar"));
        String dlLast4 = maskedFromDl.substring(maskedFromDl.length() - 4);
        assertEquals(false, dlDone.get("hasFullAadhaar"));

        // Construct Verhoeff-valid 12-digit Aadhaar matching DigiLocker's last 4 digits, plus a mismatched one
        String matchingAadhaar12 = validAadhaarEndingWith(dlLast4);
        String wrongLast4 = "9999".equals(dlLast4) ? "1111" : "9999";
        String mismatchedAadhaar12 = validAadhaarEndingWith(wrongLast4);

        // 1) Missing 12-digit Aadhaar on /reverify/pan is rejected
        ApiException missingEx = assertThrows(ApiException.class, () ->
                reverifyService.verifyPan(reverifyPrincipal, "ABCDE8300M", null, null, true, "127.0.0.1", "JUnit"));
        assertEquals("AADHAAR_REQUIRED", missingEx.getCode());

        // 2) Mismatched last-4 Aadhaar is rejected
        ApiException mismatchEx = assertThrows(ApiException.class, () ->
                reverifyService.verifyPan(reverifyPrincipal, "ABCDE8300M", mismatchedAadhaar12, null, true, "127.0.0.1", "JUnit"));
        assertEquals("AADHAAR_LAST4_MISMATCH", mismatchEx.getCode());

        // 3) Valid 12-digit Aadhaar matching DigiLocker's last 4 succeeds & marks hasFullAadhaar = true
        Map<String, Object> panRes = reverifyService.verifyPan(
                reverifyPrincipal, "ABCDE8300M", matchingAadhaar12, null, true, "127.0.0.1", "JUnit");
        assertEquals(true, panRes.get("hasFullAadhaar"));

        // Sanitized /reverify/status never leaks ciphertext to browser, only hasFullAadhaar = true
        Map<String, Object> statusDuring = reverifyService.status(reverifyPrincipal);
        Map<String, Object> identityDuring = (Map<String, Object>) statusDuring.get("identity");
        assertEquals(true, identityDuring.get("hasFullAadhaar"));
        assertFalse(identityDuring.containsKey("aadhaarEncrypted"));

        MockMultipartFile selfie = new MockMultipartFile(
                "file", "selfie.jpg", "image/jpeg", "SELFIE_BYTES".getBytes(StandardCharsets.UTF_8));
        reverifyService.verifySelfie(reverifyPrincipal, selfie, "127.0.0.1", "JUnit");
        reverifyService.completeReverification(reverifyPrincipal, "482915", null, "127.0.0.1", "JUnit");

        // Admin approves personal KYC review (as normal after re-verification)
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET personal_identity_verified = TRUE,
                       personal_status = 'PERSONAL_VERIFIED'
                 WHERE user_id = ?
                """, userId);

        // Verify aadhaar_enc in DB is AES-256-GCM encrypted and decrypts back to exact 12-digit Aadhaar
        String storedCiphertext = jdbc.queryForObject(
                "SELECT aadhaar_enc FROM agent_kyc_profiles WHERE user_id = ?", String.class, userId);
        assertNotNull(storedCiphertext);
        assertFalse(storedCiphertext.contains(matchingAadhaar12), "DB column must store ciphertext, never raw Aadhaar");
        assertEquals(matchingAadhaar12, fieldCipher.decrypt(storedCiphertext));

        // Verify Admin Retailer Banking view now shows hasFullAadhaar = true and ZERO missing fields!
        setAuth(superAdmin);
        Map<String, Object> afterAdminView = providerOnboarding.getRetailerProviders(userId);
        Map<String, Object> afterDossier = (Map<String, Object>) afterAdminView.get("dossier");
        assertEquals(true, afterDossier.get("hasFullAadhaar"));
        assertEquals(true, afterDossier.get("readyForEmerald"));
        assertEquals(List.of(), afterDossier.get("missingFields"));

        // Admin clicks Onboard EMERALD WITHOUT entering any aadhaarOverride -> decrypted Aadhaar is passed automatically!
        capturedRequests.clear();
        providerOnboarding.onboardToEmerald(superAdmin, userId, null, null, null);

        Map<String, Object> createPayload = capturedRequests.get("/core/v1/outlets/create");
        assertNotNull(createPayload, "Expected /core/v1/outlets/create call");
        assertEquals(matchingAadhaar12, createPayload.get("aadhaar"),
                "Emerald outlet create must automatically receive decrypted 12-digit Aadhaar without Admin backfill");

        // Async audit log still masks the 12-digit Aadhaar at rest in emerald_api_logs
        assertTrue(emeraldAuditLogs.awaitIdle(Duration.ofSeconds(5)));
        List<Map<String, Object>> logs = emeraldAuditLogs.listForUser(userId, 20);
        Map<String, Object> createLog = assertHasLog(
                logs, "OUTBOUND", "STEP1_ONBOARD:OUTLET_CREATE", "/core/v1/outlets/create", 200);
        String loggedBody = String.valueOf(createLog.get("requestBody"));
        assertFalse(loggedBody.contains(matchingAadhaar12));
        assertTrue(loggedBody.contains("XXXXXXXX" + dlLast4));
    }

    private static String validAadhaarEndingWith(String last4) {
        for (int prefix = 23450000; prefix < 23460000; prefix++) {
            String candidate = prefix + last4;
            if (AadhaarValidator.verhoeffValid(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find Verhoeff Aadhaar ending with " + last4);
    }
}
