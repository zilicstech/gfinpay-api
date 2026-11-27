package com.fintech.instantpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.aeps.AepsService;
import com.fintech.biometric.BiometricActivationService;
import com.fintech.finance.RetailerBankService;
import com.fintech.identity.AdminPlatformService;
import com.fintech.ledger.TransactionStatusVerificationService;
import com.fintech.ledger.WalletService;
import com.fintech.payout.PayoutService;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ProviderOnboardingService;
import com.fintech.verification.AadhaarValidator;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

/**
 * End-to-end integration test for Direct InstantPay AePS & independent UPI Cashpoint / AePS service rails:
 * <ul>
 *   <li>Independent {@code UPI_CASHOUT} ("UPI Cashpoint") and {@code AEPS} ("AePS Banking") Admin toggles & {@code AEPS -> CASHOUT_AEPS} commission rule mapping</li>
 *   <li>Merchant Daily 2FA ({@code /fi/aeps/outletLoginStatus} + {@code /fi/aeps/outletLogin}, including {@code TXN + LOGINREQUIRED} biometric mismatch rejection)</li>
 *   <li>Cached live bank directory ({@code GET /fi/aeps/banks}) with duplicate-IIN Gramin banks</li>
 *   <li>3.0 km shop geo-fencing & 2x consecutive customer Aadhaar biometric mismatch guard ({@code aadhaar_hmac})</li>
 *   <li>Balance Enquiry ({@code BE}), Mini Statement ({@code MS}), Cash Withdrawal {@code <= ₹5,000}, and {@code > ₹5,000} Off-Us ({@code OTP}) + On-Us ({@code TXN}) flows</li>
 *   <li>{@code TUP} pending handling, {@code TUP} webhook hold guard, {@code 30m}/{@code 4h} {@code /reports/txnStatus} reconciliation, post-settlement {@code REFUND} reversal, and full PII/secret audit redaction</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.sms.provider=mock",
        "app.surepass.mode=mock",
        "app.esign.mode=mock",
        "app.instantpay.enabled=true",
        "app.instantpay.client-id=IPAY_AEPS_TEST_CLIENT",
        "app.instantpay.client-secret=e6150d5564de4748b8b59cb4f37570c30991e3b3ae1ef2ec2fe08a34bd45ae68",
        "app.instantpay.encryption-key=253ddc7991d45f00253ddc7991d45f00",
        "app.instantpay.endpoint-ip=223.191.83.103",
        "app.instantpay.default-spkey=WAP",
        "app.instantpay.default-gw=JA",
        "app.instantpay.webhook-secret=IPAY_AEPS_WEBHOOK_777",
        "app.aeps.geofence-enabled=true",
        "app.aeps.max-geofence-km=3.0",
        "app.aeps.max-biometric-mismatches=2",
        "app.aeps.mismatch-block-hours=24"
})
@ActiveProfiles("test")
class InstantPayAepsFlowTest {

    private static final String ENC_KEY = "253ddc7991d45f00253ddc7991d45f00";

    private static final String SAMPLE_PID_XML = """
            <PidData>
              <Resp errCode="0" errInfo="Success." fCount="1" fType="2" iCount="0" iType="0" pCount="0" pType="0" nmPoints="46" qScore="88"/>
              <DeviceInfo dpId="MANTRA.MSIPL" rdsId="MANTRA.WIN.001" rdsVer="1.0.8" dc="81cfa394-d641-44cf-835d-cb65532bbc20" mi="MFS110" mc="CERT_MC_SECRET_AEPS">
                <additional_info>
                  <Param name="srno" value="MFS110AEPS99"/>
                  <Param name="sysid" value="SYS_AEPS_99"/>
                  <Param name="ts" value="2026-11-27T14:30:00+05:30"/>
                </additional_info>
              </DeviceInfo>
              <Skey ci="20261021">SKEY_AEPS_SECRET_BASE64</Skey>
              <Hmac>HMAC_AEPS_SECRET_BASE64</Hmac>
              <Data type="X">PID_AEPS_SECRET_BASE64</Data>
            </PidData>
            """;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static HttpServer mockIpayServer;
    static final Map<String, Map<String, Object>> capturedRequests = new ConcurrentHashMap<>();
    static final Map<String, Map<String, String>> capturedHeaders = new ConcurrentHashMap<>();

    static final AtomicReference<String> outletLoginActCode = new AtomicReference<>("LOGINREQUIRED");
    static final AtomicBoolean rejectNextOutletLoginMismatch = new AtomicBoolean(false);
    static final AtomicReference<String> txnOtpMode = new AtomicReference<>("OFFUS_OTP"); // OFFUS_OTP | ONUS_TXN
    static final AtomicReference<String> cwOutcomeMode = new AtomicReference<>("TXN"); // TXN | TUP | BIO_MISMATCH
    static final AtomicInteger loginStatusCalls = new AtomicInteger(0);
    static final AtomicInteger bankListCalls = new AtomicInteger(0);
    static final AtomicInteger txnStatusCalls = new AtomicInteger(0);

    static {
        String extUrl = System.getenv("TEST_DATASOURCE_URL");
        if (extUrl == null || extUrl.isBlank()) {
            POSTGRES.start();
        }
        startMockInstantPayServer();
    }

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry r) {
        String extUrl = System.getenv("TEST_DATASOURCE_URL");
        if (extUrl != null && !extUrl.isBlank()) {
            r.add("spring.datasource.url", () -> extUrl);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DATASOURCE_USERNAME", "postgres"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DATASOURCE_PASSWORD", ""));
        } else {
            r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            r.add("spring.datasource.username", POSTGRES::getUsername);
            r.add("spring.datasource.password", POSTGRES::getPassword);
        }
        r.add("app.instantpay.base-url", () -> "http://127.0.0.1:" + mockIpayServer.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        if (mockIpayServer != null) {
            mockIpayServer.stop(0);
        }
    }

    private static void startMockInstantPayServer() {
        try {
            mockIpayServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ObjectMapper om = new ObjectMapper();
        mockIpayServer.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> reqJson = bodyStr.isBlank()
                    ? Map.of()
                    : om.readValue(bodyStr, new TypeReference<>() {});
            capturedRequests.put(path, reqJson);

            Map<String, String> hdrs = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) hdrs.put(k.toLowerCase(Locale.ROOT), v.get(0));
            });
            capturedHeaders.put(path, hdrs);

            String resp;
            int code = 200;
            switch (path) {
                case "/user/outlet/signup/minKyc" -> resp = """
                        {
                          "statuscode": "TXN",
                          "actcode": null,
                          "status": "Transaction Successful",
                          "data": { "outletId": 782336, "name": "HEMANT MISHRA" },
                          "environment": "LIVE"
                        }
                        """;
                case "/user/outlet/signup/addBankAccount" -> resp = """
                        {
                          "statuscode": "TXN",
                          "actcode": null,
                          "status": "Bank Account Added Successfully",
                          "data": null,
                          "environment": "LIVE"
                        }
                        """;
                case "/user/outlet/signup/biometricKycStatus" -> resp = """
                        {
                          "statuscode": "TXN",
                          "actcode": null,
                          "status": "Approved",
                          "data": { "action": "NO-ACTION-REQUIRED", "status": "APPROVED" }
                        }
                        """;
                case "/fi/aeps/banks" -> {
                    bankListCalls.incrementAndGet();
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": null,
                              "status": "Banks fetched successfully",
                              "data": [
                                {
                                  "bankId": 109005,
                                  "name": "State Bank of India",
                                  "iin": "607094",
                                  "aepsEnabled": true,
                                  "aepsCwOtpMin": "5000.01",
                                  "aadhaarpayEnabled": true,
                                  "aepsFailureRate": "24"
                                },
                                {
                                  "bankId": 30301,
                                  "name": "ARYAVART BANK",
                                  "iin": "606993",
                                  "aepsEnabled": true,
                                  "aepsCwOtpMin": "5000.01",
                                  "aepsFailureRate": "49"
                                },
                                {
                                  "bankId": 134125,
                                  "name": "UTTAR PRADESH GRAMIN BANK",
                                  "iin": "606993",
                                  "aepsEnabled": true,
                                  "aepsCwOtpMin": "5000.01",
                                  "aepsFailureRate": "39"
                                },
                                {
                                  "bankId": 99999,
                                  "name": "DISABLED TEST BANK",
                                  "iin": "609999",
                                  "aepsEnabled": false,
                                  "aepsCwOtpMin": "5000.01",
                                  "aepsFailureRate": "100"
                                }
                              ],
                              "environment": "LIVE"
                            }
                            """;
                }
                case "/fi/aeps/outletLoginStatus" -> {
                    loginStatusCalls.incrementAndGet();
                    String act = outletLoginActCode.get();
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": "%s",
                              "status": "Transaction Successful",
                              "data": {
                                "isFaceAuthAvailable": true,
                                "isIrisAuthAvailable": false,
                                "aadhaarLastFour": "7183",
                                "isTxnBioLoginRequired": false
                              },
                              "environment": "LIVE"
                            }
                            """.formatted(act);
                }
                case "/fi/aeps/outletLogin" -> {
                    if (rejectNextOutletLoginMismatch.compareAndSet(true, false)) {
                        // Critical InstantPay quirk: returns statuscode=TXN + actcode=LOGINREQUIRED on mismatch!
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": "LOGINREQUIRED",
                                  "status": "Biometric Data did not match",
                                  "data": {
                                    "aadhaarLastFour": "7183",
                                    "isTxnBioLoginRequired": false
                                  },
                                  "orderid": "ORD_2FA_MISMATCH",
                                  "environment": "LIVE"
                                }
                                """;
                    } else {
                        outletLoginActCode.set("LOGGEDIN");
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": "LOGGEDIN",
                                  "status": "Transaction Successful",
                                  "data": {
                                    "isFaceAuthAvailable": true,
                                    "aadhaarLastFour": "7183",
                                    "isTxnBioLoginRequired": false,
                                    "poolReferenceId": "ORD_2FA_OK"
                                  },
                                  "orderid": "ORD_2FA_OK",
                                  "environment": "LIVE"
                                }
                                """;
                    }
                }
                case "/fi/aeps/transactionOtp" -> {
                    if ("ONUS_TXN".equals(txnOtpMode.get())) {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": null,
                                  "status": "OTP not required for this transation",
                                  "data": {
                                    "referenceKey": "REF_ONUS_NO_OTP_888"
                                  },
                                  "environment": "LIVE"
                                }
                                """;
                    } else {
                        resp = """
                                {
                                  "statuscode": "OTP",
                                  "actcode": null,
                                  "status": "OTP Successfully sent",
                                  "data": {
                                    "validity": "2026-11-27 16:00:00",
                                    "referenceKey": "REF_OFFUS_OTP_999"
                                  },
                                  "environment": "LIVE"
                                }
                                """;
                    }
                }
                case "/fi/aeps/balanceInquiry" -> {
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "BE1"));
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": null,
                              "status": "Transaction Successful",
                              "data": {
                                "externalRef": "%s",
                                "bankName": "State Bank of India",
                                "accountNumber": "xxxxxxxx0191",
                                "ipayId": "DNA_%s",
                                "transactionMode": "DR",
                                "payableValue": "0.00",
                                "transactionValue": "0.00",
                                "operatorId": "OPR_BE_%s",
                                "bankAccountBalance": "1520.00",
                                "miniStatement": []
                              },
                              "orderid": "DNA_%s",
                              "environment": "LIVE"
                            }
                            """.formatted(extRef, extRef, extRef, extRef);
                }
                case "/fi/aeps/miniStatement" -> {
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "MS1"));
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": null,
                              "status": "Transaction Successful",
                              "data": {
                                "externalRef": "%s",
                                "bankName": "State Bank of India",
                                "accountNumber": "xxxxxxxx7259",
                                "ipayId": "CNA_%s",
                                "transactionMode": "CR",
                                "payableValue": "0.95",
                                "transactionValue": "0.00",
                                "operatorId": "OPR_MS_%s",
                                "bankAccountBalance": "163.00",
                                "miniStatement": [
                                  { "date": "25/11/2026", "txnType": "CR", "amount": "2000.00", "narration": "APBS/PM" },
                                  { "date": "26/11/2026", "txnType": "DR", "amount": "500.00", "narration": "NFI/CASH" }
                                ]
                              },
                              "orderid": "ORD_MS_%s",
                              "environment": "LIVE"
                            }
                            """.formatted(extRef, extRef, extRef, extRef);
                }
                case "/fi/aeps/cashWithdrawal" -> {
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "CW1"));
                    String amt = String.valueOf(reqJson.getOrDefault("amount", "1500"));
                    String mode = cwOutcomeMode.get();
                    if ("TUP".equals(mode)) {
                        resp = """
                                {
                                  "statuscode": "TUP",
                                  "actcode": null,
                                  "status": "Transaction Under Process",
                                  "data": {
                                    "externalRef": "%s",
                                    "ipayId": "IPAY_TUP_%s"
                                  },
                                  "orderid": "ORD_TUP_%s",
                                  "environment": "LIVE"
                                }
                                """.formatted(extRef, extRef, extRef);
                    } else if ("BIO_MISMATCH".equals(mode)) {
                        resp = """
                                {
                                  "statuscode": "U3",
                                  "actcode": null,
                                  "status": "Biometric data did not match",
                                  "internalCode": "300",
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
                                    "externalRef": "%s",
                                    "bankName": "State Bank of India",
                                    "accountNumber": "xxxxxxxx1234",
                                    "ipayId": "CNA_CW_%s",
                                    "transactionMode": "CR",
                                    "payableValue": "%s.00",
                                    "transactionValue": "%s.00",
                                    "operatorId": "RRN_%s",
                                    "bankAccountBalance": "8420.50",
                                    "miniStatement": [],
                                    "isOnusTxn": true
                                  },
                                  "orderid": "ORD_CW_%s",
                                  "environment": "LIVE"
                                }
                                """.formatted(extRef, extRef, amt, amt, extRef, extRef);
                    }
                }
                case "/reports/txnStatus" -> {
                    txnStatusCalls.incrementAndGet();
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "CW1"));
                    resp = """
                            {
                              "statuscode": "TXN",
                              "status": "-",
                              "data": {
                                "transactionStatusCode": "TXN",
                                "transactionStatus": "Transaction Successful",
                                "transactionReferenceId": "RRN_RECON_%s",
                                "order": { "referenceId": "ORD_RECON_%s", "externalRef": "%s" }
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
    @Autowired AdminPlatformService adminPlatformService;
    @Autowired ProviderOnboardingService providerOnboarding;
    @Autowired BiometricActivationService biometricService;
    @Autowired AepsService aepsService;
    @Autowired InstantPayAepsAdapter aepsAdapter;
    @Autowired InstantPayCallbackService callbackService;
    @Autowired TransactionStatusVerificationService verifyStatusService;
    @Autowired InstantPayAuditLogService auditLogs;
    @Autowired PayoutService payoutService;
    @Autowired RetailerBankService retailerBankService;
    @Autowired WalletService walletService;
    @Autowired FieldCipher fieldCipher;

    private AuthPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        capturedRequests.clear();
        capturedHeaders.clear();
        outletLoginActCode.set("LOGINREQUIRED");
        rejectNextOutletLoginMismatch.set(false);
        txnOtpMode.set("OFFUS_OTP");
        cwOutcomeMode.set("TXN");
        loginStatusCalls.set(0);
        bankListCalls.set(0);
        txnStatusCalls.set(0);
        aepsAdapter.evictBankCache();

        jdbc.update("UPDATE platform_services SET enabled = TRUE WHERE code IN ('UPI_CASHOUT', 'AEPS', 'PAYOUT')");
        jdbc.update("UPDATE service_routing_policies SET strategy = 'PRIMARY_FALLBACK', active = TRUE WHERE service_code = 'AEPS'");

        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        superAdmin = new AuthPrincipal(adminId, "Test Super Admin", "SUPER_ADMIN", null);
        setAuth(superAdmin);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE platform_services SET enabled = TRUE WHERE code IN ('UPI_CASHOUT', 'AEPS', 'PAYOUT')");
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

    private AuthPrincipal createOnboardedInstantPayRetailer(String merchantAadhaar12) {
        String mobile = randomMobile();
        UUID userId = UUID.randomUUID();
        String code = "RA" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO users (id, user_type, full_name, mobile, email, password_hash, status, code)
                VALUES (?, 'RETAILER', 'Hemant Mishra', ?, ?, ?, 'ACTIVE', ?)
                """,
                userId, mobile, "aeps." + mobile + "@gfinpay.test", passwordEncoder.encode("Secret@123"), code);

        walletService.createForUser(userId);
        jdbc.update("UPDATE wallets SET status = 'ACTIVE' WHERE user_id = ?", userId);

        // Registered shop coordinates: 28.6195, 77.2804
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
                        'Mishra Banking Point', 28.619500, 77.280400,
                        '{"fullName":"Hemant Mishra","panName":"HEMANT MISHRA","dob":"1991-09-12","gender":"MALE","permanentAddress":{"fullAddress":"Laxmi Nagar Delhi","city":"Delhi","pincode":"110092"}}'::jsonb,
                        '{"line1":"Laxmi Nagar","city":"Delhi","state":"Delhi","pincode":"110092"}'::jsonb)
                """,
                userId, merchantAadhaar12.substring(8), fieldCipher.encrypt(merchantAadhaar12), fieldCipher.encrypt("BJFPM6264D"));

        AuthPrincipal retailer = new AuthPrincipal(userId, "Hemant Mishra", "RETAILER", null);
        String suffix = mobile.substring(6);
        if ("0000".equals(suffix) || "9999".equals(suffix)) suffix = "3579";
        retailerBankService.verifyAndCreate(retailer, "50100777" + suffix, "IDFB0021006", "SAVINGS");

        setAuth(superAdmin);
        providerOnboarding.onboardToInstantPay(superAdmin, userId, null, null);
        UUID ipayChannelId = jdbc.queryForObject(
                "SELECT id FROM service_provider_channels WHERE provider_code = 'INSTANTPAY' AND pipe_code = 'DIRECT'",
                UUID.class);
        providerOnboarding.setProviderPriorities(
                superAdmin, userId, List.of(new ProviderOnboardingService.ProviderPriority("INSTANTPAY", 1)));
        providerOnboarding.updateRetailerChannel(superAdmin, userId, ipayChannelId, true, false, 1);
        return retailer;
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullInstantPayAeps2faBanksOtpGeoFenceMismatchTupAndSeparateServiceRailsFlow() {
        String merchantAadhaar = "209313587183";
        String customerAadhaar1 = validAadhaar("45678912345");
        String customerAadhaar2 = validAadhaar("78912345678");
        AuthPrincipal retailer = createOnboardedInstantPayRetailer(merchantAadhaar);
        UUID userId = retailer.userId();

        // 1. Admin Settings: verify renamed labels + independent UPI Cashpoint vs AePS toggles + AEPS -> CASHOUT_AEPS commission
        setAuth(superAdmin);
        Map<String, Object> savedComm = adminPlatformService.saveCommission(
                "AEPS", "PERCENT", new BigDecimal("0.40"), new BigDecimal("0.10"), BigDecimal.ZERO, null);
        assertEquals("CASHOUT_AEPS", savedComm.get("txnType"));

        // Disable UPI_CASHOUT ("UPI Cashpoint") -> AePS remains enabled & active!
        adminPlatformService.setServiceEnabled("UPI_CASHOUT", false);
        setAuth(retailer);
        Map<String, Object> activationUpiOff = biometricService.getCashActivation(retailer, false);
        assertEquals(Boolean.FALSE, activationUpiOff.get("upiServiceEnabled"));
        assertEquals(Boolean.FALSE, activationUpiOff.get("upiCashoutActive"));
        assertEquals(Boolean.TRUE, activationUpiOff.get("aepsServiceEnabled"));
        assertEquals(Boolean.TRUE, activationUpiOff.get("aepsActive"));

        // Disable AEPS ("AePS Banking") -> /api/v1/aeps/* immediately blocked with SERVICE_DISABLED!
        setAuth(superAdmin);
        adminPlatformService.setServiceEnabled("AEPS", false);
        setAuth(retailer);
        ApiException disabledEx = assertThrows(ApiException.class, () -> aepsService.getStatus(retailer, false));
        assertEquals("SERVICE_DISABLED", disabledEx.getCode());

        // Re-enable both services
        setAuth(superAdmin);
        adminPlatformService.setServiceEnabled("UPI_CASHOUT", true);
        adminPlatformService.setServiceEnabled("AEPS", true);

        // 2. Dynamic Bank List (/fi/aeps/banks): X-Ipay-Outlet-Id sent, disabled filtered, duplicate IINs preserved, cached
        setAuth(retailer);
        List<Map<String, Object>> banks1 = aepsService.listBanks(retailer);
        List<Map<String, Object>> banks2 = aepsService.listBanks(retailer);
        assertEquals(1, bankListCalls.get(), "2nd listBanks call should be served from 30m cache");
        assertEquals("782336", capturedHeaders.get("/fi/aeps/banks").get("x-ipay-outlet-id"));
        assertEquals(3, banks1.size(), "Disabled bank 609999 must be filtered out while both 606993 Gramin banks remain");
        assertEquals(2L, banks1.stream().filter(b -> "606993".equals(b.get("iin"))).count());

        // 3. Merchant Daily 2FA: LOGINREQUIRED blocks CW -> Mismatch (TXN + LOGINREQUIRED) rejected -> LOGGEDIN unlocks!
        Map<String, Object> pre2fa = aepsService.getStatus(retailer, false);
        assertEquals(Boolean.TRUE, pre2fa.get("loginRequired"));
        assertEquals(Boolean.FALSE, pre2fa.get("loggedInToday"));

        ApiException need2faEx = assertThrows(
                ApiException.class,
                () -> aepsService.transact(retailer, "CW", customerAadhaar1, "607094", "9876543210", 1500,
                        SAMPLE_PID_XML, "28.6196", "77.2805"));
        assertEquals("AEPS_2FA_REQUIRED", need2faEx.getCode());

        // Geo-fence check on 2FA: >3.0 km from shop (28.6195, 77.2804 -> 28.7000, 77.2804 ~8.95 km) must fail!
        ApiException geoEx = assertThrows(
                ApiException.class,
                () -> aepsService.submitOutletLogin(retailer, SAMPLE_PID_XML, "FINGER", "28.7000", "77.2804"));
        assertEquals("AEPS_OUTSIDE_GEOFENCE", geoEx.getCode());

        // InstantPay quirk: statuscode=TXN + actcode=LOGINREQUIRED must be rejected as mismatch!
        rejectNextOutletLoginMismatch.set(true);
        ApiException mismatch2faEx = assertThrows(
                ApiException.class,
                () -> aepsService.submitOutletLogin(retailer, SAMPLE_PID_XML, "FINGER", "28.6196", "77.2805"));
        assertEquals("AEPS_2FA_FAILED", mismatch2faEx.getCode());

        // Valid Merchant 2FA scan -> LOGGEDIN!
        Map<String, Object> post2fa = aepsService.submitOutletLogin(
                retailer, SAMPLE_PID_XML, "FINGER", "28.6196", "77.2805");
        assertEquals(Boolean.TRUE, post2fa.get("loggedInToday"));
        assertEquals(Boolean.FALSE, post2fa.get("loginRequired"));

        Map<String, Object> loginReq = capturedRequests.get("/fi/aeps/outletLogin");
        assertEquals("DAILY_LOGIN", loginReq.get("type"));
        assertEquals("FINGER", loginReq.get("captureType"));
        Map<String, Object> loginBio = (Map<String, Object>) loginReq.get("biometricData");
        assertEquals(25, loginBio.size());
        assertEquals(0, loginBio.get("iCount"));
        assertEquals(0, loginBio.get("pCount"));
        assertEquals("", loginBio.get("iType"));
        assertEquals(merchantAadhaar, InstantPayCrypto.decrypt(String.valueOf(loginBio.get("encryptedAadhaar")), ENC_KEY));

        // Cached status check does not re-hit /fi/aeps/outletLoginStatus when already LOGGED_IN today
        int beforeCachedStatus = loginStatusCalls.get();
        aepsService.getStatus(retailer, false);
        assertEquals(beforeCachedStatus, loginStatusCalls.get());

        // 4. Balance Enquiry (BE) & Mini Statement (MS) — zero wallet change
        Map<String, Object> beRes = aepsService.transact(
                retailer, "BE", customerAadhaar1, "607094", "9876543210", 0, SAMPLE_PID_XML, "28.6196", "77.2805");
        assertEquals("SUCCESS", beRes.get("status"));
        assertEquals("xxxxxxxx0191", beRes.get("maskedAccountNumber"));
        assertEquals(0, new BigDecimal("1520.00").compareTo((BigDecimal) beRes.get("availableBalance")));

        Map<String, Object> msRes = aepsService.transact(
                retailer, "MS", customerAadhaar1, "607094", "9876543210", 0, SAMPLE_PID_XML, "28.6196", "77.2805");
        assertEquals("SUCCESS", msRes.get("status"));
        List<?> miniRows = (List<?>) msRes.get("miniStatement");
        assertNotNull(miniRows);
        assertEquals(2, miniRows.size());
        assertEquals(0, BigDecimal.ZERO.compareTo(walletService.getByUser(userId).available()));

        // 5. Cash Withdrawal <= ₹5,000 (₹1,500) -> +₹1,500 operating wallet, +₹6.00 rewards wallet, Payout counter +1
        Map<String, Object> cw1500 = aepsService.transact(
                retailer, "CW", customerAadhaar1, "607094", "9876543210", 1500, SAMPLE_PID_XML, "28.6196", "77.2805");
        assertEquals("SUCCESS", cw1500.get("status"));
        assertEquals(Boolean.TRUE, cw1500.get("isOnus"));
        assertEquals(0, new BigDecimal("1500.00").compareTo(walletService.getByUser(userId).available()));
        assertEquals(0, new BigDecimal("6.00").compareTo(walletService.getRewards(userId).available()));
        assertEquals(1, payoutService.checkEligibility(retailer).get("completedCashoutCount"));

        // 6. Cash Withdrawal > ₹5,000 (₹6,000): blocked without OTP -> Off-Us OTP + On-Us TXN
        ApiException missingOtpEx = assertThrows(
                ApiException.class,
                () -> aepsService.transact(retailer, "CW", customerAadhaar1, "607094", "9876543210", 6000,
                        SAMPLE_PID_XML, "28.6196", "77.2805"));
        assertEquals("AEPS_OTP_REQUIRED", missingOtpEx.getCode());

        // 6a. Off-Us OTP flow (statuscode=OTP -> referenceKey=REF_OFFUS_OTP_999)
        txnOtpMode.set("OFFUS_OTP");
        Map<String, Object> otpChallenge = aepsService.requestTransactionOtp(
                retailer, "607094", customerAadhaar1, "9876543210", 6000, "28.6196", "77.2805");
        assertEquals(Boolean.TRUE, otpChallenge.get("otpRequired"));
        UUID otpSession1 = (UUID) otpChallenge.get("otpSessionId");
        assertNotNull(otpSession1);
        assertEquals(
                customerAadhaar1,
                InstantPayCrypto.decrypt(
                        String.valueOf(capturedRequests.get("/fi/aeps/transactionOtp").get("encryptedAadhaar")),
                        ENC_KEY));

        Map<String, Object> cw6000OffUs = aepsService.transact(
                retailer, "CW", customerAadhaar1, "607094", "9876543210", 6000,
                SAMPLE_PID_XML, "FINGER", otpSession1, "28.6196", "77.2805");
        assertEquals("SUCCESS", cw6000OffUs.get("status"));
        assertEquals("REF_OFFUS_OTP_999", capturedRequests.get("/fi/aeps/cashWithdrawal").get("referenceKey"));
        assertEquals(0, new BigDecimal("7500.00").compareTo(walletService.getByUser(userId).available()));

        // Reusing consumed otpSessionId must be rejected
        ApiException consumedEx = assertThrows(
                ApiException.class,
                () -> aepsService.transact(retailer, "CW", customerAadhaar1, "607094", "9876543210", 6000,
                        SAMPLE_PID_XML, "FINGER", otpSession1, "28.6196", "77.2805"));
        assertEquals("AEPS_OTP_CONSUMED", consumedEx.getCode());

        // 6b. On-Us flow (statuscode=TXN -> otpRequired=false, referenceKey=REF_ONUS_NO_OTP_888)
        txnOtpMode.set("ONUS_TXN");
        Map<String, Object> onUsChallenge = aepsService.requestTransactionOtp(
                retailer, "607094", customerAadhaar1, "9876543210", 5500, "28.6196", "77.2805");
        assertEquals(Boolean.FALSE, onUsChallenge.get("otpRequired"));
        UUID otpSession2 = (UUID) onUsChallenge.get("otpSessionId");

        Map<String, Object> cw5500OnUs = aepsService.transact(
                retailer, "CW", customerAadhaar1, "607094", "9876543210", 5500,
                SAMPLE_PID_XML, "FINGER", otpSession2, "28.6196", "77.2805");
        assertEquals("SUCCESS", cw5500OnUs.get("status"));
        assertEquals("REF_ONUS_NO_OTP_888", capturedRequests.get("/fi/aeps/cashWithdrawal").get("referenceKey"));
        assertEquals(0, new BigDecimal("13000.00").compareTo(walletService.getByUser(userId).available()));

        // 7. 2x Consecutive Biometric Mismatch Guard on customerAadhaar2 (aadhaar_hmac, zero plain Aadhaar stored)
        cwOutcomeMode.set("BIO_MISMATCH");
        assertThrows(ApiException.class, () -> aepsService.transact(
                retailer, "CW", customerAadhaar2, "607094", "9876543219", 500, SAMPLE_PID_XML, "28.6196", "77.2805"));
        assertThrows(ApiException.class, () -> aepsService.transact(
                retailer, "CW", customerAadhaar2, "607094", "9876543219", 500, SAMPLE_PID_XML, "28.6196", "77.2805"));

        // 3rd attempt with customerAadhaar2 is blocked BEFORE hitting InstantPay!
        cwOutcomeMode.set("TXN");
        ApiException blockedBioEx = assertThrows(ApiException.class, () -> aepsService.transact(
                retailer, "CW", customerAadhaar2, "607094", "9876543219", 500, SAMPLE_PID_XML, "28.6196", "77.2805"));
        assertEquals("AEPS_BIOMETRIC_MISMATCH_BLOCKED", blockedBioEx.getCode());

        // Meanwhile customerAadhaar1 is completely unaffected!
        // 8. TUP Pending CW (₹1,000) -> TUP webhook never auto-fails -> <30m throttled -> >30m /reports/txnStatus settles -> REFUND reverses!
        cwOutcomeMode.set("TUP");
        Map<String, Object> cwPending = aepsService.transact(
                retailer, "CW", customerAadhaar1, "607094", "9876543210", 1000, SAMPLE_PID_XML, "28.6196", "77.2805");
        assertEquals("PENDING", cwPending.get("status"));
        UUID pendingAepsId = (UUID) cwPending.get("id");
        UUID pendingTxnId = (UUID) cwPending.get("transactionId");
        String pendingRef = String.valueOf(cwPending.get("reference"));
        assertEquals(0, new BigDecimal("13000.00").compareTo(walletService.getByUser(userId).available()));

        // TUP timeout callback -> MUST NOT mark FAILED!
        callbackService.handleInbound(
                "POST", "IPAY_AEPS_WEBHOOK_777",
                Map.of("ipay_id", "ORD_TUP_" + pendingRef, "agent_id", pendingRef, "opr_id", "",
                        "status", "FAILED", "res_code", "TUP", "res_msg", "Response Timedout", "txn_mode", "CR"),
                "", "/webhooks/instantpay/callback/IPAY_AEPS_WEBHOOK_777", "/webhooks/instantpay/callback/IPAY_AEPS_WEBHOOK_777", Map.of());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM aeps_transactions WHERE id = ?", String.class, pendingAepsId));

        // Immediate (<30m) refresh -> throttled locally without hitting /reports/txnStatus
        aepsService.refreshTransaction(retailer, pendingAepsId);
        assertEquals(0, txnStatusCalls.get());

        // Backdate created_at by 35 minutes -> verifyForDesk polls /reports/txnStatus -> settles +₹1,000!
        jdbc.update("UPDATE aeps_transactions SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(35))), pendingAepsId);
        Map<String, Object> verified = verifyStatusService.verifyForDesk(retailer, pendingTxnId);
        assertEquals("SUCCESS", verified.get("currentState"));
        assertEquals(1, txnStatusCalls.get());
        assertEquals(0, new BigDecimal("14000.00").compareTo(walletService.getByUser(userId).available()));

        // Post-settlement REFUND callback -> transitions to REVERSED & debits ₹1,000 back from wallet!
        callbackService.handleInbound(
                "POST", "IPAY_AEPS_WEBHOOK_777",
                Map.of("ipay_id", "ORD_TUP_" + pendingRef, "agent_id", pendingRef, "opr_id", "RRN_REV",
                        "status", "REFUND", "res_code", "RPI", "res_msg", "NPCI chargeback reversal", "txn_mode", "CR"),
                "", "/webhooks/instantpay/callback/IPAY_AEPS_WEBHOOK_777", "/webhooks/instantpay/callback/IPAY_AEPS_WEBHOOK_777", Map.of());
        assertEquals("REVERSED", jdbc.queryForObject("SELECT status FROM aeps_transactions WHERE id = ?", String.class, pendingAepsId));
        assertEquals(0, new BigDecimal("13000.00").compareTo(walletService.getByUser(userId).available()));

        // 9. Verify full InstantPay AePS audit logs & strict PII / biometric / secret redaction
        auditLogs.awaitPending(Duration.ofSeconds(5));
        List<Map<String, Object>> userLogs = auditLogs.listForUser(userId, 100);
        assertTrue(userLogs.size() >= 12);
        String logsText = userLogs.toString();
        assertFalse(logsText.contains(merchantAadhaar), "Merchant plain Aadhaar must never appear in logs");
        assertFalse(logsText.contains(customerAadhaar1), "Customer plain Aadhaar must never appear in logs");
        assertFalse(logsText.contains(customerAadhaar2), "Customer 2 plain Aadhaar must never appear in logs");
        assertFalse(logsText.contains("PID_AEPS_SECRET_BASE64"), "PID XML ciphertext must be redacted");
        assertFalse(logsText.contains("SKEY_AEPS_SECRET_BASE64"), "RD sessionKey must be redacted");
        assertFalse(logsText.contains("HMAC_AEPS_SECRET_BASE64"), "RD hmac must be redacted");
        assertFalse(logsText.contains("e6150d5564de4748b8b59cb4f37570c30991e3b3"), "Client secret must be redacted");
        assertFalse(logsText.contains("IPAY_AEPS_WEBHOOK_777"), "Webhook secret path token must be redacted");
    }
}
