package com.fintech.emerald;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.AepsPort;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EmeraldAepsAdapter implements AepsPort {

    private final EmeraldClient client;

    public EmeraldAepsAdapter(EmeraldClient client) {
        this.client = client;
    }

    @Override
    public String providerCode() {
        return "EMERALD";
    }

    @Override
    public ProviderModels.AepsResult transact(ServiceProviderRouter.RoutedChannel channel, Map<String, Object> payload) {
        EmeraldClient.GenericApiResponse res = client.executeAeps(channel.outletCode(), payload);
        Map<String, Object> data = res.data() == null ? Map.of() : res.data();
        String status = String.valueOf(data.getOrDefault("status", "")).trim().toUpperCase(Locale.ROOT);
        ProviderModels.Outcome outcome;
        if (res.success()) {
            outcome = ProviderModels.Outcome.SUCCESS;
        } else if ("2001".equals(res.statusCode()) || "PENDING".equals(status)) {
            outcome = ProviderModels.Outcome.PENDING;
        } else {
            outcome = ProviderModels.Outcome.FAILED;
        }
        return new ProviderModels.AepsResult(res.success(), outcome, res.statusCode(), res.message(), data);
    }
}
