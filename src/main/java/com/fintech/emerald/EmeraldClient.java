package com.fintech.emerald;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.io.IOException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * HTTP client for the Emerald B2B Partner API Suite ({@code v1.0.0 Enterprise}).
 *
 * <p>All sensitive fields (Aadhaar, PID XML/Base64, bank account numbers, client secret)
 * are strictly excluded from application logs.
 */
@Component
public class EmeraldClient {

    private static final Logger log = LoggerFactory.getLogger(EmeraldClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public record OutletPipeSummary(
            String pipeCode,
            String pipeOutletCode,
            String onboardingStatus,
            String kycStatus,
            boolean active
    ) {}

    public record OutletBankSummary(
            String accountHolderName,
            String bankAccountNo,
            String bankIfsc,
            boolean primary,
            boolean active,
            String status,
            String pipeSyncStatus,
            String pipeSyncRemarks
    ) {}

    public record OutletResponse(
            boolean success,
            int httpStatus,
            String statusCode,
            String message,
            String outletCode,
            List<OutletPipeSummary> pipes,
            List<OutletBankSummary> bankAccounts,
            Map<String, Object> raw
    ) {}

    public record BankSyncResponse(
            boolean synced,
            int httpStatus,
            String statusCode,
            String message,
            String pipeSyncStatus,
            String pipeSyncRemarks,
            Map<String, Object> raw
    ) {}

    public record BiometricInitResponse(
            boolean success,
            int httpStatus,
            String statusCode,
            String message,
            String referenceKey,
            String pidWadh,
            String referenceKeyType,
            Map<String, Object> raw
    ) {}

    public record BiometricSubmitResponse(
            boolean success,
            int httpStatus,
            String statusCode,
            String message,
            String kycStatus,
            String bankSyncStatus,
            Map<String, Object> raw
    ) {}

    public record UatmQrResponse(
            boolean success,
            int httpStatus,
            String statusCode,
            String message,
            String txnId,
            String externalRef,
            String qrStatus,
            String qrString,
            String qrMobile,
            String expiryDt,
            int displayExpirySec,
            String payableValue,
            String transactionValue,
            Map<String, Object> raw
    ) {}

    public record UatmStatusResponse(
            boolean success,
            int httpStatus,
            String statusCode,
            String message,
            String txnId,
            String qrStatus,
            String qrStatusMsg,
            String amount,
            String payerVpa,
            String payerName,
            String payerMobile,
            String bankName,
            String maskedAccountNumber,
            String utr,
            Map<String, Object> raw
    ) {}

    public record PayoutResponse(
            boolean success,
            int httpStatus,
            String statusCode,
            String message,
            String outcome, // SUCCESS, PENDING, FAILED
            String txnId,
            String vendorOrderId,
            String utr,
            String txnValue,
            Map<String, Object> raw
    ) {}

    public record GenericApiResponse(
            boolean success,
            int httpStatus,
            String statusCode,
            String message,
            Map<String, Object> data,
            Map<String, Object> raw
    ) {}

    public record CreateOutletRequest(
            String mobile,
            String firstName,
            String lastName,
            String gender,
            String pan,
            String email,
            String shopName,
            String merchantLegalName,
            String aadhaar,
            String dateOfBirth,
            String latitude,
            String longitude,
            String pipeCode,
            String gstin,
            String addressLine,
            String area,
            String city,
            String district,
            String state,
            String pincode
    ) {}

    public record AddBankAccountRequest(
            String accountHolderName,
            String accountNo,
            String ifscCode,
            String latitude,
            String longitude,
            String pipeCode
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

    private final EmeraldProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final EmeraldAuditLogService auditLog;

    @Autowired
    public EmeraldClient(EmeraldProperties props, ObjectMapper mapper, EmeraldAuditLogService auditLog) {
        this(props, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build(), auditLog);
    }

    public EmeraldClient(EmeraldProperties props, ObjectMapper mapper) {
        this(props, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build(), null);
    }

    public EmeraldClient(EmeraldProperties props, ObjectMapper mapper, HttpClient http) {
        this(props, mapper, http, null);
    }

    public EmeraldClient(EmeraldProperties props, ObjectMapper mapper, HttpClient http, EmeraldAuditLogService auditLog) {
        this.props = props;
        this.mapper = mapper;
        this.http = http;
        this.auditLog = auditLog;
    }

    public EmeraldProperties properties() {
        return props;
    }

    public OutletResponse createOutlet(CreateOutletRequest req) {
        ensureConfigured();
        String pipe = (req.pipeCode() == null || req.pipeCode().isBlank()) ? props.defaultPipe() : req.pipeCode().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("line", nonBlankOrDefault(req.addressLine(), "Shop Premises"));
        address.put("city", nonBlankOrDefault(req.city(), "Mumbai"));
        address.put("state", nonBlankOrDefault(req.state(), "Maharashtra"));
        address.put("pincode", nonBlankOrDefault(req.pincode(), "400001"));
        if (req.district() != null && !req.district().isBlank()) {
            address.put("district", req.district().trim());
        }
        if (req.area() != null && !req.area().isBlank()) {
            address.put("area", req.area().trim());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobile", req.mobile());
        body.put("firstName", req.firstName());
        if (req.lastName() != null && !req.lastName().isBlank()) {
            body.put("lastName", req.lastName().trim());
        }
        body.put("gender", nonBlankOrDefault(req.gender(), "MALE"));
        body.put("pan", req.pan());
        body.put("email", req.email());
        body.put("shopName", req.shopName());
        if (req.merchantLegalName() != null && !req.merchantLegalName().isBlank()) {
            body.put("merchantLegalName", req.merchantLegalName().trim());
        }
        body.put("aadhaar", req.aadhaar());
        if (req.dateOfBirth() != null && !req.dateOfBirth().isBlank()) {
            body.put("dateOfBirth", req.dateOfBirth().trim());
        }
        body.put("latitude", req.latitude());
        body.put("longitude", req.longitude());
        body.put("pipeCode", pipe);
        if (req.gstin() != null && !req.gstin().isBlank()) {
            body.put("gstin", req.gstin().trim());
        }
        body.put("address", address);

        RawHttpResult res = sendJson("POST", "/core/v1/outlets/create", null, body, Duration.ofSeconds(30));
        return parseOutletResponse(res);
    }

    public OutletResponse getOutletDetails(String outletCode) {
        ensureConfigured();
        RawHttpResult res = sendJson("GET", "/core/v1/outlets/details", outletCode, null, Duration.ofSeconds(20));
        return parseOutletResponse(res);
    }

    @SuppressWarnings("unchecked")
    public List<OutletResponse> listAllOutlets() {
        ensureConfigured();
        RawHttpResult res = sendJson("GET", "/core/v1/outlets/all", null, null, Duration.ofSeconds(25));
        Object dataObj = res.json().get("data");
        if (!(dataObj instanceof List<?> list)) {
            return List.of();
        }
        List<OutletResponse> out = new ArrayList<>();
        String code = extractStatusCode(res.json());
        String msg = extractMessage(res.json());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> itemMap = (Map<String, Object>) m;
                out.add(buildOutletResponse(true, res.httpStatus(), code, msg, itemMap, Map.of("data", itemMap)));
            }
        }
        return out;
    }

    public BankSyncResponse addBankAccount(String outletCode, AddBankAccountRequest req) {
        ensureConfigured();
        String pipe = (req.pipeCode() == null || req.pipeCode().isBlank()) ? props.defaultPipe() : req.pipeCode().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountHolderName", req.accountHolderName());
        body.put("accountNo", req.accountNo());
        body.put("ifscCode", req.ifscCode());
        body.put("latitude", req.latitude());
        body.put("longitude", req.longitude());
        body.put("pipeCode", pipe);

        RawHttpResult res = sendJson("POST", "/core/v1/outlets/bank-account", outletCode, body, Duration.ofSeconds(30));
        String statusCode = extractStatusCode(res.json());
        String message = extractMessage(res.json());
        Map<String, Object> data = asMap(res.json().get("data"));
        String pipeSyncStatus = str(data.get("pipeSyncStatus"));
        String pipeSyncRemarks = str(data.get("pipeSyncRemarks"));
        boolean okCode = "2000".equals(statusCode) || "EMD00000".equalsIgnoreCase(statusCode);
        boolean synced = res.httpStatus() >= 200 && res.httpStatus() < 300
                && okCode
                && !"FAILED".equalsIgnoreCase(pipeSyncStatus);
        if (pipeSyncStatus.isBlank()) {
            pipeSyncStatus = synced ? "SYNCED" : "FAILED";
        }
        return new BankSyncResponse(
                synced,
                res.httpStatus(),
                statusCode,
                message,
                pipeSyncStatus,
                pipeSyncRemarks.isBlank() ? message : pipeSyncRemarks,
                res.json());
    }

    public BiometricInitResponse initiateBiometricKyc(String outletCode, String pipeCode) {
        ensureConfigured();
        String pipe = (pipeCode == null || pipeCode.isBlank()) ? props.defaultPipe() : pipeCode.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> body = Map.of("pipeCode", pipe);
        RawHttpResult res = sendJson("POST", "/core/v1/outlets/kyc-request", outletCode, body, Duration.ofSeconds(25));
        String statusCode = extractStatusCode(res.json());
        String message = extractMessage(res.json());
        Map<String, Object> data = asMap(res.json().get("data"));
        String referenceKey = str(data.get("referenceKey"));
        String pidWadh = firstNonBlank(str(data.get("pidWadh")), str(data.get("wadh")));
        String referenceKeyType = str(data.get("referenceKeyType"));
        boolean success = Boolean.TRUE.equals(res.json().get("success"))
                || "EMD00000".equalsIgnoreCase(statusCode)
                || ("2000".equals(statusCode) && !referenceKey.isBlank());
        return new BiometricInitResponse(
                success && !referenceKey.isBlank(),
                res.httpStatus(),
                statusCode,
                message,
                referenceKey,
                pidWadh,
                referenceKeyType,
                res.json());
    }

    public BiometricSubmitResponse submitBiometricKyc(
            String outletCode,
            String pipeCode,
            String referenceKey,
            String latitude,
            String longitude,
            String externalRef,
            Map<String, String> biometricData26) {
        ensureConfigured();
        String pipe = (pipeCode == null || pipeCode.isBlank()) ? props.defaultPipe() : pipeCode.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pipeCode", pipe);
        body.put("referenceKey", referenceKey);
        body.put("latitude", latitude);
        body.put("longitude", longitude);
        body.put("externalRef", externalRef);
        body.put("captureType", "finger");
        body.put("biometricData", biometricData26);

        RawHttpResult res = sendJson("POST", "/core/v1/outlets/biometric-kyc", outletCode, body, Duration.ofSeconds(35));
        String statusCode = extractStatusCode(res.json());
        String message = extractMessage(res.json());
        Map<String, Object> data = asMap(res.json().get("data"));
        String kycStatus = str(data.get("kycStatus"));
        String bankSyncStatus = str(data.get("bankSyncStatus"));
        boolean success = (Boolean.TRUE.equals(res.json().get("success"))
                || "EMD00000".equalsIgnoreCase(statusCode)
                || "2000".equals(statusCode))
                && ("FULL_KYC".equalsIgnoreCase(kycStatus) || "APPROVED".equalsIgnoreCase(kycStatus) || kycStatus.isBlank());
        return new BiometricSubmitResponse(
                success,
                res.httpStatus(),
                statusCode,
                message,
                kycStatus.isBlank() && success ? "FULL_KYC" : kycStatus,
                bankSyncStatus,
                redactBiometricRaw(res.json()));
    }

    public UatmQrResponse generateUatmQr(
            String outletCode,
            String amountWholeRupees,
            String customerMobile,
            String latitude,
            String longitude,
            String externalRef) {
        ensureConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountWholeRupees);
        body.put("mobile", customerMobile);
        body.put("latitude", latitude);
        body.put("longitude", longitude);
        body.put("externalRef", externalRef);

        RawHttpResult res = sendJson("POST", "/upicash/v1/uatm/generate-qr", outletCode, body, Duration.ofSeconds(25));
        String status = firstNonBlank(str(res.json().get("status")), extractStatusCode(res.json()));
        String message = extractMessage(res.json());
        Map<String, Object> data = asMap(res.json().get("data"));
        String qrString = firstNonBlank(str(data.get("qrString")), str(data.get("qrMobile")));
        boolean ok = Boolean.TRUE.equals(res.json().get("success"))
                && "UPITOCASH00000".equalsIgnoreCase(status)
                && !qrString.isBlank();
        int expirySec = parsePositiveInt(str(data.get("displayExpirySec")), 90);
        return new UatmQrResponse(
                ok,
                res.httpStatus(),
                status,
                message,
                str(data.get("txnId")),
                firstNonBlank(str(data.get("externalRef")), externalRef),
                str(data.get("qrStatus")),
                qrString,
                str(data.get("qrMobile")),
                str(data.get("expiryDt")),
                expirySec,
                firstNonBlank(str(data.get("payableValue")), amountWholeRupees),
                firstNonBlank(str(data.get("transactionValue")), amountWholeRupees),
                res.json());
    }

    public UatmStatusResponse checkUatmQrStatus(
            String outletCode,
            String txnId,
            String externalRef,
            String latitude,
            String longitude) {
        ensureConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", nonBlankOrDefault(latitude, "19.0760"));
        body.put("longitude", nonBlankOrDefault(longitude, "72.8777"));
        if (txnId != null && !txnId.isBlank()) {
            body.put("txnId", txnId.trim());
        }
        if (externalRef != null && !externalRef.isBlank()) {
            body.put("referenceNo", externalRef.trim());
        }

        RawHttpResult res = sendJson("POST", "/upicash/v1/uatm/qr-status", outletCode, body, Duration.ofSeconds(20));
        String status = firstNonBlank(str(res.json().get("status")), extractStatusCode(res.json()));
        String message = extractMessage(res.json());
        Map<String, Object> data = asMap(res.json().get("data"));
        Map<String, Object> payer = asMap(data.get("payer"));
        String utr = firstNonBlank(
                str(data.get("utr")),
                str(data.get("rrn")),
                str(data.get("bankRrn")),
                str(data.get("npciTxnId")),
                str(data.get("txnId")));
        boolean ok = Boolean.TRUE.equals(res.json().get("success")) || "UPITOCASH00000".equalsIgnoreCase(status);
        return new UatmStatusResponse(
                ok,
                res.httpStatus(),
                status,
                message,
                firstNonBlank(str(data.get("txnId")), txnId),
                str(data.get("qrStatus")).toUpperCase(Locale.ROOT),
                str(data.get("qrStatusMsg")),
                str(data.get("amount")),
                str(payer.get("vpa")),
                str(payer.get("name")),
                str(payer.get("mobile")),
                str(payer.get("bankName")),
                str(payer.get("accountNumber")),
                utr,
                res.json());
    }

    public PayoutResponse executePayout(String outletCode, PayoutPayRequest req) {
        ensureConfigured();
        Map<String, Object> payee = new LinkedHashMap<>();
        payee.put("name", req.payeeName());
        payee.put("accountNumber", req.payeeAccountNumber());
        payee.put("bankIfsc", req.payeeBankIfsc());

        // Strict Emerald Payout v1 body schema: NO payer, alertEmail, purpose, or outletCode in JSON body.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payee", payee);
        body.put("transferMode", req.transferMode());
        body.put("transferAmount", req.transferAmount());
        body.put("externalRef", req.externalRef());
        body.put("latitude", req.latitude());
        body.put("longitude", req.longitude());
        body.put("remarks", nonBlankOrDefault(req.remarks(), "Settlement payout"));

        RawHttpResult res;
        try {
            res = sendJson("POST", "/payout/v1/pay", outletCode, body, Duration.ofSeconds(35));
        } catch (ApiException netEx) {
            log.warn("EMERALD payout network timeout/ambiguity outlet={} ref={}: {}",
                    outletCode, req.externalRef(), netEx.getMessage());
            return new PayoutResponse(
                    false,
                    0,
                    "TIMEOUT",
                    "Transfer submitted; awaiting bank confirmation",
                    "PENDING",
                    "",
                    "",
                    "",
                    req.transferAmount(),
                    Map.of("networkUncertain", true, "message", netEx.getMessage()));
        }

        String statusCode = extractStatusCode(res.json());
        String message = extractMessage(res.json());
        Map<String, Object> data = asMap(res.json().get("data"));
        String dataMsg = str(data.get("message"));
        if (!dataMsg.isBlank() && (message.isBlank() || "Payout request submitted successfully".equalsIgnoreCase(message))) {
            message = dataMsg;
        }
        String txnId = str(data.get("txnId"));
        String vendorOrderId = str(data.get("transactionId"));
        String utr = firstNonBlank(str(data.get("utr")), str(data.get("rrn")), vendorOrderId, txnId);
        String txnValue = firstNonBlank(str(data.get("txnValue")), req.transferAmount());

        String outcome;
        if ("2000".equals(statusCode) && Boolean.TRUE.equals(res.json().get("success"))) {
            outcome = "SUCCESS";
        } else if ("2001".equals(statusCode)) {
            outcome = "PENDING";
        } else if ("4001".equals(statusCode) || "4002".equals(statusCode)
                || "4003".equals(statusCode) || "4004".equals(statusCode)
                || "4100".equals(statusCode) || "2015".equals(statusCode)) {
            outcome = "FAILED";
        } else if (res.httpStatus() >= 500 || "5000".equals(statusCode) || "5001".equals(statusCode)) {
            // Treat 5001 ("Vendor API invocation failed" before debit) vs ambiguous 500 carefully:
            // per Emerald Payout doc Section 9, 5001 = "Vendor API could not be called" -> safe FAILED;
            // 5000 / 504 = ambiguous -> PENDING until webhook or manual verify.
            outcome = "5001".equals(statusCode) ? "FAILED" : "PENDING";
        } else {
            outcome = Boolean.FALSE.equals(res.json().get("success")) ? "FAILED" : "PENDING";
        }

        return new PayoutResponse(
                "SUCCESS".equals(outcome),
                res.httpStatus(),
                statusCode,
                message,
                outcome,
                txnId,
                vendorOrderId,
                utr,
                txnValue,
                redactPayeeRaw(res.json()));
    }

    public GenericApiResponse checkPayoutStatus(String outletCode, String externalRef, String txnId) {
        if (props.payoutStatusPath().isBlank()) {
            return new GenericApiResponse(false, 0, "UNCONFIGURED", "Payout status inquiry path not configured", Map.of(), Map.of());
        }
        ensureConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        if (externalRef != null && !externalRef.isBlank()) {
            body.put("externalRef", externalRef.trim());
        }
        if (txnId != null && !txnId.isBlank()) {
            body.put("txnId", txnId.trim());
        }
        RawHttpResult res = sendJson("POST", props.payoutStatusPath(), outletCode, body, Duration.ofSeconds(20));
        return new GenericApiResponse(
                Boolean.TRUE.equals(res.json().get("success")),
                res.httpStatus(),
                extractStatusCode(res.json()),
                extractMessage(res.json()),
                asMap(res.json().get("data")),
                res.json());
    }

    public GenericApiResponse executeAeps(String outletCode, Map<String, Object> payload) {
        if (props.aepsTransactPath().isBlank()) {
            return new GenericApiResponse(false, 0, "AEPS_PATH_UNCONFIGURED",
                    "AEPS banking endpoint path is not yet configured", Map.of(), Map.of());
        }
        ensureConfigured();
        RawHttpResult res = sendJson("POST", props.aepsTransactPath(), outletCode, payload, Duration.ofSeconds(35));
        return new GenericApiResponse(
                Boolean.TRUE.equals(res.json().get("success")),
                res.httpStatus(),
                extractStatusCode(res.json()),
                extractMessage(res.json()),
                asMap(res.json().get("data")),
                redactBiometricRaw(res.json()));
    }

    private OutletResponse parseOutletResponse(RawHttpResult res) {
        String statusCode = extractStatusCode(res.json());
        String message = extractMessage(res.json());
        Map<String, Object> data = asMap(res.json().get("data"));
        boolean ok = (res.httpStatus() >= 200 && res.httpStatus() < 300)
                && ("2000".equals(statusCode)
                || "EMD00000".equalsIgnoreCase(statusCode)
                || Boolean.TRUE.equals(res.json().get("success")))
                && !str(data.get("outletCode")).isBlank();
        return buildOutletResponse(ok, res.httpStatus(), statusCode, message, data, res.json());
    }

    @SuppressWarnings("unchecked")
    private OutletResponse buildOutletResponse(
            boolean ok,
            int httpStatus,
            String statusCode,
            String message,
            Map<String, Object> data,
            Map<String, Object> raw) {
        String outletCode = str(data.get("outletCode"));
        List<OutletPipeSummary> pipes = new ArrayList<>();
        Object pipesObj = data.get("pipes");
        if (pipesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> pm = (Map<String, Object>) m;
                    boolean active = Boolean.TRUE.equals(pm.get("is_active"))
                            || Boolean.TRUE.equals(pm.get("active"))
                            || "ACTIVE".equalsIgnoreCase(str(pm.get("onboardingStatus")));
                    pipes.add(new OutletPipeSummary(
                            str(pm.get("pipeCode")).toUpperCase(Locale.ROOT),
                            str(pm.get("pipeOutletCode")),
                            str(pm.get("onboardingStatus")),
                            str(pm.get("kycStatus")),
                            active));
                }
            }
        }
        List<OutletBankSummary> banks = new ArrayList<>();
        Object banksObj = data.get("bankAccounts");
        if (banksObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> bm = (Map<String, Object>) m;
                    banks.add(new OutletBankSummary(
                            str(bm.get("accountHolderName")),
                            str(bm.get("bankAccountNo")),
                            str(bm.get("bankIfsc")),
                            Boolean.TRUE.equals(bm.get("isPrimary")),
                            Boolean.TRUE.equals(bm.get("isActive")),
                            str(bm.get("status")),
                            str(bm.get("pipeSyncStatus")),
                            str(bm.get("pipeSyncRemarks"))));
                }
            }
        }
        return new OutletResponse(ok, httpStatus, statusCode, message, outletCode, pipes, banks, raw);
    }

    private record RawHttpResult(int httpStatus, Map<String, Object> json) {}

    private RawHttpResult sendJson(
            String method,
            String path,
            String outletCode,
            Object body,
            Duration timeout) {
        String httpMethod = method.toUpperCase(Locale.ROOT);
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String fullUrl = props.baseUrl() + normalizedPath;
        URI uri = URI.create(fullUrl);
        Instant occurredAt = Instant.now();
        String timestampMs = String.valueOf(occurredAt.toEpochMilli());
        String callId = String.format("emd_%x_%04x", occurredAt.toEpochMilli(), ThreadLocalRandom.current().nextInt(0x10000));
        String requestId = MDC.get("requestId");

        EmeraldTraceContext.Snapshot ctx = EmeraldTraceContext.current();
        UUID userId = ctx != null ? ctx.userId() : null;
        String flow = resolveFlowName(ctx != null ? ctx.flow() : null, httpMethod, normalizedPath);
        String externalRef = resolveExternalRef(ctx != null ? ctx.externalRef() : null, body);

        Map<String, String> rawReqHeaders = new LinkedHashMap<>();
        rawReqHeaders.put("Accept", "application/json");
        rawReqHeaders.put("X-Client-Id", props.clientId());
        rawReqHeaders.put("X-Client-Secret", props.clientSecret());
        rawReqHeaders.put("X-Entity-Code", props.entityCode());
        rawReqHeaders.put("X-Timestamp", timestampMs);
        if (outletCode != null && !outletCode.isBlank()) {
            rawReqHeaders.put("X-Outlet-Code", outletCode.trim());
        }
        if (!"GET".equalsIgnoreCase(httpMethod)) {
            rawReqHeaders.put("Content-Type", "application/json");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("X-Client-Id", props.clientId())
                .header("X-Client-Secret", props.clientSecret())
                .header("X-Entity-Code", props.entityCode())
                .header("X-Timestamp", timestampMs);

        if (outletCode != null && !outletCode.isBlank()) {
            builder.header("X-Outlet-Code", outletCode.trim());
        }

        String rawReqBody = "";
        long started = System.currentTimeMillis();
        try {
            if ("GET".equalsIgnoreCase(httpMethod)) {
                builder.GET();
            } else {
                rawReqBody = body == null ? "{}" : mapper.writeValueAsString(body);
                builder.header("Content-Type", "application/json")
                        .method(httpMethod, HttpRequest.BodyPublishers.ofString(rawReqBody, StandardCharsets.UTF_8));
            }

            started = System.currentTimeMillis();
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - started;

            int status = response.statusCode();
            String rawResBody = response.body();
            Map<String, String> rawResHeaders = flattenResponseHeaders(response);

            Map<String, Object> parsed = parseJsonBody(rawResBody, status);
            String providerCode = extractStatusCode(parsed);
            String providerMsg = extractMessage(parsed);
            Map<String, Object> dataMap = asMap(parsed.get("data"));
            String resolvedOutlet = firstNonBlank(outletCode, str(dataMap.get("outletCode")));
            String resolvedExtRef = firstNonBlank(externalRef, str(dataMap.get("externalRef")));
            String providerTxnId = firstNonBlank(
                    str(dataMap.get("txnId")),
                    str(dataMap.get("transactionId")),
                    str(dataMap.get("referenceKey")));
            boolean upstreamSuccessFalse = Boolean.FALSE.equals(parsed.get("success"));

            dispatchOutboundAuditAsync(new EmeraldAuditLogService.OutboundTraceSnapshot(
                    callId,
                    requestId,
                    flow,
                    httpMethod,
                    fullUrl,
                    normalizedPath,
                    userId,
                    resolvedOutlet,
                    resolvedExtRef,
                    providerTxnId,
                    rawReqHeaders,
                    rawReqBody,
                    status,
                    upstreamSuccessFalse,
                    providerCode,
                    providerMsg,
                    rawResHeaders,
                    rawResBody,
                    null,
                    elapsed,
                    occurredAt));

            return new RawHttpResult(status, parsed);
        } catch (InterruptedException e) {
            long elapsed = System.currentTimeMillis() - started;
            Thread.currentThread().interrupt();
            String errDesc = e.getClass().getSimpleName() + ": " + nonBlankOrDefault(e.getMessage(), "interrupted");
            dispatchOutboundAuditAsync(new EmeraldAuditLogService.OutboundTraceSnapshot(
                    callId,
                    requestId,
                    flow,
                    httpMethod,
                    fullUrl,
                    normalizedPath,
                    userId,
                    outletCode,
                    externalRef,
                    null,
                    rawReqHeaders,
                    rawReqBody,
                    null,
                    true,
                    "INTERRUPTED",
                    null,
                    Map.of(),
                    null,
                    errDesc,
                    elapsed,
                    occurredAt));
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "GATEWAY_INTERRUPTED",
                    "Banking network request was interrupted. Please try again.");
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - started;
            String errDesc = e.getClass().getSimpleName() + ": " + nonBlankOrDefault(e.getMessage(), "io_error");
            dispatchOutboundAuditAsync(new EmeraldAuditLogService.OutboundTraceSnapshot(
                    callId,
                    requestId,
                    flow,
                    httpMethod,
                    fullUrl,
                    normalizedPath,
                    userId,
                    outletCode,
                    externalRef,
                    null,
                    rawReqHeaders,
                    rawReqBody,
                    null,
                    true,
                    "NETWORK_ERROR",
                    null,
                    Map.of(),
                    null,
                    errDesc,
                    elapsed,
                    occurredAt));
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE",
                    "Banking network is temporarily unreachable. Please try again.");
        }
    }

    private void dispatchOutboundAuditAsync(EmeraldAuditLogService.OutboundTraceSnapshot snap) {
        if (auditLog != null) {
            auditLog.recordOutboundAsync(snap);
        } else {
            // Standalone unit-test fallback when Spring audit service is not wired
            log.info("EMERALD {} {} callId={} outlet={} httpStatus={} code={} latencyMs={}",
                    snap.httpMethod(), snap.endpointPath(), snap.callId(),
                    nonBlankOrDefault(snap.outletCode(), "-"), snap.httpStatus(), snap.providerCode(), snap.latencyMs());
        }
    }

    private static Map<String, String> flattenResponseHeaders(HttpResponse<?> response) {
        Map<String, String> flat = new LinkedHashMap<>();
        response.headers().map().forEach((k, values) -> {
            if (values != null && !values.isEmpty()) {
                flat.put(k, String.join(", ", values));
            }
        });
        return flat;
    }

    private String resolveFlowName(String contextFlow, String method, String path) {
        String endpointTag;
        if ("/core/v1/outlets/create".equalsIgnoreCase(path)) {
            endpointTag = "OUTLET_CREATE";
        } else if ("/core/v1/outlets/details".equalsIgnoreCase(path)) {
            endpointTag = "OUTLET_DETAILS";
        } else if ("/core/v1/outlets/all".equalsIgnoreCase(path)) {
            endpointTag = "OUTLET_LIST_ALL";
        } else if ("/core/v1/outlets/bank-account".equalsIgnoreCase(path)) {
            endpointTag = "OUTLET_ADD_BANK";
        } else if ("/core/v1/outlets/kyc-request".equalsIgnoreCase(path)) {
            endpointTag = "BIOMETRIC_INIT";
        } else if ("/core/v1/outlets/biometric-kyc".equalsIgnoreCase(path)) {
            endpointTag = "BIOMETRIC_SUBMIT";
        } else if ("/upicash/v1/uatm/generate-qr".equalsIgnoreCase(path)) {
            endpointTag = "UATM_GENERATE_QR";
        } else if ("/upicash/v1/uatm/qr-status".equalsIgnoreCase(path)) {
            endpointTag = "UATM_QR_STATUS";
        } else if ("/payout/v1/pay".equalsIgnoreCase(path)) {
            endpointTag = "PAYOUT_PAY";
        } else if (!props.payoutStatusPath().isBlank() && props.payoutStatusPath().equalsIgnoreCase(path)) {
            endpointTag = "PAYOUT_STATUS";
        } else if (!props.aepsTransactPath().isBlank() && props.aepsTransactPath().equalsIgnoreCase(path)) {
            endpointTag = "AEPS_TRANSACT";
        } else {
            endpointTag = method + "_" + path.replaceAll("^/+", "").replace('/', '_').toUpperCase(Locale.ROOT);
        }
        if (contextFlow != null && !contextFlow.isBlank() && !contextFlow.equalsIgnoreCase(endpointTag)) {
            return contextFlow + ":" + endpointTag;
        }
        return endpointTag;
    }

    @SuppressWarnings("unchecked")
    private static String resolveExternalRef(String contextRef, Object body) {
        if (contextRef != null && !contextRef.isBlank()) {
            return contextRef.trim();
        }
        if (body instanceof Map<?, ?> m) {
            return firstNonBlank(
                    str(m.get("externalRef")),
                    str(m.get("referenceNo")),
                    str(m.get("referenceKey")));
        }
        return "";
    }

    private Map<String, Object> parseJsonBody(String rawBody, int httpStatus) {
        if (rawBody == null || rawBody.isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("success", false);
            empty.put("statusCode", String.valueOf(httpStatus));
            empty.put("message", httpStatus == 401
                    ? "Gateway authentication or timestamp validation failed"
                    : "Empty response from banking gateway (HTTP " + httpStatus + ")");
            return empty;
        }
        try {
            return mapper.readValue(rawBody, MAP_TYPE);
        } catch (Exception ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("success", false);
            fallback.put("statusCode", String.valueOf(httpStatus));
            fallback.put("message", "Unexpected response format from banking gateway");
            return fallback;
        }
    }

    private void ensureConfigured() {
        if (!props.isConfigured()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_NOT_CONFIGURED",
                    "Provider credentials are not configured on this environment.");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    private static String extractStatusCode(Map<String, Object> json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        return firstNonBlank(
                str(json.get("statusCode")),
                str(json.get("statuscode")),
                str(json.get("status")),
                str(json.get("code")));
    }

    @SuppressWarnings("unchecked")
    private static String extractMessage(Map<String, Object> json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        String msg = str(json.get("message"));
        Object errObj = json.get("error");
        if (errObj instanceof Map<?, ?> errMap) {
            String errDesc = str(errMap.get("errDesc"));
            if (!errDesc.isBlank()) {
                return errDesc;
            }
            if ("Validation failed".equalsIgnoreCase(msg) || msg.isBlank()) {
                List<String> details = new ArrayList<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) errMap).entrySet()) {
                    if (entry.getValue() != null) {
                        details.add(String.valueOf(entry.getValue()));
                    }
                }
                if (!details.isEmpty()) {
                    return String.join("; ", details);
                }
            }
        }
        return msg;
    }

    private static Map<String, Object> redactBiometricRaw(Map<String, Object> raw) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        copy.remove("biometricData");
        return copy;
    }

    private static Map<String, Object> redactPayeeRaw(Map<String, Object> raw) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        Map<String, Object> data = asMap(copy.get("data"));
        if (!data.isEmpty() && data.containsKey("payee")) {
            Map<String, Object> payee = asMap(data.get("payee"));
            String acct = str(payee.get("accountNumber"));
            if (acct.length() > 4) {
                payee.put("accountNumber", "XXXXXX" + acct.substring(acct.length() - 4));
            }
            data.put("payee", payee);
            copy.put("data", data);
        }
        return copy;
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int val = Integer.parseInt(raw.trim());
            return val > 0 ? val : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String nonBlankOrDefault(String val, String fallback) {
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
