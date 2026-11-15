package com.fintech.provider.port;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;

public interface PayoutPort {
    String providerCode();

    ProviderModels.PayoutResult submit(ServiceProviderRouter.RoutedChannel channel, ProviderModels.PayoutRequest request);

    ProviderModels.PayoutStatus checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String externalRef,
            String providerTxnId);
}
