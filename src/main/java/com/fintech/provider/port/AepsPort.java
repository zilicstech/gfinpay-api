package com.fintech.provider.port;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import java.util.Map;

public interface AepsPort {
    String providerCode();

    ProviderModels.AepsResult transact(ServiceProviderRouter.RoutedChannel channel, Map<String, Object> payload);
}
