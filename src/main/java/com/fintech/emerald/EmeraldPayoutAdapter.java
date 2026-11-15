package com.fintech.emerald;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.PayoutPort;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EmeraldPayoutAdapter implements PayoutPort {

    private final EmeraldClient client;

    public EmeraldPayoutAdapter(EmeraldClient client) {
        this.client = client;
    }

    @Override
    public String providerCode() {
        return "EMERALD";
    }

    @Override
    public ProviderModels.PayoutResult submit(ServiceProviderRouter.RoutedChannel channel, ProviderModels.PayoutRequest request) {
        EmeraldClient.PayoutResponse res = client.executePayout(
                channel.outletCode(),
                new EmeraldClient.PayoutPayRequest(
                        request.accountHolder(),
                        request.accountNumber(),
                        request.ifsc(),
                        request.mode(),
                        request.amount(),
                        request.externalRef(),
                        request.latitude(),
                        request.longitude(),
                        request.remarks()));
        return new ProviderModels.PayoutResult(
                outcome(res.outcome()),
                res.statusCode(),
                res.message(),
                res.txnId(),
                res.utr());
    }

    @Override
    public ProviderModels.PayoutStatus checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String externalRef,
            String providerTxnId) {
        EmeraldClient.GenericApiResponse res = client.checkPayoutStatus(channel.outletCode(), externalRef, providerTxnId);
        Map<String, Object> data = res.data() == null ? Map.of() : res.data();
        String raw = first(str(data.get("status")), res.statusCode());
        return new ProviderModels.PayoutStatus(
                outcome(raw),
                res.statusCode(),
                res.message(),
                first(str(data.get("utr")), str(data.get("rrn"))),
                first(str(data.get("txnId")), providerTxnId));
    }

    public static ProviderModels.Outcome outcome(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "SUCCESS", "2000" -> ProviderModels.Outcome.SUCCESS;
            case "FAILED", "REVERSED", "DECLINED" -> ProviderModels.Outcome.FAILED;
            default -> ProviderModels.Outcome.PENDING;
        };
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }
}
