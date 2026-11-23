package com.fintech.instantpay;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.UpiCashoutPort;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Direct InstantPay UPI-to-Cashout ({@code UATM}) adapter implementing {@link UpiCashoutPort}.
 * Uses {@code POST /fi/uatm/generateQr} and real-time {@code POST /fi/uatm/qrStatus}.
 */
@Component
public class InstantPayUpiCashoutAdapter implements UpiCashoutPort {

    /**
     * InstantPay UATM specification: 30s NPCI QR display + 60s customer UPI PIN window = 90s total session duration.
     */
    private static final int TOTAL_TXN_WINDOW_SECONDS = 90;

    private final InstantPayClient client;

    public InstantPayUpiCashoutAdapter(InstantPayClient client) {
        this.client = client;
    }

    @Override
    public String providerCode() {
        return "INSTANTPAY";
    }

    @Override
    public ProviderModels.QrResult generateQr(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.QrRequest request) {
        InstantPayClient.UatmQrResponse res = client.generateUatmQr(
                channel.outletCode(),
                String.valueOf(request.amountRupees()),
                request.mobile(),
                request.latitude(),
                request.longitude(),
                request.externalRef());
        int sessionExpirySec = Math.max(TOTAL_TXN_WINDOW_SECONDS, res.displayExpirySec());
        return new ProviderModels.QrResult(
                res.success(),
                res.message(),
                res.ipayId(),
                res.qrString(),
                null,
                sessionExpirySec,
                res.payableValue());
    }

    @Override
    public ProviderModels.StatusResult checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String providerTxnId,
            String externalRef,
            String latitude,
            String longitude,
            String txnDate) {
        if (providerTxnId == null || providerTxnId.isBlank()) {
            return new ProviderModels.StatusResult(
                    "INITIATED",
                    "PENDING",
                    "Awaiting InstantPay order ID",
                    "",
                    "",
                    "",
                    "");
        }
        InstantPayClient.UatmStatusResponse res = client.checkUatmQrStatus(channel.outletCode(), providerTxnId);
        return new ProviderModels.StatusResult(
                normalizeQrStatus(res.qrStatus()),
                res.statusCode(),
                firstNonBlank(res.qrStatusMsg(), res.message()),
                firstNonBlank(res.ipayId(), providerTxnId),
                res.operatorId(),
                res.payerVpa(),
                res.payerName());
    }

    public static String normalizeQrStatus(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "SUCCESS", "PAID", "COMPLETED" -> "SUCCESS";
            case "FAILED", "DECLINED", "REJECTED" -> "FAILED";
            case "EXPIRED" -> "EXPIRED";
            case "INITIATED", "PENDING", "" -> "INITIATED";
            default -> s;
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }
}
