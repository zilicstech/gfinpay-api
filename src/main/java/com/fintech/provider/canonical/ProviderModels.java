package com.fintech.provider.canonical;

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
            String remarks
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
