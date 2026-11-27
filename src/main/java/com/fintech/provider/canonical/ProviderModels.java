package com.fintech.provider.canonical;

import com.fintech.biometric.RdPidXmlParser;
import java.math.BigDecimal;
import java.util.Map;

/** Provider-neutral results. Adapters translate vendor payloads into these types. */
public final class ProviderModels {

    private ProviderModels() {}

    public enum QrKind { UPI_STRING, PNG_BASE64 }

    public enum Outcome { SUCCESS, PENDING, FAILED }

    public record QrRequest(
            int amountRupees,
            String mobile,
            String customerName,
            String latitude,
            String longitude,
            String externalRef
    ) {}

    public record QrResult(
            boolean success,
            String message,
            String txnId,
            String qrText,
            String qrPngBase64,
            int displayExpirySec,
            String payableValue
    ) {
        public QrKind kind() {
            return qrPngBase64 != null && !qrPngBase64.isBlank() ? QrKind.PNG_BASE64 : QrKind.UPI_STRING;
        }

        public String storedQr() {
            return kind() == QrKind.PNG_BASE64 ? qrPngBase64 : qrText;
        }
    }

    public record StatusResult(
            String qrStatus,
            String statusCode,
            String message,
            String txnId,
            String utr,
            String payerVpa,
            String payerName
    ) {}

    public record PayoutRequest(
            String accountHolder,
            String accountNumber,
            String ifsc,
            String mode,
            String amount,
            String externalRef,
            String latitude,
            String longitude,
            String remarks,
            String customerName,
            String customerMobile,
            String pincode,
            String bankName,
            String clientIp
    ) {}

    public record PayoutResult(
            Outcome outcome,
            String statusCode,
            String message,
            String txnId,
            String utr
    ) {}

    public record PayoutStatus(
            Outcome outcome,
            String statusCode,
            String message,
            String utr,
            String txnId
    ) {}

    public record AepsResult(
            boolean success,
            Outcome outcome,
            String statusCode,
            String message,
            Map<String, Object> data
    ) {}

    public record AepsBank(
            String bankId,
            String iin,
            String name,
            boolean aepsEnabled,
            BigDecimal cwOtpMin,
            Integer failureRatePct
    ) {}

    public record AepsLoginStatus(
            boolean loggedIn,
            boolean loginRequired,
            boolean dormantOutlet,
            String aadhaarLast4,
            boolean txnBioLoginRequired,
            boolean faceAuthAvailable,
            boolean irisAuthAvailable,
            String statusCode,
            String actCode,
            String message,
            String providerTxnId
    ) {}

    public record AepsOutletLoginRequest(
            String loginType,
            String captureType,
            String latitude,
            String longitude,
            String externalRef,
            String merchantAadhaar12,
            RdPidXmlParser.ParsedPidData pid
    ) {}

    public record AepsOtpRequest(
            String bankIin,
            String customerAadhaar12,
            String customerMobile,
            int amountRupees,
            String latitude,
            String longitude,
            String externalRef
    ) {}

    public record AepsOtpChallenge(
            boolean success,
            boolean otpRequired,
            boolean loginRequired,
            boolean dormantOutlet,
            String referenceKey,
            String validity,
            String statusCode,
            String message
    ) {}

    public record AepsTxnRequest(
            String aepsType,
            String bankIin,
            String customerMobile,
            BigDecimal amount,
            String externalRef,
            String referenceKey,
            String captureType,
            String latitude,
            String longitude,
            String customerAadhaar12,
            RdPidXmlParser.ParsedPidData pid
    ) {}

    public record AepsStatus(
            Outcome outcome,
            String statusCode,
            String message,
            String rrn,
            String txnId
    ) {}

    public record SettlementEvent(
            String externalRef,
            String providerTxnId,
            String status,
            String message,
            String utr,
            String payerVpa,
            String payerName
    ) {}
}
