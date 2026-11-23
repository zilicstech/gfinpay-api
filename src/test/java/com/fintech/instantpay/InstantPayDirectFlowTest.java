package com.fintech.instantpay;

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
import com.fintech.ledger.WalletService;
import com.fintech.payout.PayoutService;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ProviderOnboardingService;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.verification.AadhaarValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
        "app.esign.mode=mock",
        "app.instantpay.enabled=true",
        "app.instantpay.auth-code=1",
        "app.instantpay.client-id=IPAY_TEST_CLIENT_ID",
        "app.instantpay.client-secret=fd77361133ce1a1169de78c57c44caa747ec7dfdeb7dc047d7950aaadfa96984",
        "app.instantpay.encryption-key=253ddc7991d45f00253ddc7991d45f00",
        "app.instantpay.endpoint-ip=103.80.162.232",
        "app.instantpay.default-spkey=WAP",
        "app.instantpay.default-gw=JA",
        "app.instantpay.payer-bank-profile-id=0",
        "app.instantpay.payer-account-number=9650043494",
        "app.instantpay.payout-purpose=REIMBURSEMENT",
        "app.instantpay.webhook-secret=IPAY_WEBHOOK_SEC_777",
        "app.payout.min-cashout-txns=1",
        "app.payout.min-amount=10"
})
@ActiveProfiles("test")
class InstantPayDirectFlowTest {

    private static final String ENC_KEY = "253ddc7991d45f00253ddc7991d45f00";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static HttpServer mockIpayServer;
    static final Map<String, Map<String, Object>> capturedRequests = new ConcurrentHashMap<>();
    static final Map<String, Map<String, String>> capturedHeaders = new ConcurrentHashMap<>();
    static final AtomicReference<String> bioStage = new AtomicReference<>("NOTYETREGISTERED");
    static final AtomicReference<String> uatmQrStatusMode = new AtomicReference<>("INITIATED");
    static final AtomicReference<Boolean> simulateAlreadyExistsOnMinKyc = new AtomicReference<>(false);
    static final AtomicInteger txnStatusCallCount = new AtomicInteger(0);

    private static final String SAMPLE_PID_XML = """
            <PidData>
              <Resp errCode="0" errInfo="Success." fCount="1" fType="2" nmPoints="44" qScore="86"/>
              <DeviceInfo dpId="MANTRA.MSIPL" rdsId="MANTRA.AND.001" rdsVer="1.0.8" mi="MFS110"
                          mc="CERT_B64" dc="dc-uuid-5678">
                <additional_info>
                  <Param name="srno" value="77889900"/>
                  <Param name="sysid" value="SYS002"/>
                  <Param name="ts" value="2026-11-23T12:00:00+05:30"/>
                </additional_info>
              </DeviceInfo>
              <Skey ci="20281029">SKEY_SECRET_BASE64</Skey>
              <Hmac>HMAC_SECRET_BASE64</Hmac>
              <Data type="X">PID_SECRET_BASE64</Data>
            </PidData>
            """;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        startMockInstantPayIfNeeded();
        String mockBaseUrl = "http://127.0.0.1:" + mockIpayServer.getAddress().getPort();
        registry.add("app.instantpay.base-url", () -> mockBaseUrl);

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
        if (mockIpayServer != null) {
            mockIpayServer.stop(0);
        }
    }

    private static synchronized void startMockInstantPayIfNeeded() throws IOException {
        if (mockIpayServer != null) return;
        ObjectMapper om = new ObjectMapper();
        mockIpayServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockIpayServer.createContext("/", (HttpExchange ex) -> {
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
                case "/user/outlet/signup/minKyc" -> {
                    if (Boolean.TRUE.equals(simulateAlreadyExistsOnMinKyc.get())) {
                        resp = """
                                {
                                  "statuscode": "ERR",
                                  "actcode": null,
                                  "status": "Merchant already registered",
                                  "data": null,
                                  "environment": "LIVE"
                                }
                                """;
                    } else {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": null,
                                  "status": "Transaction Successful",
                                  "data": {
                                    "outletId": 72762,
                                    "name": "HEMANT MISHRA",
                                    "dateOfBirth": "1991-09-12",
                                    "gender": "M",
                                    "pincode": "560099",
                                    "state": "KARNATAKA",
                                    "city": "BENGALURU"
                                  },
                                  "ipay_uuid": "h000-minkyc-uuid",
                                  "orderid": "ORD_MINKYC_1",
                                  "environment": "LIVE"
                                }
                                """;
                    }
                }
                case "/user/outlet/list" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> filters = reqJson.get("filters") instanceof Map<?, ?> m
                            ? (Map<String, Object>) m
                            : Map.of();
                    String reqMob = String.valueOf(filters.getOrDefault("mobileNumber", "9545612835"));
                    resp = """
                            {
                              "statuscode": "TXN",
                              "status": "Transaction Successful",
                              "data": {
                                "records": [
                                  {
                                    "outletId": 88441,
                                    "name": "DIRECTOR SIGNATORY",
                                    "mobile": "%s",
                                    "pan": "BJFPM6264D",
                                    "KYCStatus": false,
                                    "isActive": true
                                  }
                                ]
                              }
                            }
                            """.formatted(reqMob);
                }
                case "/user/outlet/signup/addBankAccount" -> resp = """
                        {
                          "statuscode": "TXN",
                          "actcode": null,
                          "status": "Bank Account Added Successfully",
                          "data": null,
                          "ipay_uuid": "h000-addbank-uuid",
                          "environment": "LIVE"
                        }
                        """;
                case "/user/outlet/signup/biometricKycStatus" -> {
                    String stage = bioStage.get();
                    if ("NOTYETREGISTERED".equals(stage)) {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": "NOTYETREGISTERED",
                                  "status": "Outlet not yet registered with Bank, please complete registration first",
                                  "data": {
                                    "action": "NO-ACTION-REQUIRED",
                                    "status": "PENDING",
                                    "pidOptionWadh": "WADH_REG",
                                    "referenceKey": "REF_REG_KEY_001",
                                    "referenceKeyType": "OutletRegistration",
                                    "bankName": "Jio Payments Bank"
                                  }
                                }
                                """;
                    } else if ("ACTION-REQUIRED".equals(stage)) {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": null,
                                  "status": "Biometric authentication required",
                                  "data": {
                                    "outletAadhaarNumber": "XXXX-XXXX-7183",
                                    "action": "ACTION-REQUIRED",
                                    "status": "PENDING",
                                    "pidOptionWadh": "WADH_BIO_123",
                                    "referenceKey": "REF_BIO_KEY_002",
                                    "referenceKeyType": "OutletBiometicKyc"
                                  }
                                }
                                """;
                    } else {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": null,
                                  "status": "Approved",
                                  "data": {
                                    "action": "NO-ACTION-REQUIRED",
                                    "status": "APPROVED"
                                  }
                                }
                                """;
                    }
                }
                case "/user/outlet/register" -> resp = """
                        {
                          "statuscode": "OTP",
                          "actcode": null,
                          "status": "OTP Successfully sent",
                          "data": {
                            "referenceKeyType": "OutletRegistrationVerify",
                            "validity": "2026-11-23 18:00:00",
                            "referenceKey": "REF_OTP_VERIFY_003"
                          }
                        }
                        """;
                case "/user/outlet/registerationVerify" -> {
                    bioStage.set("ACTION-REQUIRED");
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": null,
                              "status": "Success",
                              "data": null
                            }
                            """;
                }
                case "/user/outlet/signup/biometricKyc" -> {
                    bioStage.set("APPROVED");
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": null,
                              "status": "Success",
                              "data": null,
                              "environment": "LIVE"
                            }
                            """;
                }
                case "/fi/uatm/generateQr" -> {
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "CW1"));
                    String amt = String.valueOf(reqJson.getOrDefault("amount", "100"));
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": null,
                              "status": "QR Generated Successfully.",
                              "data": {
                                "externalRef": "%s",
                                "qrStatus": "INITIATED",
                                "qrStatusMsg": "QR Generated Successfully",
                                "qrString": "upi://pay?pa=jpbcw@jiomerchant&am=%s.0&cu=INR",
                                "qrMobile": "upi://pay?pa=jpbcw@jiomerchant&am=%s.0&cu=INR",
                                "displayExpirySec": "30",
                                "ipayId": "IPAY_UATM_%s",
                                "transactionMode": "CR",
                                "payableValue": "%s.39",
                                "transactionValue": "%s.00"
                              },
                              "orderid": "IPAY_UATM_%s",
                              "environment": "LIVE"
                            }
                            """.formatted(extRef, amt, amt, extRef, amt, amt, extRef);
                }
                case "/fi/uatm/qrStatus" -> {
                    String ipayId = String.valueOf(reqJson.getOrDefault("ipayId", "IPAY_1"));
                    String qMode = uatmQrStatusMode.get();
                    resp = """
                            {
                              "statuscode": "TXN",
                              "status": "Success",
                              "data": {
                                "qrStatus": "%s",
                                "qrStatusMsg": "Status %s",
                                "ipayId": "%s",
                                "amount": "100.00",
                                "operatorId": "OPR_%s",
                                "payer": {
                                  "vpa": "walkin@okicici",
                                  "name": "Walkin Buyer"
                                }
                              }
                            }
                            """.formatted(qMode, qMode, ipayId, ipayId);
                }
                case "/payments/payout" -> {
                    // Exact InstantPay Sandbox amount rules:
                    // 11 -> TXN (SUCCESS), 12 -> TUP (PENDING), any other -> ERR (FAILED)
                    String amt = String.valueOf(reqJson.getOrDefault("transferAmount", "0"));
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "PO1"));
                    if ("11".equals(amt)) {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": null,
                                  "status": "Transaction Successful",
                                  "data": {
                                    "externalRef": "%s",
                                    "poolReferenceId": "IPAY_PO_11_%s",
                                    "txnValue": "11",
                                    "txnReferenceId": "UTR_11_%s"
                                  },
                                  "orderid": "IPAY_PO_11_%s",
                                  "environment": "LIVE"
                                }
                                """.formatted(extRef, extRef, extRef, extRef);
                    } else if ("12".equals(amt)) {
                        resp = """
                                {
                                  "statuscode": "TUP",
                                  "actcode": null,
                                  "status": "Transaction Under Process",
                                  "data": {
                                    "externalRef": "%s",
                                    "poolReferenceId": "IPAY_PO_12_%s"
                                  },
                                  "orderid": "IPAY_PO_12_%s",
                                  "environment": "LIVE"
                                }
                                """.formatted(extRef, extRef, extRef);
                    } else {
                        resp = """
                                {
                                  "statuscode": "ERR",
                                  "actcode": null,
                                  "status": "Beneficiary bank declined transfer",
                                  "data": null,
                                  "environment": "LIVE"
                                }
                                """;
                    }
                }
                case "/reports/txnStatus" -> {
                    txnStatusCallCount.incrementAndGet();
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "PO1"));
                    resp = """
                            {
                              "statuscode": "TXN",
                              "status": "-",
                              "data": {
                                "transactionStatusCode": "TXN",
                                "transactionStatus": "Transaction Successful",
                                "transactionReferenceId": "UTR_RECON_%s",
                                "order": { "referenceId": "ORD_%s", "externalRef": "%s" }
                              }
                            }
                            """.formatted(extRef, extRef, extRef);
                }
                default -> {
                    code = 404;
                    resp = "{\"statuscode\":\"404\",\"status\":\"Not found\"}";
                }
            }

            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        mockIpayServer.start();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ProviderOnboardingService providerOnboarding;
    @Autowired ServiceProviderRouter router;
    @Autowired BiometricActivationService biometricService;
    @Autowired CashoutService cashoutService;
    @Autowired PayoutService payoutService;
    @Autowired InstantPayPayoutAdapter instantPayPayoutAdapter;
    @Autowired InstantPayCallbackService callbackService;
    @Autowired InstantPayAuditLogService auditLogs;
    @Autowired RetailerBankService retailerBankService;
    @Autowired WalletService walletService;
    @Autowired FieldCipher fieldCipher;

    private AuthPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        capturedRequests.clear();
        capturedHeaders.clear();
        bioStage.set("NOTYETREGISTERED");
        uatmQrStatusMode.set("INITIATED");
        simulateAlreadyExistsOnMinKyc.set(false);
        txnStatusCallCount.set(0);

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
                        List.of(
                                new SimpleGrantedAuthority("wallet.view"),
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
        long n = 9100000000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 899999999L);
        return String.valueOf(n);
    }

    private AuthPrincipal createIndividualRetailer(String aadhaar12) {
        String mobile = randomMobile();
        UUID userId = UUID.randomUUID();
        String code = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO users (id, user_type, full_name, mobile, email, password_hash, status, code)
                VALUES (?, 'RETAILER', 'Hemant Mishra', ?, ?, ?, 'ACTIVE', ?)
                """,
                userId, mobile, "hemant." + mobile + "@gfinpay.test", passwordEncoder.encode("Secret@123"), code);

        walletService.createForUser(userId);
        jdbc.update("UPDATE wallets SET status = 'ACTIVE' WHERE user_id = ?", userId);

        jdbc.update("""
                INSERT INTO agent_kyc_profiles
                    (user_id, kyc_status, identity_status, personal_status, personal_identity_verified,
                     business_status, business_verified, agreement_status, business_type,
                     aadhaar_last4, aadhaar_enc, pan_last4, pan_enc,
                     shop_name, work_latitude, work_longitude,
                     personal_details, shop_address)
                VALUES (?, 'COMPLETED', 'VERIFIED', 'PERSONAL_VERIFIED', TRUE,
                        'BUSINESS_VERIFIED', TRUE, 'SIGNED', 'INDIVIDUAL',
                        ?, ?, '6264', ?,
                        'Mishra Cash Point', 21.251973, 81.673191,
                        '{"fullName":"Hemant Mishra","panName":"HEMANT MISHRA","dob":"1991-09-12","gender":"MALE","permanentAddress":{"fullAddress":"B112 CHOODASANDRA BANGALORE","city":"Bengaluru","pincode":"560099"}}'::jsonb,
                        '{"line1":"B112 Choodasandra","city":"Bengaluru","state":"Karnataka","pincode":"560099"}'::jsonb)
                """,
                userId, aadhaar12.substring(8), fieldCipher.encrypt(aadhaar12), fieldCipher.encrypt("BJFPM6264D"));

        AuthPrincipal retailer = new AuthPrincipal(userId, "Hemant Mishra", "RETAILER", null);
        String suffix = mobile.substring(6);
        if ("0000".equals(suffix) || "9999".equals(suffix)) suffix = "2468";
        retailerBankService.verifyAndCreate(retailer, "50100999" + suffix, "BARB0DAMOHA", "SAVINGS");
        return retailer;
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullEndToEndInstantPayOnboardingOtpBiometricUatmPayoutAndWebhookFlow() {
        String aadhaar12 = "209313587183";
        AuthPrincipal retailer = createIndividualRetailer(aadhaar12);
        UUID userId = retailer.userId();

        // 1. Admin clicks "Onboard to InstantPay"
        setAuth(superAdmin);
        Map<String, Object> onboardView = providerOnboarding.onboardToInstantPay(superAdmin, userId, null, null);
        assertNotNull(onboardView);

        // Verify signup/minKyc encrypted Aadhaar, Individual PAN, and 4-decimal coordinates
        Map<String, Object> minKycReq = capturedRequests.get("/user/outlet/signup/minKyc");
        assertNotNull(minKycReq);
        assertEquals("HEMANT MISHRA", minKycReq.get("name"));
        assertEquals("M", minKycReq.get("gender"));
        assertEquals("BJFPM6264D", minKycReq.get("pan"));
        assertEquals("21.2520", minKycReq.get("latitude"));
        assertEquals("81.6732", minKycReq.get("longitude"));
        assertEquals(aadhaar12, InstantPayCrypto.decrypt(String.valueOf(minKycReq.get("aadhaar")), ENC_KEY));

        // Verify biometricKycStatus sent {"spkey":"WAP","gw":"JA"}
        Map<String, Object> bioStatusReq = capturedRequests.get("/user/outlet/signup/biometricKycStatus");
        assertEquals("WAP", bioStatusReq.get("spkey"));
        assertEquals("JA", bioStatusReq.get("gw"));
        assertEquals("72762", capturedHeaders.get("/user/outlet/signup/biometricKycStatus").get("x-ipay-outlet-id"));

        // Verify addBankAccount automatically synced the retailer's verified bank account
        Map<String, Object> addBankReq = capturedRequests.get("/user/outlet/signup/addBankAccount");
        assertNotNull(addBankReq);
        assertEquals("72762", addBankReq.get("outletId"));
        assertEquals("BARB0DAMOHA", addBankReq.get("bankIfsc"));

        UUID instantPayChannelId = jdbc.queryForObject(
                "SELECT id FROM service_provider_channels WHERE provider_code = 'INSTANTPAY' AND pipe_code = 'DIRECT'",
                UUID.class);

        // Enable InstantPay at Priority 1 for this retailer
        providerOnboarding.setProviderPriorities(
                superAdmin, userId, List.of(new ProviderOnboardingService.ProviderPriority("INSTANTPAY", 1)));
        providerOnboarding.updateRetailerChannel(superAdmin, userId, instantPayChannelId, true, false, 1);

        // 2. White-label OTP Registration + 25-field Biometric Activation
        setAuth(retailer);
        Map<String, Object> initOtp = biometricService.initiateBiometric(retailer, "ch_instantpay_direct", null);
        assertTrue(Boolean.TRUE.equals(initOtp.get("otpRequired")));
        UUID bioSessionId = (UUID) initOtp.get("sessionId");
        assertNotNull(bioSessionId);
        assertEquals("REF_REG_KEY_001", capturedRequests.get("/user/outlet/register").get("referenceKey"));

        Map<String, Object> afterOtp = biometricService.verifyRegistrationOtp(
                retailer, "ch_instantpay_direct", bioSessionId, "654321");
        assertFalse(Boolean.TRUE.equals(afterOtp.get("otpRequired")));
        assertEquals("WADH_BIO_123", afterOtp.get("wadh"));
        assertEquals("REF_OTP_VERIFY_003", capturedRequests.get("/user/outlet/registerationVerify").get("referenceKey"));

        Map<String, Object> postBio = biometricService.submitBiometric(
                retailer, "ch_instantpay_direct", bioSessionId, SAMPLE_PID_XML, "21.2520", "81.6732");
        assertTrue((Boolean) postBio.get("biometricVerified"));
        assertTrue((Boolean) postBio.get("upiCashoutActive"));
        assertTrue((Boolean) postBio.get("payoutBankReady"));

        // Verify 25 biometric fields (omits sessionId, encrypts Aadhaar via AES-256-CBC)
        Map<String, Object> bioSubmitReq = capturedRequests.get("/user/outlet/signup/biometricKyc");
        Map<String, Object> bioData = (Map<String, Object>) bioSubmitReq.get("biometricData");
        assertEquals(25, bioData.size());
        assertFalse(bioData.containsKey("sessionId"));
        assertEquals(aadhaar12, InstantPayCrypto.decrypt(String.valueOf(bioData.get("encryptedAadhaar")), ENC_KEY));

        // 3. Direct UPI-to-Cashout (UATM): ₹100 minimum + qrStatus polling + CR webhook with echoed ipay_id
        Map<String, Object> limits = cashoutService.limits(retailer);
        assertEquals(100, limits.get("min"));
        assertEquals(5000, limits.get("max"));

        Map<String, Object> qr1 = cashoutService.generateQr(
                retailer, 100, "9876543210", "Walkin One", "21.2520", "81.6732", "ipay-qr-1");
        UUID qrSession1Id = (UUID) qr1.get("id");
        String qrExtRef1 = String.valueOf(qr1.get("refid"));
        assertEquals(90, qr1.get("displayExpirySec"));

        // Settle QR #1 via real-time /fi/uatm/qrStatus polling
        uatmQrStatusMode.set("SUCCESS");
        Map<String, Object> refreshedQr1 = cashoutService.refresh(retailer, qrSession1Id);
        assertEquals("PAID", refreshedQr1.get("status"));
        assertEquals("OPR_IPAY_UATM_" + qrExtRef1, refreshedQr1.get("utr"));

        // Generate QR #2 and settle via InstantPay GET/POST Webhook (verifying bad secret 401 + echoed ipay_id + dedupe)
        uatmQrStatusMode.set("INITIATED");
        Map<String, Object> qr2 = cashoutService.generateQr(
                retailer, 200, "9876543211", "Walkin Two", "21.2520", "81.6732", "ipay-qr-2");
        String qrExtRef2 = String.valueOf(qr2.get("refid"));
        String ipayId2 = "IPAY_UATM_" + qrExtRef2;

        InstantPayCallbackService.CallbackAck unauth = callbackService.handleInbound(
                "GET", "WRONG_TOKEN",
                Map.of("ipay_id", ipayId2, "agent_id", qrExtRef2, "status", "SUCCESS", "res_code", "TXN", "txn_mode", "CR"),
                "", "/webhooks/instantpay/callback/WRONG_TOKEN", "/webhooks/instantpay/callback/WRONG_TOKEN", Map.of());
        assertEquals(401, unauth.httpStatus());

        Map<String, String> crParams = Map.of(
                "ipay_id", ipayId2,
                "agent_id", qrExtRef2,
                "opr_id", "RRN_UATM_" + qrExtRef2,
                "status", "SUCCESS",
                "res_code", "TXN",
                "res_msg", "Cash Withdrawal is Successful",
                "txn_mode", "CR");
        InstantPayCallbackService.CallbackAck crAck = callbackService.handleInbound(
                "GET", "IPAY_WEBHOOK_SEC_777", crParams, "",
                "/webhooks/instantpay/callback/IPAY_WEBHOOK_SEC_777",
                "/webhooks/instantpay/callback/IPAY_WEBHOOK_SEC_777", Map.of());
        assertEquals(200, crAck.httpStatus());
        assertEquals(ipayId2, crAck.body().get("ipay_id"));
        assertEquals(Boolean.TRUE, crAck.body().get("success"));
        assertEquals("callback called successfully", crAck.body().get("description"));

        // Wallet after ₹100 + ₹200 cashouts = ₹300.00
        assertEquals(0, new BigDecimal("300.00").compareTo(walletService.getByUser(userId).available()));

        // 4. Strict Same-Bank Payout Guard: adding a newer verified bank blocks payout until addBankAccount syncs it!
        retailerBankService.verifyAndCreate(retailer, "501008884321", "BARB0DAMOHA", "CURRENT");
        assertFalse((Boolean) payoutService.checkEligibility(retailer).get("eligible"));
        ApiException unsyncedEx = assertThrows(
                ApiException.class,
                () -> payoutService.initiatePayout(retailer, new BigDecimal("11"), "IMPS", null, "21.2520", "81.6732", "po-unsynced"));
        assertEquals("PAYOUT_NOT_ELIGIBLE", unsyncedEx.getCode());

        // Sync the new bank account to InstantPay -> payouts unlock and route strictly to the newly synced bank!
        setAuth(superAdmin);
        Map<String, Object> resynced = providerOnboarding.syncInstantPayBank(superAdmin, userId);
        assertEquals("SYNCED", resynced.get("bankSyncStatus"));

        // 5a. Payout Amount = 11 -> Immediate TXN (SUCCESS), payer = 0 / 9650043494, 20-char alphabetic remarks
        setAuth(retailer);
        Map<String, Object> po11 = payoutService.initiatePayout(
                retailer, new BigDecimal("11"), "IMPS", "Merchant bank settlement #99", "21.251973", "81.673191", "po-11");
        String po11Ref = String.valueOf(po11.get("reference"));
        assertEquals("SUCCESS", po11.get("status"));
        assertEquals("UTR_11_" + po11Ref, po11.get("utr"));

        Map<String, Object> sentPayoutReq = capturedRequests.get("/payments/payout");
        Map<String, Object> sentPayer = (Map<String, Object>) sentPayoutReq.get("payer");
        Map<String, Object> sentPayee = (Map<String, Object>) sentPayoutReq.get("payee");
        assertEquals("0", sentPayer.get("bankProfileId"));
        assertEquals("9650043494", sentPayer.get("accountNumber"));
        assertEquals("501008884321", sentPayee.get("accountNumber"));
        assertTrue(String.valueOf(sentPayoutReq.get("remarks")).length() <= 20);
        assertEquals("21.2520", sentPayoutReq.get("latitude"));

        // Wallet: 300 - 11 = 289.00, hold = 0.00
        assertEquals(0, new BigDecimal("289.00").compareTo(walletService.getByUser(userId).available()));
        assertEquals(0, BigDecimal.ZERO.compareTo(walletService.getByUser(userId).hold()));

        // Post-settlement bank return webhook (status = REFUND on SUCCESS payout) -> transitions to REVERSED & credits back ₹11!
        callbackService.handleInbound(
                "POST", "IPAY_WEBHOOK_SEC_777",
                Map.of("ipay_id", "IPAY_PO_11_" + po11Ref, "agent_id", po11Ref, "opr_id", "UTR_RET_" + po11Ref,
                        "status", "REFUND", "res_code", "RPI", "res_msg", "Returned by beneficiary bank", "txn_mode", "DR"),
                "", "/webhooks/instantpay/callback/IPAY_WEBHOOK_SEC_777", "/webhooks/instantpay/callback/IPAY_WEBHOOK_SEC_777", Map.of());
        assertEquals(0, new BigDecimal("300.00").compareTo(walletService.getByUser(userId).available()));

        // 5b. Payout Amount = 13 -> Synchronous ERR (FAILED), hold immediately restored to available_balance
        assertThrows(
                ApiException.class,
                () -> payoutService.initiatePayout(retailer, new BigDecimal("13"), "IMPS", "Fund Transfer", "21.2520", "81.6732", "po-13"));
        assertEquals(0, new BigDecimal("300.00").compareTo(walletService.getByUser(userId).available()));
        assertEquals(0, BigDecimal.ZERO.compareTo(walletService.getByUser(userId).hold()));

        // 5c. Payout Amount = 12 -> TUP (PENDING):
        //     - TUP callback must NEVER auto-refund
        //     - <30m /reports/txnStatus must throttle without calling upstream
        //     - Backdating >30m allows /reports/txnStatus, and <4h second poll is throttled!
        Map<String, Object> po12 = payoutService.initiatePayout(
                retailer, new BigDecimal("12"), "IMPS", "Fund Transfer", "21.2520", "81.6732", "po-12");
        assertEquals("PENDING", po12.get("status"));
        String po12Ref = String.valueOf(po12.get("reference"));
        UUID po12TxnId = (UUID) po12.get("transactionId");
        assertEquals(0, new BigDecimal("288.00").compareTo(walletService.getByUser(userId).available()));
        assertEquals(0, new BigDecimal("12.00").compareTo(walletService.getByUser(userId).hold()));

        // TUP timeout callback -> MUST NOT refund or fail!
        callbackService.handleInbound(
                "POST", "IPAY_WEBHOOK_SEC_777",
                Map.of("ipay_id", "IPAY_PO_12_" + po12Ref, "agent_id", po12Ref, "opr_id", "",
                        "status", "FAILED", "res_code", "TUP", "res_msg", "Response Timedout please contact help desk #E1", "txn_mode", "DR"),
                "", "/webhooks/instantpay/callback/IPAY_WEBHOOK_SEC_777", "/webhooks/instantpay/callback/IPAY_WEBHOOK_SEC_777", Map.of());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payout_transfers WHERE external_ref = ?", String.class, po12Ref));
        assertEquals(0, new BigDecimal("12.00").compareTo(walletService.getByUser(userId).hold()));

        // Immediate (<30m) status check -> throttled locally without hitting /reports/txnStatus
        payoutService.reconcileByTransactionId(po12TxnId);
        assertEquals(0, txnStatusCallCount.get());

        // Backdate created_at by 35 minutes -> /reports/txnStatus executes and settles to SUCCESS!
        jdbc.update("UPDATE payout_transfers SET created_at = ? WHERE external_ref = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(35))), po12Ref);
        Map<String, Object> reconciledPo12 = payoutService.reconcileByTransactionId(po12TxnId);
        assertEquals("SUCCESS", reconciledPo12.get("status"));
        assertEquals(1, txnStatusCallCount.get());
        assertEquals(0, BigDecimal.ZERO.compareTo(walletService.getByUser(userId).hold()));

        // 6. Verify audit logs & secret/PII redaction in instantpay_api_logs and provider_api_logs
        auditLogs.awaitPending(Duration.ofSeconds(5));
        List<Map<String, Object>> userLogs = auditLogs.listForUser(userId, 50);
        assertFalse(userLogs.isEmpty());
        String allLogsJson = userLogs.toString();
        assertFalse(allLogsJson.contains(aadhaar12), "Plain Aadhaar must never appear in logs");
        assertFalse(allLogsJson.contains("fd77361133ce1a1169de78c57c44caa7"), "Client secret must be redacted");
        assertFalse(allLogsJson.contains("PID_SECRET_BASE64"), "PID XML block must be redacted");
        assertFalse(allLogsJson.contains("IPAY_WEBHOOK_SEC_777"), "Webhook URL secret must be redacted");

        Integer unionCount = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM provider_api_logs WHERE rail = 'INSTANTPAY'", Integer.class);
        assertTrue(unionCount != null && unionCount >= 8);
    }

    @Test
    void companyRetailerUsesAuthorizedSignatoryIndividualPanAndRecoversExistingOutlet() {
        String sigAadhaar = validAadhaar("65432198765");
        String mobile = randomMobile();
        UUID userId = UUID.randomUUID();
        String code = "RC" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO users (id, user_type, full_name, mobile, email, password_hash, status, code)
                VALUES (?, 'RETAILER', 'Acme Finserv Pvt Ltd', ?, ?, ?, 'ACTIVE', ?)
                """, userId, mobile, "ops." + mobile + "@acmefin.test", passwordEncoder.encode("Secret@123"), code);
        walletService.createForUser(userId);
        jdbc.update("UPDATE wallets SET status = 'ACTIVE' WHERE user_id = ?", userId);

        // Company PAN has 4th char 'C' (AABCA1234E) -> not allowed by InstantPay minKyc!
        jdbc.update("""
                INSERT INTO agent_kyc_profiles
                    (user_id, kyc_status, identity_status, personal_status, personal_identity_verified,
                     business_status, business_verified, agreement_status, business_type,
                     pan_last4, pan_enc, shop_name, work_latitude, work_longitude,
                     personal_details, shop_address)
                VALUES (?, 'COMPLETED', 'VERIFIED', 'PERSONAL_VERIFIED', TRUE,
                        'BUSINESS_VERIFIED', TRUE, 'SIGNED', 'COMPANY',
                        '234E', ?, 'Acme Finserv Pvt Ltd', 19.076090, 72.877426,
                        '{"fullName":"Acme Finserv Pvt Ltd","panNumber":"AABCA1234E"}'::jsonb,
                        '{"line1":"Unit 4 BKC","city":"Mumbai","state":"Maharashtra","pincode":"400051"}'::jsonb)
                """, userId, fieldCipher.encrypt("AABCA1234E"));

        // Active Authorized Signatory has Individual PAN (4th char 'P' -> BJFPM6264D)
        jdbc.update("""
                INSERT INTO business_signatories
                    (id, user_id, cin, director_name, is_self, mobile, status, verified_name,
                     aadhaar_last4, pan_last4, pan_enc, identity_details, active)
                VALUES (?, ?, 'U65999MH2020PTC123456', 'Director Signatory', FALSE, ?, 'KYC_COMPLETED',
                        'DIRECTOR SIGNATORY', ?, '6264', ?,
                        '{"dob":"1988-04-20","gender":"FEMALE","permanentAddress":{"fullAddress":"Flat 9 Andheri West","city":"Mumbai","pincode":"400053"}}'::jsonb,
                        TRUE)
                """, UUID.randomUUID(), userId, mobile, sigAadhaar.substring(8), fieldCipher.encrypt("BJFPM6264D"));

        // Add verified corporate bank account directly
        jdbc.update("""
                INSERT INTO retailer_bank_accounts
                    (id, user_id, account_name, verified_name, account_number, ifsc, bank_name, account_type, active, verification_status)
                VALUES (?, ?, 'Acme Finserv Pvt Ltd', 'Acme Finserv Pvt Ltd', '918273645501', 'SBIN0005943', 'SBI', 'CURRENT', TRUE, 'VERIFIED')
                """, UUID.randomUUID(), userId);

        simulateAlreadyExistsOnMinKyc.set(true);
        setAuth(superAdmin);

        // Pass inline 12-digit Signatory Aadhaar override during onboarding
        Map<String, Object> view = providerOnboarding.onboardToInstantPay(superAdmin, userId, sigAadhaar, null);
        assertNotNull(view);

        // Verify Individual Signatory PAN ('BJFPM6264D') and Signatory Aadhaar were sent to signup/minKyc
        Map<String, Object> minKycReq = capturedRequests.get("/user/outlet/signup/minKyc");
        assertEquals("BJFPM6264D", minKycReq.get("pan"));
        assertEquals("DIRECTOR SIGNATORY", minKycReq.get("name"));
        assertEquals("F", minKycReq.get("gender"));
        assertEquals("1988-04-20", minKycReq.get("dateOfBirth"));
        assertEquals(sigAadhaar, InstantPayCrypto.decrypt(String.valueOf(minKycReq.get("aadhaar")), ENC_KEY));

        // Verify idempotent recovery picked outletId 88441 from /user/outlet/list and synced bank!
        String savedOutlet = jdbc.queryForObject(
                "SELECT outlet_code FROM provider_merchant_accounts WHERE user_id = ? AND provider_code = 'INSTANTPAY'",
                String.class, userId);
        assertEquals("88441", savedOutlet);
    }
}
