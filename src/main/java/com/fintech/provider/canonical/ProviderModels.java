package com.fintech.provider.canonical;

import com.fintech.biometric.RdPidXmlParser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Provider-neutral results. Adapters translate vendor payloads into these types. */
public final class ProviderModels {

    private ProviderModels() {}

    public enum QrKind { UPI_STRING, PNG_BASE64 }

    public enum Outcome { SUCCESS, PENDING, FAILED }

    public enum DmtTransferOutcome {
        SUCCESS,
        PENDING,
        FAILED,
        OTP_REGENERATE_REQUIRED,
        INVALID_OTP_RETRYABLE
    }

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

    public record DmtBankItem(
            String bankId,
            String name,
            String ifscAlias,
            String ifscGlobal,
            boolean impsEnabled,
            boolean neftEnabled,
            Integer impsFailureRate,
            Integer neftFailureRate
    ) {}

    public record DmtBeneficiaryItem(
            String id,
            String name,
            String account,
            String ifsc,
            String bankName,
            String beneficiaryMobile,
            String verificationDt
    ) {}

    public record DmtRemitterProfile(
            String status,
            String statusCode,
            String actCode,
            String message,
            String mobileNumber,
            String firstName,
            String lastName,
            String city,
            String pincode,
            BigDecimal perTxnLimit,
            BigDecimal dailyLimitMax,
            BigDecimal dailyLimitAvailable,
            BigDecimal monthlyLimitMax,
            BigDecimal monthlyLimitAvailable,
            boolean txnOtpRequired,
            boolean txnBioRequired,
            boolean impsAllowed,
            boolean neftAllowed,
            boolean faceAuthAvailable,
            String validity,
            String referenceKey,
            String pidOptionWadh,
            List<DmtBeneficiaryItem> beneficiaries
    ) {}

    public record DmtOtpChallenge(
            boolean success,
            boolean otpRequired,
            String status,
            String statusCode,
            String actCode,
            String message,
            String referenceKey,
            String beneficiaryId,
            String validity
    ) {}

    public record DmtRemitterEkycRequest(
            String mobileNumber,
            String referenceKey,
            String latitude,
            String longitude,
            String externalRef,
            String captureType,
            RdPidXmlParser.ParsedPidData pid
    ) {}

    public record DmtRemitterEkycResult(
            boolean success,
            String statusCode,
            String actCode,
            String message,
            String orderId,
            String poolReferenceId,
            BigDecimal poolAmount
    ) {}

    public record DmtBeneRegisterRequest(
            String remitterMobile,
            String beneficiaryMobile,
            String accountNumber,
            String ifsc,
            String bankId,
            String name
    ) {}

    public record DmtTransferRequest(
            String remitterMobile,
            String accountNumber,
            String ifsc,
            String transferMode,
            BigDecimal amount,
            String latitude,
            String longitude,
            String referenceKey,
            String otp,
            String externalRef
    ) {}

    public record DmtTransferResult(
            DmtTransferOutcome outcome,
            String statusCode,
            String actCode,
            String message,
            String providerTxnId,
            String utr,
            BigDecimal poolAmount
    ) {}

    public record DmtTransferStatus(
            Outcome outcome,
            boolean throttled,
            String statusCode,
            String message,
            String utr,
            String txnId
    ) {}
}
