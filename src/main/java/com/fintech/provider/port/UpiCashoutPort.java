package com.fintech.provider.port;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;

public interface UpiCashoutPort {
    String providerCode();

    ProviderModels.QrResult generateQr(ServiceProviderRouter.RoutedChannel channel, ProviderModels.QrRequest request);

    ProviderModels.StatusResult checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String providerTxnId,
            String externalRef,
            String latitude,
            String longitude);
}
