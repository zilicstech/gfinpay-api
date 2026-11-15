package com.fintech.provider.port;

import com.fintech.provider.canonical.ProviderModels;
import java.util.Map;

public interface ProviderCallbackPort {
    String providerCode();

    /** Returns empty when the body is not a settlement event this provider owns. */
    ProviderModels.SettlementEvent verify(Map<String, String> headers, String rawBody);
}
