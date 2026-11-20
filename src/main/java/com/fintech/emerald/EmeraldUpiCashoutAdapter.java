package com.fintech.emerald;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.UpiCashoutPort;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class EmeraldUpiCashoutAdapter implements UpiCashoutPort {

    private final EmeraldClient client;

    public EmeraldUpiCashoutAdapter(EmeraldClient client) {
        this.client = client;
    }

    @Override
    public String providerCode() {
        return "EMERALD";
    }

    @Override
    public ProviderModels.QrResult generateQr(ServiceProviderRouter.RoutedChannel channel, ProviderModels.QrRequest request) {
        EmeraldClient.UatmQrResponse res = client.generateUatmQr(
                channel.outletCode(),
                String.valueOf(request.amountRupees()),
                request.mobile(),
                request.latitude(),
                request.longitude(),
                request.externalRef());
        return new ProviderModels.QrResult(
                res.success(),
                res.message(),
                res.txnId(),
                res.qrString(),
                null,
                res.displayExpirySec(),
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
        EmeraldClient.UatmStatusResponse res = client.checkUatmQrStatus(
                channel.outletCode(), providerTxnId, externalRef, latitude, longitude);
        return new ProviderModels.StatusResult(
                normalize(res.qrStatus()),
                res.statusCode(),
                first(res.qrStatusMsg(), res.message()),
                res.txnId(),
                res.utr(),
                res.payerVpa(),
                res.payerName());
    }

    public static String normalize(String qrStatus) {
        String status = qrStatus == null ? "" : qrStatus.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS", "PAID", "COMPLETED" -> "SUCCESS";
            case "FAILED", "DECLINED", "REJECTED" -> "FAILED";
            case "EXPIRED" -> "EXPIRED";
            default -> status;
        };
    }

    private static String first(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }
}
