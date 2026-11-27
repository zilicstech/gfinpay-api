package com.fintech.instantpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.biometric.BiometricActivationService;
import com.fintech.dmt.DmtBankListService;
import com.fintech.dmt.DmtCasaService;
import com.fintech.dmt.DmtStatusReconciliationJob;
import com.fintech.finance.RetailerBankService;
import com.fintech.identity.AdminPlatformService;
import com.fintech.ledger.TransactionStatusVerificationService;
import com.fintech.ledger.WalletService;
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
import java.util.Set;
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
 * End-to-end integration test for Direct InstantPay Domestic Money Transfer ({@code DMT v2}):
 * <ul>
 *   <li>Strict Merchant {@code FULL_KYC} gate ({@code PENDING_BIOMETRIC} blocked with {@code BIOMETRIC_KYC_REQUIRED})</li>
 *   <li>Live {@code POST /fi/remit/out/domestic/v2/banks} sync (raw {@code ""} string body) preserving Admin {@code active=FALSE} overrides</li>
 *   <li>Property-backed default surcharge ({@code 1.00%} / {@code ₹10} min) + Admin custom DMT surcharge &amp; partner rewards commission</li>
 *   <li>One-time Remitter onboarding ({@code RNF} -&gt; {@code remitterRegistration} AES-256-CBC Aadhaar -&gt; {@code remitterRegistrationVerify} -&gt; {@code remitterKyc} 12-field {@code biometricData} with uppercase {@code Skey} + {@code wadh}) and permanent zero-repeat eKYC</li>
 *   <li>3.0 km shop geo-fencing on both {@code remitterKyc} and {@code transaction}</li>
 *   <li>OTP-verified Beneficiary Registration &amp; Deletion</li>
 *   <li>Retryable {@code INVALID_OTP} vs {@code OTPGENREF} ({@code DMT_OTP_REGENERATE_REQUIRED}), {@code TUP} pending hold, {@code 30m} cooling throttle, {@code /reports/txnStatus} resolution &amp; {@code SUCCESS} reconfirmation + post-settlement bank return reversal, and {@code instantpay_api_logs} PII redaction</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.sms.provider=mock",
        "app.surepass.mode=mock",
        "app.esign.mode=mock",
        "app.instantpay.enabled=true",
        "app.instantpay.client-id=IPAY_DMT_TEST_CLIENT",
        "app.instantpay.client-secret=e6150d5564de4748b8b59cb4f37570c30991e3b3ae1ef2ec2fe08a34bd45ae68",
        "app.instantpay.encryption-key=253ddc7991d45f00253ddc7991d45f00",
        "app.instantpay.endpoint-ip=223.191.83.103",
        "app.dmt.geofence-enabled=true",
        "app.dmt.max-geofence-km=3.0",
        "app.dmt.default-surcharge-mode=PERCENT",
        "app.dmt.default-surcharge-value=1.00",
        "app.dmt.default-surcharge-min=10.00"
})
@ActiveProfiles("test")
class InstantPayDmtFlowTest {

    private static final String ENC_KEY = "253ddc7991d45f00253ddc7991d45f00";

    private static final String SAMPLE_PID_XML = """
            <PidData>
              <Resp errCode="0" errInfo="Success." fCount="1" fType="2" iCount="0" iType="0" pCount="0" pType="0" nmPoints="48" qScore="91"/>
              <DeviceInfo dpId="MANTRA.MSIPL" rdsId="MANTRA.WIN.001" rdsVer="1.0.8" dc="91cfa394-d641-44cf-835d-cb65532bbc99" mi="MFS110" mc="CERT_MC_SECRET_DMT">
                <additional_info>
                  <Param name="srno" value="MFS110DMT77"/>
                  <Param name="sysid" value="SYS_DMT_77"/>
                  <Param name="ts" value="2026-11-27T16:15:00+05:30"/>
                </additional_info>
              </DeviceInfo>
              <Skey ci="20261021">SKEY_DMT_SECRET_BASE64</Skey>
              <Hmac>HMAC_DMT_SECRET_BASE64</Hmac>
              <Data type="X">PID_DMT_SECRET_BASE64</Data>
            </PidData>
            """;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static HttpServer mockIpayServer;
    static final Map<String, Map<String, Object>> capturedRequests = new ConcurrentHashMap<>();
    static final Map<String, String> capturedRawBodies = new ConcurrentHashMap<>();
    static final Map<String, Map<String, String>> capturedHeaders = new ConcurrentHashMap<>();

    static final AtomicBoolean remitterOnboarded = new AtomicBoolean(false);
    static final AtomicReference<String> transferOutcomeMode = new AtomicReference<>("TXN"); // TXN | TUP | WRONG_OTP | OTPGENREF
    static final AtomicReference<String> txnStatusMode = new AtomicReference<>("TXN"); // TXN | FAILED | REFUND
    static final AtomicInteger profileLookupCalls = new AtomicInteger(0);
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
            capturedRawBodies.put(path, bodyStr);
            Map<String, Object> reqJson = Map.of();
            if (!bodyStr.isBlank() && bodyStr.trim().startsWith("{")) {
                reqJson = om.readValue(bodyStr, new TypeReference<>() {});
            }
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
                          "data": { "outletId": 782991, "name": "RAJESH VERMA" },
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
                case "/fi/remit/out/domestic/v2/banks" -> resp = """
                        {
                          "statuscode": "TXN",
                          "actcode": null,
                          "status": "Transaction Successful",
                          "data": [
                            {
                              "bankId": "109005",
                              "name": "State Bank of India",
                              "ifscAlias": "SBIN",
                              "ifscGlobal": "SBIN0000001",
                              "neftEnabled": 1,
                              "neftFailureRate": "0%",
                              "impsEnabled": 1,
                              "impsFailureRate": "1.2%"
                            },
                            {
                              "bankId": "109012",
                              "name": "HDFC Bank",
                              "ifscAlias": "HDFC",
                              "ifscGlobal": "HDFC0000001",
                              "neftEnabled": 1,
                              "neftFailureRate": "0%",
                              "impsEnabled": 1,
                              "impsFailureRate": "0.5%"
                            }
                          ]
                        }
                        """;
                case "/fi/remit/out/domestic/v2/remitterProfile" -> {
                    int callNo = profileLookupCalls.incrementAndGet();
                    String mob = String.valueOf(reqJson.getOrDefault("mobileNumber", "9876543210"));
                    if (!remitterOnboarded.get()) {
                        resp = """
                                {
                                  "statuscode": "RNF",
                                  "actcode": null,
                                  "status": "Remitter Not Found",
                                  "data": {
                                    "referenceKey": "REF_PROF_RNF_%d",
                                    "validity": "2099-12-31 23:59:59",
                                    "pidOptionWadh": "E0jzJ/P8UopUHAieZn8CKqS4WPMi5ZSYXgfnlfkWjrc="
                                  }
                                }
                                """.formatted(callNo);
                    } else {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": null,
                                  "status": "Transaction Successful",
                                  "data": {
                                    "mobileNumber": "%s",
                                    "firstName": "Suresh",
                                    "lastName": "Sharma",
                                    "city": "New Delhi",
                                    "pincode": "110092",
                                    "limitPerTransaction": 5000,
                                    "limitDetails": {
                                      "maximumDailyLimit": 25000,
                                      "consumedDailyLimit": 0,
                                      "availableDailyLimit": 25000,
                                      "maximumMonthlyLimit": 25000,
                                      "consumedMonthlyLimit": 0,
                                      "availableMonthlyLimit": 25000
                                    },
                                    "beneficiaries": [],
                                    "referenceKey": "REF_PROF_ACTIVE_%d",
                                    "validity": "2099-12-31 23:59:59",
                                    "isTxnOtpRequired": 1,
                                    "isTxnBioRequired": 0,
                                    "impsAllowed": 1,
                                    "neftAllowed": 1,
                                    "isFaceAuthAvailable": 0,
                                    "pidOptionWadh": "E0jzJ/P8UopUHAieZn8CKqS4WPMi5ZSYXgfnlfkWjrc="
                                  }
                                }
                                """.formatted(mob, callNo);
                    }
                }
                case "/fi/remit/out/domestic/v2/remitterRegistration" -> resp = """
                        {
                          "statuscode": "OTP",
                          "actcode": null,
                          "status": "OTP sent successfully",
                          "data": {
                            "validity": "2099-12-31 23:59:59",
                            "referenceKey": "REF_REG_OTP_SECRET_111"
                          }
                        }
                        """;
                case "/fi/remit/out/domestic/v2/remitterRegistrationVerify" -> resp = """
                        {
                          "statuscode": "KYC",
                          "actcode": null,
                          "status": "Remitter KYC Required",
                          "data": {
                            "validity": "2099-12-31 23:59:59",
                            "referenceKey": "REF_REG_VERIFIED_SECRET_222"
                          }
                        }
                        """;
                case "/fi/remit/out/domestic/v2/remitterKyc" -> {
                    remitterOnboarded.set(true);
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": null,
                              "status": "Transaction Successful",
                              "data": {
                                "poolReferenceId": "1260206121549999",
                                "pool": {
                                  "account": "9876543210",
                                  "openingBal": "1050.00",
                                  "mode": "DR",
                                  "amount": "0.00",
                                  "closingBal": "1050.00"
                                }
                              },
                              "orderid": "1260206121549999"
                            }
                            """;
                }
                case "/fi/remit/out/domestic/v2/beneficiaryRegistration" -> {
                    String acc = String.valueOf(reqJson.getOrDefault("accountNumber", ""));
                    String beneId = acc.endsWith("0795") ? "456625" : "456626";
                    resp = """
                            {
                              "statuscode": "OTP",
                              "actcode": null,
                              "status": "OTP sent successfully",
                              "data": {
                                "beneficiaryId": "%s",
                                "validity": "2099-12-31 23:59:59",
                                "referenceKey": "REF_BENE_REG_SECRET_%s"
                              }
                            }
                            """.formatted(beneId, beneId);
                }
                case "/fi/remit/out/domestic/v2/beneficiaryRegistrationVerify" -> {
                    String beneId = String.valueOf(reqJson.getOrDefault("beneficiaryId", "456625"));
                    resp = """
                            {
                              "statuscode": "TXN",
                              "actcode": null,
                              "status": "Beneficiary Added Successfully",
                              "data": {
                                "beneficiaryId": "%s",
                                "referenceKey": "REF_BENE_OK_%s"
                              }
                            }
                            """.formatted(beneId, beneId);
                }
                case "/fi/remit/out/domestic/v2/beneficiaryDelete" -> resp = """
                        {
                          "statuscode": "OTP",
                          "actcode": null,
                          "status": "OTP sent successfully",
                          "data": {
                            "validity": "2099-12-31 23:59:59",
                            "referenceKey": "REF_BENE_DEL_SECRET_333"
                          }
                        }
                        """;
                case "/fi/remit/out/domestic/v2/beneficiaryDeleteVerify" -> resp = """
                        {
                          "statuscode": "TXN",
                          "actcode": null,
                          "status": "Beneficiary Deleted Successfully",
                          "data": {
                            "referenceKey": "REF_BENE_DEL_OK_333"
                          }
                        }
                        """;
                case "/fi/remit/out/domestic/v2/generateTransactionOtp" -> resp = """
                        {
                          "statuscode": "OTP",
                          "actcode": null,
                          "status": "OTP sent successfully",
                          "data": {
                            "isOtpRequired": "1",
                            "validity": "2099-12-31 23:59:59",
                            "referenceKey": "REF_TXN_OTP_SECRET_444"
                          }
                        }
                        """;
                case "/fi/remit/out/domestic/v2/transaction" -> {
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "DMT1"));
                    String amt = String.valueOf(reqJson.getOrDefault("transferAmount", "1000"));
                    String mode = transferOutcomeMode.get();
                    if ("WRONG_OTP".equals(mode)) {
                        resp = """
                                {
                                  "statuscode": "ERR",
                                  "actcode": null,
                                  "status": "Invalid OTP entered",
                                  "data": null
                                }
                                """;
                    } else if ("OTPGENREF".equals(mode)) {
                        resp = """
                                {
                                  "statuscode": "ERR",
                                  "actcode": "OTPGENREF",
                                  "status": "Expired reference key, generate new OTP",
                                  "data": null
                                }
                                """;
                    } else if ("TUP".equals(mode)) {
                        resp = """
                                {
                                  "statuscode": "TUP",
                                  "actcode": null,
                                  "status": "Transaction Under Process",
                                  "data": {
                                    "externalRef": "%s",
                                    "poolReferenceId": "IPAY_DMT_TUP_%s"
                                  },
                                  "orderid": "ORD_DMT_TUP_%s"
                                }
                                """.formatted(extRef, extRef, extRef);
                    } else {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "actcode": null,
                                  "status": "Transaction Successful",
                                  "data": {
                                    "externalRef": "%s",
                                    "poolReferenceId": "1260206122146999",
                                    "txnValue": "%s",
                                    "txnReferenceId": "UTR_LIVE_%s",
                                    "beneficiaryName": "Ramesh Sharma",
                                    "beneficiaryAccount": "xxxxxxxx0795",
                                    "beneficiaryIfsc": "SBIN0000001"
                                  },
                                  "orderid": "1260206122146999"
                                }
                                """.formatted(extRef, amt, extRef);
                    }
                }
                case "/reports/txnStatus" -> {
                    txnStatusCalls.incrementAndGet();
                    String extRef = String.valueOf(reqJson.getOrDefault("externalRef", "DMT1"));
                    String stMode = txnStatusMode.get();
                    if ("FAILED".equals(stMode)) {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "status": "Data Fetched Successfully",
                                  "data": {
                                    "transactionStatusCode": "ERR",
                                    "transactionStatus": "Transaction Failed at Beneficiary Bank",
                                    "transactionReferenceId": "",
                                    "order": { "referenceId": "ORD_FAIL_%s", "externalRef": "%s" }
                                  }
                                }
                                """.formatted(extRef, extRef);
                    } else if ("REFUND".equals(stMode)) {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "status": "Data Fetched Successfully",
                                  "data": {
                                    "transactionStatusCode": "RFD",
                                    "transactionStatus": "Transaction Reversed & Refunded by Bank",
                                    "transactionReferenceId": "UTR_RFD_%s",
                                    "order": { "referenceId": "ORD_RFD_%s", "externalRef": "%s" }
                                  }
                                }
                                """.formatted(extRef, extRef, extRef);
                    } else {
                        resp = """
                                {
                                  "statuscode": "TXN",
                                  "status": "Data Fetched Successfully",
                                  "data": {
                                    "transactionStatusCode": "TXN",
                                    "transactionStatus": "Transaction Successful",
                                    "transactionReferenceId": "UTR_RECONF_%s",
                                    "order": { "referenceId": "ORD_RECONF_%s", "externalRef": "%s" }
                                  }
                                }
                                """.formatted(extRef, extRef, extRef);
                    }
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
    @Autowired DmtCasaService dmtService;
    @Autowired DmtBankListService dmtBankListService;
    @Autowired DmtStatusReconciliationJob dmtReconJob;
    @Autowired TransactionStatusVerificationService verifyStatusService;
    @Autowired InstantPayAuditLogService auditLogs;
    @Autowired RetailerBankService retailerBankService;
    @Autowired WalletService walletService;
    @Autowired FieldCipher fieldCipher;

    private AuthPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        capturedRequests.clear();
        capturedRawBodies.clear();
        capturedHeaders.clear();
        remitterOnboarded.set(false);
        transferOutcomeMode.set("TXN");
        txnStatusMode.set("TXN");
        profileLookupCalls.set(0);
        txnStatusCalls.set(0);

        jdbc.update("UPDATE platform_services SET enabled = TRUE WHERE code IN ('DMT', 'AEPS', 'UPI_CASHOUT', 'PAYOUT')");
        jdbc.update("UPDATE service_routing_policies SET strategy = 'PRIMARY_FALLBACK', active = TRUE WHERE service_code = 'DMT'");
        jdbc.update("UPDATE commission_rules SET effective_to = now() - interval '1 second' WHERE txn_type = 'DMT' AND effective_to IS NULL");

        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        superAdmin = new AuthPrincipal(adminId, "Test Super Admin", "SUPER_ADMIN", null);
        setAuth(superAdmin);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE platform_services SET enabled = TRUE WHERE code IN ('DMT', 'AEPS', 'UPI_CASHOUT', 'PAYOUT')");
        jdbc.update("UPDATE commission_rules SET effective_to = now() - interval '1 second' WHERE txn_type = 'DMT' AND effective_to IS NULL");
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
        long n = 9200000000L + (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 799999999L);
        return String.valueOf(n);
    }

    private AuthPrincipal createOnboardedInstantPayRetailer(String merchantAadhaar12) {
        String mobile = randomMobile();
        UUID userId = UUID.randomUUID();
        String code = "RD" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO users (id, user_type, full_name, mobile, email, password_hash, status, code)
                VALUES (?, 'RETAILER', 'Rajesh Verma', ?, ?, ?, 'ACTIVE', ?)
                """,
                userId, mobile, "dmt." + mobile + "@gfinpay.test", passwordEncoder.encode("Secret@123"), code);

        walletService.createForUser(userId);
        jdbc.update("UPDATE wallets SET status = 'ACTIVE', available_balance = 15000.00 WHERE user_id = ? AND wallet_kind = 'MAIN'", userId);
        jdbc.update("UPDATE wallets SET status = 'ACTIVE' WHERE user_id = ? AND wallet_kind = 'REWARDS'", userId);

        // Registered shop coordinates: 28.619500, 77.280400 (Laxmi Nagar, Delhi)
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
                        'Verma Money Point', 28.619500, 77.280400,
                        '{"fullName":"Rajesh Verma","panName":"RAJESH VERMA","dob":"1990-05-15","gender":"MALE","permanentAddress":{"fullAddress":"Laxmi Nagar Delhi","city":"Delhi","pincode":"110092"}}'::jsonb,
                        '{"line1":"Laxmi Nagar","city":"Delhi","state":"Delhi","pincode":"110092"}'::jsonb)
                """,
                userId, merchantAadhaar12.substring(8), fieldCipher.encrypt(merchantAadhaar12), fieldCipher.encrypt("BJFPM6264D"));

        AuthPrincipal retailer = new AuthPrincipal(userId, "Rajesh Verma", "RETAILER", null);
        String suffix = mobile.substring(6);
        if ("0000".equals(suffix) || "9999".equals(suffix)) suffix = "4680";
        retailerBankService.verifyAndCreate(retailer, "50100888" + suffix, "IDFB0021006", "SAVINGS");

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
    void fullInstantPayDmtV2OneTimeKycSurchargeGeoFenceTransferAndStatusReconfirmationFlow() {
        String merchantAadhaar = validAadhaar("32165498701");
        String remitterAadhaar = validAadhaar("65498732109");
        String remitterMobile = randomMobile();

        AuthPrincipal retailer = createOnboardedInstantPayRetailer(merchantAadhaar);
        UUID userId = retailer.userId();

        // =====================================================================
        // 1. Strict Merchant FULL_KYC Gate: PENDING_BIOMETRIC must block DMT
        // =====================================================================
        jdbc.update("UPDATE provider_pipe_activations SET kyc_status = 'PENDING_BIOMETRIC' WHERE user_id = ?", userId);
        setAuth(retailer);
        Map<String, Object> pendingAct = biometricService.getCashActivation(retailer, false);
        assertEquals(Boolean.FALSE, pendingAct.get("dmtActive"));
        assertEquals(Boolean.TRUE, pendingAct.get("dmtBiometricRequired"));

        ApiException kycBlocked = assertThrows(ApiException.class,
                () -> dmtService.queryRemitter(retailer, remitterMobile));
        assertEquals("BIOMETRIC_KYC_REQUIRED", kycBlocked.getCode());

        // Restore merchant FULL_KYC
        jdbc.update("UPDATE provider_pipe_activations SET kyc_status = 'FULL_KYC' WHERE user_id = ?", userId);
        Map<String, Object> fullKycAct = biometricService.getCashActivation(retailer, false);
        assertEquals(Boolean.TRUE, fullKycAct.get("dmtActive"));
        assertEquals(Boolean.FALSE, fullKycAct.get("dmtBiometricRequired"));

        // =====================================================================
        // 2. DMT Bank Sync (raw "" string body) + Preserving Admin active=FALSE
        // =====================================================================
        setAuth(superAdmin);
        Map<String, Object> syncRes1 = dmtBankListService.syncFromAdmin(superAdmin.userId());
        assertEquals(2, syncRes1.get("synced"));
        assertEquals("\"\"", capturedRawBodies.get("/fi/remit/out/domestic/v2/banks"));
        assertEquals("782991", capturedHeaders.get("/fi/remit/out/domestic/v2/banks").get("x-ipay-outlet-id"));

        UUID hdfcRowId = jdbc.queryForObject("SELECT id FROM dmt_bank_list WHERE bank_id = '109012'", UUID.class);
        dmtBankListService.setActive(hdfcRowId, false);
        dmtBankListService.syncFromAdmin(superAdmin.userId());
        Boolean hdfcActiveAfterResync = jdbc.queryForObject(
                "SELECT active FROM dmt_bank_list WHERE bank_id = '109012'", Boolean.class);
        Boolean sbiActiveAfterResync = jdbc.queryForObject(
                "SELECT active FROM dmt_bank_list WHERE bank_id = '109005'", Boolean.class);
        assertFalse(hdfcActiveAfterResync, "Admin active=FALSE override must be preserved across InstantPay syncs");
        assertTrue(sbiActiveAfterResync);

        // =====================================================================
        // 3. Property Default Surcharge vs Admin Custom DMT Surcharge & Rewards
        // =====================================================================
        Map<String, Object> defaultComm = adminPlatformService.commission("DMT");
        assertEquals("PERCENT", defaultComm.get("surchargeMode"));
        assertEquals(0, new BigDecimal("1.00").compareTo((BigDecimal) defaultComm.get("surchargeValue")));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) defaultComm.get("surchargeMin")));
        assertEquals(Boolean.FALSE, defaultComm.get("surchargeCustomized"));

        Map<String, Object> savedComm = adminPlatformService.saveCommission(
                "DMT",
                "PERCENT",
                new BigDecimal("0.40"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "PERCENT",
                new BigDecimal("1.20"),
                new BigDecimal("12.00"));
        assertEquals("PERCENT", savedComm.get("surchargeMode"));
        assertEquals(0, new BigDecimal("1.20").compareTo((BigDecimal) savedComm.get("surchargeValue")));
        assertEquals(0, new BigDecimal("12.00").compareTo((BigDecimal) savedComm.get("surchargeMin")));
        assertEquals(Boolean.TRUE, savedComm.get("surchargeCustomized"));

        // =====================================================================
        // 4. One-Time Remitter Onboarding (RNF -> Register -> OTP -> eKYC -> TXN)
        // =====================================================================
        setAuth(retailer);
        Map<String, Object> rnfProfile = dmtService.queryRemitter(retailer, remitterMobile);
        assertEquals("REGISTRATION_REQUIRED", rnfProfile.get("status"));
        assertEquals(Boolean.FALSE, rnfProfile.get("exists"));

        Map<String, Object> regInit = dmtService.registerRemitter(retailer, remitterMobile, remitterAadhaar);
        assertEquals("OTP_REQUIRED", regInit.get("status"));
        UUID regSessionId = (UUID) regInit.get("sessionId");

        // Verify AES-256-CBC encryptedAadhaar sent to InstantPay
        Map<String, Object> regReqBody = capturedRequests.get("/fi/remit/out/domestic/v2/remitterRegistration");
        assertNotNull(regReqBody);
        String encAadhaarSent = String.valueOf(regReqBody.get("encryptedAadhaar"));
        assertEquals(remitterAadhaar, InstantPayCrypto.decrypt(encAadhaarSent, ENC_KEY));

        Map<String, Object> regVerify = dmtService.verifyRemitterRegistration(retailer, regSessionId, "123456");
        assertEquals("KYC_REQUIRED", regVerify.get("status"));
        assertNotNull(regVerify.get("wadh"));

        // Geo-fence violation (> 3.0 km from 28.6195, 77.2804) on remitterKyc
        ApiException geoKycEx = assertThrows(ApiException.class, () -> dmtService.submitRemitterEkyc(
                retailer, remitterMobile, regSessionId, SAMPLE_PID_XML, "FINGER", "28.700000", "77.400000", true));
        assertEquals("DMT_GEOFENCE_VIOLATION", geoKycEx.getCode());

        // Valid one-time Remitter Biometric eKYC within 3.0 km
        Map<String, Object> activeProfile = dmtService.submitRemitterEkyc(
                retailer, remitterMobile, regSessionId, SAMPLE_PID_XML, "FINGER", "28.619900", "77.280900", true);
        assertEquals("ACTIVE", activeProfile.get("status"));
        assertEquals(Boolean.TRUE, activeProfile.get("exists"));
        assertEquals(Boolean.TRUE, activeProfile.get("ekycVerified"));
        assertEquals("Suresh Sharma", activeProfile.get("fullName"));

        // Verify 12-field biometricData + consentTaken=Y + captureType=FINGER sent to /v2/remitterKyc
        assertEquals("E0jzJ/P8UopUHAieZn8CKqS4WPMi5ZSYXgfnlfkWjrc=", regVerify.get("wadh"));
        Map<String, Object> kycReqBody = capturedRequests.get("/fi/remit/out/domestic/v2/remitterKyc");
        assertEquals("Y", kycReqBody.get("consentTaken"));
        assertEquals("FINGER", kycReqBody.get("captureType"));
        Map<String, Object> bioMap = (Map<String, Object>) kycReqBody.get("biometricData");
        assertEquals(Set.of("ci", "hmac", "pidData", "ts", "dc", "mi", "dpId", "mc", "rdsId", "rdsVer", "Skey", "srno"),
                bioMap.keySet());
        assertEquals("SKEY_DMT_SECRET_BASE64", bioMap.get("Skey"));
        assertEquals("MFS110DMT77", bioMap.get("srno"));

        // Subsequent visit: goes straight to ACTIVE with zero repeat KYC
        Map<String, Object> repeatLookup = dmtService.queryRemitter(retailer, remitterMobile);
        assertEquals("ACTIVE", repeatLookup.get("status"));
        assertEquals(Boolean.TRUE, repeatLookup.get("ekycVerified"));
        UUID senderId = (UUID) repeatLookup.get("senderId");

        // =====================================================================
        // 5. Beneficiary OTP Registration & OTP Deletion
        // =====================================================================
        Map<String, Object> beneOtp1 = dmtService.sendBeneOtp(
                retailer, senderId, "Ramesh Sharma", "382910000795", "SBIN0000001", "109005", null);
        assertEquals(Boolean.TRUE, beneOtp1.get("otpRequired"));
        Map<String, Object> bene1 = dmtService.addBeneficiary(
                retailer, senderId, (UUID) beneOtp1.get("sessionId"), "654321");
        UUID bene1Id = (UUID) bene1.get("id");
        assertEquals("••••0795", bene1.get("accountMasked"));
        assertEquals(Boolean.TRUE, bene1.get("verified"));

        // Second beneficiary -> OTP delete
        Map<String, Object> beneOtp2 = dmtService.sendBeneOtp(
                retailer, senderId, "Temp Beneficiary", "382910009999", "SBIN0000001", "109005", null);
        Map<String, Object> bene2 = dmtService.addBeneficiary(
                retailer, senderId, (UUID) beneOtp2.get("sessionId"), "654321");
        UUID bene2Id = (UUID) bene2.get("id");
        Map<String, Object> delInit = dmtService.initiateDeleteBeneficiary(retailer, bene2Id);
        assertEquals(Boolean.TRUE, delInit.get("otpRequired"));
        Map<String, Object> delDone = dmtService.confirmDeleteBeneficiary(
                retailer, bene2Id, (UUID) delInit.get("sessionId"), "112233");
        assertEquals(Boolean.TRUE, delDone.get("deleted"));
        assertEquals(1, dmtService.beneficiaries(retailer, senderId).size());

        // =====================================================================
        // 6. Transfer Quote, Stale Profile Ref Auto-Refresh, Retryable OTP,
        //    SUCCESS Settlement, Rewards Commission, TUP Hold, 30m Throttle,
        //    Check/Reconfirm Status & Post-Settlement Bank Reversal
        // =====================================================================
        // Force profile_expires_at into the past to verify transparent profile refresh
        jdbc.update("UPDATE dmt_senders SET profile_expires_at = now() - interval '10 minutes' WHERE id = ?", senderId);
        int lookupsBeforeOtp = profileLookupCalls.get();

        // ₹2,000 transfer -> 1.20% surcharge = ₹24.00 (> ₹12 min) -> total ₹2,024.00
        Map<String, Object> quote2000 = dmtService.sendTxnOtp(
                retailer, senderId, bene1Id, new BigDecimal("2000.00"), "IMPS");
        assertTrue(profileLookupCalls.get() > lookupsBeforeOtp, "Expired profile_reference_key must auto-refresh");
        assertEquals(0, new BigDecimal("24.00").compareTo((BigDecimal) quote2000.get("fee")));
        assertEquals(0, new BigDecimal("2024.00").compareTo((BigDecimal) quote2000.get("total")));
        UUID txnSession1 = (UUID) quote2000.get("sessionId");

        // Geo-fence violation on transfer execution -> rejected before provider call
        ApiException geoTransferEx = assertThrows(ApiException.class, () -> dmtService.process(
                retailer, txnSession1, "888888", "28.750000", "77.450000", "idem-geo-fail-1", "canon-1"));
        assertEquals("DMT_GEOFENCE_VIOLATION", geoTransferEx.getCode());

        // Retryable wrong OTP -> refunds hold and keeps txn OTP session open for retry!
        transferOutcomeMode.set("WRONG_OTP");
        ApiException wrongOtpEx = assertThrows(ApiException.class, () -> dmtService.process(
                retailer, txnSession1, "000000", "28.619700", "77.280600", "idem-wrong-otp-1", "canon-2"));
        assertEquals("INVALID_OTP", wrongOtpEx.getCode());

        BigDecimal availAfterWrongOtp = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'MAIN'",
                BigDecimal.class, userId);
        assertEquals(0, new BigDecimal("15000.00").compareTo(availAfterWrongOtp));

        // Retry same OTP session with valid OTP & fresh Idempotency-Key -> SUCCESS!
        transferOutcomeMode.set("TXN");
        Map<String, Object> settled2000 = dmtService.process(
                retailer, txnSession1, "888888", "28.619700", "77.280600", "idem-settled-2000", "canon-3");
        assertEquals("SUCCESS", settled2000.get("state"));
        assertTrue(String.valueOf(settled2000.get("utr")).startsWith("UTR_LIVE_"));

        // MAIN wallet debited ₹2,024.00 -> ₹12,976.00; REWARDS wallet credited 0.40% of ₹2,000 = ₹8.00
        BigDecimal availAfter2000 = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'MAIN'",
                BigDecimal.class, userId);
        BigDecimal rewardsAfter2000 = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'REWARDS'",
                BigDecimal.class, userId);
        assertEquals(0, new BigDecimal("12976.00").compareTo(availAfter2000));
        assertEquals(0, new BigDecimal("8.00").compareTo(rewardsAfter2000));

        // OTPGENREF -> consumes session and throws DMT_OTP_REGENERATE_REQUIRED
        Map<String, Object> quoteOtpGenRef = dmtService.sendTxnOtp(
                retailer, senderId, bene1Id, new BigDecimal("500.00"), "IMPS");
        transferOutcomeMode.set("OTPGENREF");
        ApiException otpGenRefEx = assertThrows(ApiException.class, () -> dmtService.process(
                retailer, (UUID) quoteOtpGenRef.get("sessionId"), "888888", "28.619700", "77.280600", "idem-otpgenref-1", "canon-4"));
        assertEquals("DMT_OTP_REGENERATE_REQUIRED", otpGenRefEx.getCode());

        // TUP Pending Transfer (₹500 -> min surcharge ₹12.00 -> total ₹512.00)
        Map<String, Object> quote500 = dmtService.sendTxnOtp(
                retailer, senderId, bene1Id, new BigDecimal("500.00"), "NEFT");
        assertEquals(0, new BigDecimal("12.00").compareTo((BigDecimal) quote500.get("fee")));
        assertEquals(0, new BigDecimal("512.00").compareTo((BigDecimal) quote500.get("total")));

        transferOutcomeMode.set("TUP");
        Map<String, Object> pending500 = dmtService.process(
                retailer, (UUID) quote500.get("sessionId"), "888888", "28.619700", "77.280600", "idem-tup-500", "canon-5");
        UUID tupTxnId = (UUID) pending500.get("transactionId");
        assertEquals("PENDING", pending500.get("state"));
        assertEquals("PENDING", pending500.get("transferStatus"));

        BigDecimal holdDuringTup = jdbc.queryForObject(
                "SELECT hold_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'MAIN'",
                BigDecimal.class, userId);
        assertEquals(0, new BigDecimal("512.00").compareTo(holdDuringTup));

        // Immediate retailer refresh (< 30m) is throttled without hitting /reports/txnStatus
        txnStatusCalls.set(0);
        Map<String, Object> throttledRefresh = dmtService.refreshStatus(retailer, tupTxnId);
        assertEquals(Boolean.TRUE, throttledRefresh.get("statusCheckThrottled"));
        assertEquals(0, txnStatusCalls.get());

        // verifyForDesk also respects 30m cooling window (force = false)
        Map<String, Object> deskVerifyThrottled = verifyStatusService.verifyForDesk(retailer, tupTxnId);
        assertEquals(0, txnStatusCalls.get());
        assertTrue(String.valueOf(deskVerifyThrottled.get("message")).contains("30 minutes"));

        // Backdate transfer to 35 minutes ago -> retailer refresh resolves PENDING -> SUCCESS via /reports/txnStatus
        jdbc.update("UPDATE dmt_transfers SET created_at = now() - interval '35 minutes' WHERE transaction_id = ?", tupTxnId);
        txnStatusMode.set("TXN");
        Map<String, Object> resolvedSuccess = dmtService.refreshStatus(retailer, tupTxnId);
        assertEquals(1, txnStatusCalls.get());
        assertEquals("SUCCESS", resolvedSuccess.get("state"));
        assertTrue(String.valueOf(resolvedSuccess.get("utr")).startsWith("UTR_RECONF_"));

        BigDecimal holdAfterResolve = jdbc.queryForObject(
                "SELECT hold_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'MAIN'",
                BigDecimal.class, userId);
        BigDecimal rewardsAfter500 = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'REWARDS'",
                BigDecimal.class, userId);
        assertEquals(0, BigDecimal.ZERO.compareTo(holdAfterResolve));
        assertEquals(0, new BigDecimal("10.00").compareTo(rewardsAfter500)); // ₹8.00 + 0.40% of ₹500 (₹2.00)

        // Reconfirm Status on settled SUCCESS transfer (Admin force=true bypasses 4h repeat throttle)
        setAuth(superAdmin);
        Map<String, Object> adminReconfirm = verifyStatusService.verifyForAdmin(tupTxnId);
        assertEquals(2, txnStatusCalls.get());
        assertEquals("SUCCESS", adminReconfirm.get("currentState"));
        Timestamp reconfirmedTs = jdbc.queryForObject(
                "SELECT reconfirmed_at FROM dmt_transfers WHERE transaction_id = ?", Timestamp.class, tupTxnId);
        assertNotNull(reconfirmedTs, "reconfirmed_at must be stamped on SUCCESS reconfirmation");

        // Post-settlement NEFT bank return (SUCCESS -> REVERSED via /reports/txnStatus REFUND)
        txnStatusMode.set("REFUND");
        Map<String, Object> adminBankReturn = verifyStatusService.verifyForAdmin(tupTxnId);
        assertEquals("REVERSED", adminBankReturn.get("currentState"));

        BigDecimal availAfterReturn = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'MAIN'",
                BigDecimal.class, userId);
        BigDecimal rewardsAfterReturn = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'REWARDS'",
                BigDecimal.class, userId);
        assertEquals(0, new BigDecimal("12976.00").compareTo(availAfterReturn),
                "Post-settlement bank return must credit ₹512.00 back to MAIN wallet");
        assertEquals(0, new BigDecimal("8.00").compareTo(rewardsAfterReturn),
                "Post-settlement bank return must reverse the ₹2.00 REWARDS commission");

        // TUP -> FAILED via background reconciliation job
        setAuth(retailer);
        Map<String, Object> quoteFail = dmtService.sendTxnOtp(
                retailer, senderId, bene1Id, new BigDecimal("500.00"), "IMPS");
        transferOutcomeMode.set("TUP");
        Map<String, Object> pendingFail = dmtService.process(
                retailer, (UUID) quoteFail.get("sessionId"), "888888", "28.619700", "77.280600", "idem-tup-fail", "canon-6");
        UUID failTxnId = (UUID) pendingFail.get("transactionId");
        jdbc.update("UPDATE dmt_transfers SET created_at = now() - interval '40 minutes' WHERE transaction_id = ?", failTxnId);
        txnStatusMode.set("FAILED");
        dmtReconJob.runSweep();
        String stateAfterJob = jdbc.queryForObject(
                "SELECT state::text FROM transactions WHERE id = ?", String.class, failTxnId);
        assertEquals("FAILED", stateAfterJob);
        BigDecimal availAfterJobRefund = jdbc.queryForObject(
                "SELECT available_balance FROM wallets WHERE user_id = ? AND wallet_kind = 'MAIN'",
                BigDecimal.class, userId);
        assertEquals(0, new BigDecimal("12976.00").compareTo(availAfterJobRefund));

        // =====================================================================
        // 7. Audit Log Redaction Verification (zero Aadhaar / Skey / OTP / full account leaks)
        // =====================================================================
        auditLogs.awaitPending(Duration.ofSeconds(5));
        List<Map<String, Object>> logs = auditLogs.listForUser(userId, 50);
        assertFalse(logs.isEmpty(), "DMT calls must be recorded in instantpay_api_logs and linked to retailer");
        String allPayloads = logs.toString();
        assertFalse(allPayloads.contains(remitterAadhaar), "Plaintext Aadhaar must never appear in audit logs");
        assertFalse(allPayloads.contains(encAadhaarSent), "Encrypted Aadhaar ciphertext must be redacted in audit logs");
        assertFalse(allPayloads.contains("SKEY_DMT_SECRET_BASE64"), "Uppercase Skey must be redacted in audit logs");
        assertFalse(allPayloads.contains("PID_DMT_SECRET_BASE64"), "pidData must be redacted in audit logs");
        assertFalse(allPayloads.contains("HMAC_DMT_SECRET_BASE64"), "hmac must be redacted in audit logs");
        assertFalse(allPayloads.contains("REF_TXN_OTP_SECRET_444"), "referenceKey must be redacted in audit logs");
        assertFalse(allPayloads.contains("382910000795"), "Full beneficiary account number must be masked in audit logs");
    }
}
