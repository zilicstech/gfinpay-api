package com.fintech.instantpay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.emerald.EmeraldTraceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Direct HTTP client for InstantPay APIs ({@code https://api.instantpay.in}) covering:
 * Merchant Onboarding ({@code minKyc}, {@code addBankAccount}, {@code outlet/list}),
 * Biometric &amp; OTP Registration ({@code biometricKycStatus}, {@code register}, {@code registerationVerify}, {@code biometricKyc}),
 * UPI-to-Cashout ({@code fi/uatm/generateQr}, {@code fi/uatm/qrStatus}), and
 * Bank Account Settlement Payouts ({@code payments/payout}, {@code payments/payout/banks}, {@code reports/txnStatus}).
 */
@Component
public class InstantPayClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public record MinKycRequest(
            String mobile,
            String name,
            String gender,
            String pan,
            String email,
            String addressFull,
            String addressCity,
            String addressPincode,
            String plainAadhaar12,
            String dateOfBirth,
            String latitude,
            String longitude,
            String gstin
    ) {}

    public record MinKycResponse(
            boolean success,
            String statusCode,
            String actCode,
            String message,
            String outletId,
            String ipayUuid,
            String orderId,
            String environment,
            Map<String, Object> raw
    ) {}

    public record BankSyncResponse(
            boolean synced,
            String statusCode,
            String message,
            String ipayUuid,
            Map<String, Object> raw
    ) {}

    public record OutletSummary(
            String outletId,
            String name,
            String mobile,
            String email,
            String pan,
            boolean kycStatus,
            boolean active
    ) {}

    public record OutletListResponse(
            boolean success,
            String statusCode,
            String message,
            List<OutletSummary> records,
            Map<String, Object> raw
    ) {}

    public record BiometricStatusResponse(
            boolean success,
            String statusCode,
            String actCode,
            String message,
            String action,
            String bankStatus,
            String referenceKey,
            String referenceKeyType,
            String pidOptionWadh,
            String outletAadhaarMasked,
            String bankName,
            Map<String, Object> raw
    ) {}

    public record OtpRegistrationResponse(
            boolean otpSent,
            String statusCode,
            String message,
            String referenceKey,
            String referenceKeyType,
            String validity,
            Map<String, Object> raw
    ) {}

    public record GenericResponse(
            boolean success,
            String statusCode,
            String actCode,
            String message,
            String orderId,
            String ipayUuid,
            Map<String, Object> data,
            Map<String, Object> raw
    ) {}

    public record UatmQrResponse(
            boolean success,
            String statusCode,
            String message,
            String ipayId,
            String externalRef,
            String qrStatus,
            String qrString,
            int displayExpirySec,
            String expiryDt,
            String qrCreatedDt,
            String payableValue,
            Map<String, Object> raw
    ) {}

    public record UatmStatusResponse(
            boolean success,
            String statusCode,
            String message,
            String qrStatus,
            String qrStatusMsg,
            String ipayId,
            String operatorId,
            String payerVpa,
            String payerName,
            Map<String, Object> raw
    ) {}

    public record PayoutPayRequest(
            String payeeName,
            String payeeAccountNumber,
            String payeeBankIfsc,
            String transferMode,
            String transferAmount,
            String externalRef,
            String latitude,
            String longitude,
            String remarks
    ) {}

    public record PayoutResponse(
            String outcome,
            String statusCode,
            String actCode,
            String message,
            String txnId,
            String utr,
            Map<String, Object> raw
    ) {}

    public record TxnStatusResponse(
            String outcome,
            String statusCode,
            String transactionStatusCode,
            String message,
            String txnId,
            String utr,
            Map<String, Object> raw
    ) {}

    private final InstantPayProperties props;
    private final ObjectMapper mapper;
    private final InstantPayAuditLogService auditLog;
    private final HttpClient http;

    @Autowired
    public InstantPayClient(
            InstantPayProperties props,
            ObjectMapper mapper,
            InstantPayAuditLogService auditLog) {
        this(
                props,
                mapper,
                auditLog,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(12))
                        .build());
    }

    public InstantPayClient(
            InstantPayProperties props,
            ObjectMapper mapper,
            InstantPayAuditLogService auditLog,
            HttpClient http) {
        this.props = props;
        this.mapper = mapper;
        this.auditLog = auditLog;
        this.http = http;
    }

    public MinKycResponse signupMinKyc(MinKycRequest req) {
        String encAadhaar = InstantPayCrypto.encryptAadhaar(req.plainAadhaar12(), props.encryptionKey());
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("full", nonBlank(req.addressFull(), req.addressCity()));
        address.put("city", nonBlank(req.addressCity(), "Bengaluru"));
        address.put("pincode", nonBlank(req.addressPincode(), "560001"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobile", normalizeMobile10(req.mobile()));
        body.put("name", nonBlank(req.name(), "Merchant"));
        body.put("gender", normalizeGenderCode(req.gender()));
        body.put("pan", nonBlank(req.pan(), "").toUpperCase(Locale.ROOT));
        body.put("email", nonBlank(req.email(), ""));
        body.put("address", address);
        body.put("aadhaar", encAadhaar);
        body.put("dateOfBirth", nonBlank(req.dateOfBirth(), ""));
        body.put("latitude", scale4(req.latitude(), "19.0760"));
        body.put("longitude", scale4(req.longitude(), "72.8777"));
        body.put("gstin", req.gstin() == null ? "" : req.gstin().trim());

        RawCall res = execute("POST", "/user/outlet/signup/minKyc", null, body, Duration.ofSeconds(30), "ONBOARD_MIN_KYC", null);
        Map<String, Object> data = asMap(res.parsed.get("data"));
        String statusCode = str(res.parsed.get("statuscode"));
        String outletId = str(data.get("outletId"));
        if ("0".equals(outletId)) {
            outletId = "";
        }
        boolean ok = "TXN".equalsIgnoreCase(statusCode) && !outletId.isBlank();
        return new MinKycResponse(
                ok,
                statusCode,
                str(res.parsed.get("actcode")),
                str(res.parsed.get("status")),
                outletId,
                str(res.parsed.get("ipay_uuid")),
                str(res.parsed.get("orderid")),
                str(res.parsed.get("environment")),
                res.parsed);
    }

    public BankSyncResponse addBankAccount(
            String outletId,
            String bankAccountNo,
            String bankIfsc,
            String latitude,
            String longitude) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outletId", nonBlank(outletId, ""));
        body.put("bankAccountNo", nonBlank(bankAccountNo, ""));
        body.put("bankIfsc", nonBlank(bankIfsc, "").toUpperCase(Locale.ROOT));
        body.put("latitude", scale4(latitude, "19.0760"));
        body.put("longitude", scale4(longitude, "72.8777"));

        RawCall res = execute("POST", "/user/outlet/signup/addBankAccount", null, body, Duration.ofSeconds(30), "ONBOARD_ADD_BANK", null);
        String statusCode = str(res.parsed.get("statuscode"));
        String message = str(res.parsed.get("status"));
        boolean synced = "TXN".equalsIgnoreCase(statusCode);
        return new BankSyncResponse(synced, statusCode, message, str(res.parsed.get("ipay_uuid")), res.parsed);
    }

    public OutletListResponse listOutlets(int pageNumber, int recordsPerPage, Integer outletId, String mobile, String pan) {
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("pageNumber", Math.max(1, pageNumber));
        pagination.put("recordsPerPage", String.valueOf(Math.max(1, Math.min(100, recordsPerPage))));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("outletId", outletId != null ? outletId : 0);
        filters.put("mobile", mobile == null ? "" : normalizeMobile10(mobile));
        filters.put("pan", pan == null ? "" : pan.trim().toUpperCase(Locale.ROOT));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pagination", pagination);
        body.put("filters", filters);

        RawCall res = execute("POST", "/user/outlet/list", null, body, Duration.ofSeconds(25), "OUTLET_LIST", null);
        String statusCode = str(res.parsed.get("statuscode"));
        Map<String, Object> data = asMap(res.parsed.get("data"));
        List<OutletSummary> records = new ArrayList<>();
        Object rawRecords = data.get("records");
        if (rawRecords instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> rec = asMap(item);
                String oid = str(rec.get("outletId"));
                if (!oid.isBlank() && !"0".equals(oid)) {
                    records.add(new OutletSummary(
                            oid,
                            str(rec.get("name")),
                            normalizeMobile10(str(rec.get("mobile"))),
                            str(rec.get("email")),
                            str(rec.get("pan")).toUpperCase(Locale.ROOT),
                            truthy(rec.get("KYCStatus")),
                            truthy(rec.get("isActive"))));
                }
            }
        }
        return new OutletListResponse(
                "TXN".equalsIgnoreCase(statusCode),
                statusCode,
                str(res.parsed.get("status")),
                records,
                res.parsed);
    }

    public BiometricStatusResponse checkBiometricKycStatus(String outletId, String spkeyRaw, String gwRaw) {
        String spkey = nonBlank(spkeyRaw, props.defaultSpkey()).toUpperCase(Locale.ROOT);
        String gw = gwRaw == null ? props.defaultGw() : gwRaw.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spkey", spkey);
        if (!gw.isBlank()) {
            body.put("gw", gw);
        }
        RawCall res = execute("POST", "/user/outlet/signup/biometricKycStatus", outletId, body, Duration.ofSeconds(25), "EKYC_STATUS", null);
        String statusCode = str(res.parsed.get("statuscode"));
        String actCode = str(res.parsed.get("actcode"));
        Map<String, Object> data = asMap(res.parsed.get("data"));
        return new BiometricStatusResponse(
                "TXN".equalsIgnoreCase(statusCode),
                statusCode,
                actCode,
                str(res.parsed.get("status")),
                str(data.get("action")),
                str(data.get("status")),
                str(data.get("referenceKey")),
                str(data.get("referenceKeyType")),
                str(data.get("pidOptionWadh")),
                str(data.get("outletAadhaarNumber")),
                str(data.get("bankName")),
                res.parsed);
    }

    public OtpRegistrationResponse registerOutletOtp(String outletId, String referenceKey) {
        Map<String, Object> body = Map.of("referenceKey", nonBlank(referenceKey, ""));
        RawCall res = execute("POST", "/user/outlet/register", outletId, body, Duration.ofSeconds(25), "OUTLET_REGISTER_OTP", null);
        String statusCode = str(res.parsed.get("statuscode"));
        Map<String, Object> data = asMap(res.parsed.get("data"));
        boolean sent = "OTP".equalsIgnoreCase(statusCode) || "TXN".equalsIgnoreCase(statusCode);
        return new OtpRegistrationResponse(
                sent,
                statusCode,
                str(res.parsed.get("status")),
                str(data.get("referenceKey")),
                str(data.get("referenceKeyType")),
                str(data.get("validity")),
                res.parsed);
    }

    public GenericResponse verifyOutletRegistrationOtp(String outletId, String referenceKey, String otp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceKey", nonBlank(referenceKey, ""));
        body.put("otp", nonBlank(otp, ""));
        RawCall res = execute("POST", "/user/outlet/registerationVerify", outletId, body, Duration.ofSeconds(25), "OUTLET_REGISTER_VERIFY", null);
        String statusCode = str(res.parsed.get("statuscode"));
        return new GenericResponse(
                "TXN".equalsIgnoreCase(statusCode),
                statusCode,
                str(res.parsed.get("actcode")),
                str(res.parsed.get("status")),
                str(res.parsed.get("orderid")),
                str(res.parsed.get("ipay_uuid")),
                asMap(res.parsed.get("data")),
                res.parsed);
    }

    public GenericResponse submitBiometricKyc(
            String outletId,
            String referenceKey,
            String latitude,
            String longitude,
            String externalRef,
            Map<String, String> biometricData25) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceKey", nonBlank(referenceKey, ""));
        body.put("latitude", nonBlank(latitude, "19.0760"));
        body.put("longitude", nonBlank(longitude, "72.8777"));
        body.put("externalRef", nonBlank(externalRef, ""));
        body.put("biometricData", biometricData25);

        RawCall res = execute("POST", "/user/outlet/signup/biometricKyc", outletId, body, Duration.ofSeconds(45), "EKYC_BIOMETRIC_SUBMIT", externalRef);
        String statusCode = str(res.parsed.get("statuscode"));
        return new GenericResponse(
                "TXN".equalsIgnoreCase(statusCode),
                statusCode,
                str(res.parsed.get("actcode")),
                str(res.parsed.get("status")),
                str(res.parsed.get("orderid")),
                str(res.parsed.get("ipay_uuid")),
                asMap(res.parsed.get("data")),
                res.parsed);
    }

    public UatmQrResponse generateUatmQr(
            String outletId,
            String amount,
            String mobile,
            String latitude,
            String longitude,
            String externalRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", nonBlank(latitude, "19.0760"));
        body.put("longitude", nonBlank(longitude, "72.8777"));
        body.put("mobile", normalizeMobile10(mobile));
        body.put("amount", nonBlank(amount, "100"));
        body.put("externalRef", nonBlank(externalRef, ""));

        RawCall res = execute("POST", "/fi/uatm/generateQr", outletId, body, Duration.ofSeconds(30), "UATM_GENERATE_QR", externalRef);
        String statusCode = str(res.parsed.get("statuscode"));
        Map<String, Object> data = asMap(res.parsed.get("data"));
        String qrString = firstNonBlank(str(data.get("qrString")), str(data.get("qrMobile")));
        String ipayId = firstNonBlank(str(data.get("ipayId")), str(res.parsed.get("orderid")), str(data.get("txnId")));
        int displayExpirySec = parseIntOrDefault(str(data.get("displayExpirySec")), 30);
        boolean ok = "TXN".equalsIgnoreCase(statusCode) && !qrString.isBlank();
        return new UatmQrResponse(
                ok,
                statusCode,
                str(res.parsed.get("status")),
                ipayId,
                firstNonBlank(str(data.get("externalRef")), externalRef),
                str(data.get("qrStatus")),
                qrString,
                displayExpirySec,
                str(data.get("expiryDt")),
                str(data.get("qrCreatedDt")),
                str(data.get("payableValue")),
                res.parsed);
    }

    public UatmStatusResponse checkUatmQrStatus(String outletId, String ipayId) {
        Map<String, Object> body = Map.of("ipayId", nonBlank(ipayId, ""));
        RawCall res = execute("POST", "/fi/uatm/qrStatus", outletId, body, Duration.ofSeconds(25), "UATM_QR_STATUS", null);
        String statusCode = str(res.parsed.get("statuscode"));
        Map<String, Object> data = asMap(res.parsed.get("data"));
        Map<String, Object> payer = asMap(data.get("payer"));
        return new UatmStatusResponse(
                "TXN".equalsIgnoreCase(statusCode),
                statusCode,
                str(res.parsed.get("status")),
                str(data.get("qrStatus")),
                str(data.get("qrStatusMsg")),
                firstNonBlank(str(data.get("ipayId")), ipayId),
                firstNonBlank(str(data.get("operatorId")), str(data.get("utr"))),
                str(payer.get("vpa")),
                str(payer.get("name")),
                res.parsed);
    }

    public GenericResponse listPayoutBanks() {
        RawCall res = execute("GET", "/payments/payout/banks", null, null, Duration.ofSeconds(25), "PAYOUT_BANK_LIST", null);
        String statusCode = str(res.parsed.get("statuscode"));
        return new GenericResponse(
                "TXN".equalsIgnoreCase(statusCode),
                statusCode,
                str(res.parsed.get("actcode")),
                str(res.parsed.get("status")),
                str(res.parsed.get("orderid")),
                str(res.parsed.get("ipay_uuid")),
                asMap(res.parsed.get("data")),
                res.parsed);
    }

    public PayoutResponse executePayout(String outletId, PayoutPayRequest req) {
        Map<String, Object> payer = new LinkedHashMap<>();
        payer.put("bankProfileId", props.payerBankProfileId());
        payer.put("accountNumber", props.payerAccountNumber());

        Map<String, Object> payee = new LinkedHashMap<>();
        payee.put("name", nonBlank(req.payeeName(), "Merchant"));
        payee.put("accountNumber", nonBlank(req.payeeAccountNumber(), ""));
        payee.put("bankIfsc", nonBlank(req.payeeBankIfsc(), "").toUpperCase(Locale.ROOT));
        payee.put("payeeListId", "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payer", payer);
        body.put("payee", payee);
        body.put("transferMode", nonBlank(req.transferMode(), "IMPS").toUpperCase(Locale.ROOT));
        body.put("transferAmount", formatTransferAmount(req.transferAmount()));
        body.put("externalRef", sanitizeAlphanumericRef(req.externalRef()));
        body.put("latitude", scale4(req.latitude(), "19.0760"));
        body.put("longitude", scale4(req.longitude(), "72.8777"));
        body.put("remarks", sanitizePayoutRemarks(req.remarks()));
        body.put("purpose", props.payoutPurpose());
        body.put("otp", "");
        body.put("otpReference", "");

        String headerOutlet = (props.payoutIncludeOutletId() && outletId != null && outletId.trim().matches("[0-9]+"))
                ? outletId.trim()
                : null;

        RawCall res = execute("POST", "/payments/payout", headerOutlet, body, Duration.ofSeconds(55), "PAYOUT_EXECUTE", req.externalRef());
        if (res.networkError) {
            return new PayoutResponse("PENDING", "TIMEOUT", "", "Awaiting bank confirmation", "", "", res.parsed);
        }
        String statusCode = str(res.parsed.get("statuscode")).toUpperCase(Locale.ROOT);
        String actCode = str(res.parsed.get("actcode"));
        String message = str(res.parsed.get("status"));
        Map<String, Object> data = asMap(res.parsed.get("data"));
        String txnId = firstNonBlank(
                str(res.parsed.get("orderid")),
                str(data.get("poolReferenceId")),
                str(data.get("ipayId")));
        String utr = firstNonBlank(
                str(data.get("txnReferenceId")),
                str(data.get("operatorId")),
                txnId);

        String outcome;
        if ("TXN".equals(statusCode)) {
            outcome = "SUCCESS";
        } else if ("TUP".equals(statusCode) || statusCode.isEmpty()) {
            outcome = "PENDING";
        } else {
            outcome = "FAILED";
        }
        return new PayoutResponse(outcome, statusCode, actCode, message, txnId, utr, res.parsed);
    }

    public TxnStatusResponse checkTransactionStatus(String transactionDate, String externalRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionDate", nonBlank(transactionDate, ""));
        body.put("externalRef", nonBlank(externalRef, ""));

        RawCall res = execute("POST", "/reports/txnStatus", null, body, Duration.ofSeconds(30), "REPORTS_TXN_STATUS", externalRef);
        if (res.networkError) {
            return new TxnStatusResponse("PENDING", "TIMEOUT", "", "Status check timed out", "", "", res.parsed);
        }
        String statusCode = str(res.parsed.get("statuscode")).toUpperCase(Locale.ROOT);
        Map<String, Object> data = asMap(res.parsed.get("data"));
        String txnStatusCode = str(data.get("transactionStatusCode")).toUpperCase(Locale.ROOT);
        String txnStatusMsg = firstNonBlank(str(data.get("transactionStatus")), str(res.parsed.get("status")));
        Map<String, Object> order = asMap(data.get("order"));
        Map<String, Object> pool = asMap(data.get("pool"));
        Map<String, Object> poolTxn = asMap(pool.get("transaction"));

        String utr = firstNonBlank(str(data.get("transactionReferenceId")), str(order.get("referenceId")));
        String txnId = firstNonBlank(str(poolTxn.get("referenceId")), str(order.get("referenceId")));

        // Exact InstantPay Transaction Status table:
        // statuscode=TXN + transactionStatusCode=TXN -> SUCCESS
        // statuscode=TXN + transactionStatusCode=TUP -> PENDING
        // statuscode=TXN + other non-blank transactionStatusCode -> FAILED
        // statuscode != TXN -> PENDING (No Action Required)
        String outcome;
        if (!"TXN".equals(statusCode)) {
            outcome = "PENDING";
        } else if ("TXN".equals(txnStatusCode)) {
            outcome = "SUCCESS";
        } else if ("TUP".equals(txnStatusCode) || txnStatusCode.isEmpty()) {
            outcome = "PENDING";
        } else {
            outcome = "FAILED";
        }
        return new TxnStatusResponse(outcome, statusCode, txnStatusCode, txnStatusMsg, txnId, utr, res.parsed);
    }

    private record RawCall(int httpStatus, Map<String, Object> parsed, boolean networkError) {}

    private RawCall execute(
            String method,
            String path,
            String outletId,
            Object bodyObj,
            Duration timeout,
            String defaultFlow,
            String explicitExternalRef) {
        Instant startedAt = Instant.now();
        long t0 = System.currentTimeMillis();
        String callId = String.format("ipay_%x_%04x", startedAt.toEpochMilli(), ThreadLocalRandom.current().nextInt(0x10000));
        String requestId = MDC.get("requestId");

        EmeraldTraceContext.Snapshot trace = EmeraldTraceContext.current();
        UUID userId = trace != null ? trace.userId() : null;
        String flow = trace != null && trace.flow() != null && !trace.flow().isBlank()
                ? trace.flow() + ":" + defaultFlow
                : defaultFlow;
        String externalRef = firstNonBlank(explicitExternalRef, trace != null ? trace.externalRef() : null);

        String fullUrl = props.baseUrl() + path;
        String endpointIp = resolveEndpointIp();

        Map<String, String> reqHeaders = new LinkedHashMap<>();
        reqHeaders.put("Accept", "application/json");
        reqHeaders.put("X-Ipay-Auth-Code", props.authCode());
        reqHeaders.put("X-Ipay-Client-Id", props.clientId());
        reqHeaders.put("X-Ipay-Client-Secret", props.clientSecret());
        reqHeaders.put("X-Ipay-Endpoint-Ip", endpointIp);
        if (outletId != null && !outletId.isBlank()) {
            reqHeaders.put("X-Ipay-Outlet-Id", outletId.trim());
        }

        String rawReqBody = "";
        if (!"GET".equalsIgnoreCase(method) && bodyObj != null) {
            reqHeaders.put("Content-Type", "application/json");
            try {
                rawReqBody = mapper.writeValueAsString(bodyObj);
            } catch (Exception e) {
                rawReqBody = "{}";
            }
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(fullUrl)).timeout(timeout);
        for (Map.Entry<String, String> h : reqHeaders.entrySet()) {
            reqBuilder.header(h.getKey(), h.getValue());
        }
        if ("GET".equalsIgnoreCase(method)) {
            reqBuilder.GET();
        } else {
            reqBuilder.POST(HttpRequest.BodyPublishers.ofString(rawReqBody, StandardCharsets.UTF_8));
        }

        try {
            HttpResponse<String> response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latencyMs = System.currentTimeMillis() - t0;
            String rawResBody = response.body() == null ? "" : response.body();
            Map<String, Object> parsed = parseJsonMap(rawResBody);
            Map<String, String> resHeaders = flattenHeaders(response.headers().map());

            String statusCode = str(parsed.get("statuscode"));
            String message = str(parsed.get("status"));
            Map<String, Object> data = asMap(parsed.get("data"));
            String providerTxnId = firstNonBlank(
                    str(data.get("ipayId")),
                    str(parsed.get("orderid")),
                    str(data.get("poolReferenceId")),
                    str(parsed.get("ipay_uuid")));
            String resolvedOutlet = firstNonBlank(outletId, str(data.get("outletId")));
            boolean upstreamFailed = !"TXN".equalsIgnoreCase(statusCode) && !"OTP".equalsIgnoreCase(statusCode) && !"TUP".equalsIgnoreCase(statusCode);

            if (auditLog != null) {
                auditLog.recordOutboundAsync(new InstantPayAuditLogService.OutboundSnapshot(
                        callId,
                        requestId,
                        flow,
                        method,
                        fullUrl,
                        path,
                        userId,
                        resolvedOutlet,
                        externalRef,
                        providerTxnId,
                        reqHeaders,
                        rawReqBody,
                        response.statusCode(),
                        upstreamFailed,
                        statusCode,
                        message,
                        resHeaders,
                        rawResBody,
                        null,
                        latencyMs,
                        startedAt));
            }
            return new RawCall(response.statusCode(), parsed, false);
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - t0;
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (auditLog != null) {
                auditLog.recordOutboundAsync(new InstantPayAuditLogService.OutboundSnapshot(
                        callId,
                        requestId,
                        flow,
                        method,
                        fullUrl,
                        path,
                        userId,
                        outletId,
                        externalRef,
                        null,
                        reqHeaders,
                        rawReqBody,
                        null,
                        true,
                        "NETWORK_ERROR",
                        ex.getMessage(),
                        Map.of(),
                        null,
                        ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                        latencyMs,
                        startedAt));
            }
            return new RawCall(0, Map.of("statuscode", "TIMEOUT", "status", nonBlank(ex.getMessage(), "Upstream network error")), true);
        }
    }

    private String resolveEndpointIp() {
        if (!props.endpointIp().isEmpty()) {
            return props.endpointIp();
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String ip = xff.split(",")[0].trim();
                if (isPublicIpv4(ip)) return ip;
            }
            String real = req.getHeader("X-Real-IP");
            if (real != null && isPublicIpv4(real.trim())) {
                return real.trim();
            }
            String remote = req.getRemoteAddr();
            if (remote != null && isPublicIpv4(remote.trim())) {
                return remote.trim();
            }
        }
        return "103.80.162.232";
    }

    private static boolean isPublicIpv4(String ip) {
        return ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                && !ip.startsWith("127.")
                && !ip.startsWith("10.")
                && !ip.startsWith("192.168.");
    }

    public static String scale4(String raw, String fallback) {
        String candidate = nonBlank(raw, fallback);
        try {
            return new BigDecimal(candidate).setScale(4, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return new BigDecimal(fallback).setScale(4, RoundingMode.HALF_UP).toPlainString();
        }
    }

    public static String sanitizePayoutRemarks(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Fund Transfer";
        }
        String lettersOnly = raw.replaceAll("[^A-Za-z ]", " ").replaceAll("\\s+", " ").trim();
        if (lettersOnly.isEmpty()) {
            return "Fund Transfer";
        }
        return lettersOnly.length() <= 20 ? lettersOnly : lettersOnly.substring(0, 20).trim();
    }

    public static String sanitizeAlphanumericRef(String raw) {
        if (raw == null || raw.isBlank()) {
            return "GFP" + System.currentTimeMillis();
        }
        String clean = raw.replaceAll("[^A-Za-z0-9]", "");
        return clean.isEmpty() ? "GFP" + System.currentTimeMillis() : clean;
    }

    private static String formatTransferAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return "0";
        }
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            BigDecimal stripped = bd.stripTrailingZeros();
            return stripped.scale() <= 0 ? stripped.setScale(0, RoundingMode.HALF_UP).toPlainString() : bd.setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return raw.trim();
        }
    }

    public static String normalizeGenderCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "M";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("F")) return "F";
        if (u.startsWith("T") || u.startsWith("O")) return "T";
        return "M";
    }

    public static String normalizeMobile10(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("raw", raw);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static Map<String, String> flattenHeaders(Map<String, List<String>> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                out.put(e.getKey(), String.join(", ", e.getValue()));
            }
        }
        return out;
    }

    private static boolean truthy(Object val) {
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        String s = str(val);
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String nonBlank(String val, String fallback) {
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
