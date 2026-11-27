package com.fintech.provider;

import com.fintech.provider.port.AepsPort;
import com.fintech.provider.port.DmtPort;
import com.fintech.provider.port.PayoutPort;
import com.fintech.provider.port.UpiCashoutPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Selects a channel and the provider port that implements it. */
@Component
public class ProviderDispatch {

    private final ServiceProviderRouter router;
    private final ProviderRegistry registry;

    public ProviderDispatch(ServiceProviderRouter router, ProviderRegistry registry) {
        this.router = router;
        this.registry = registry;
    }

    public record RoutedUpi(ServiceProviderRouter.RoutedChannel channel, UpiCashoutPort port) {}

    public record RoutedPayout(ServiceProviderRouter.RoutedChannel channel, PayoutPort port) {}

    public record RoutedAeps(ServiceProviderRouter.RoutedChannel channel, AepsPort port) {}

    public record RoutedDmt(ServiceProviderRouter.RoutedChannel channel, DmtPort port) {}

    public RoutedUpi upiCashout(UUID userId) {
        ServiceProviderRouter.RoutedChannel channel = router.selectChannel(userId, ServiceProviderRouter.UPI_CASHOUT);
        return new RoutedUpi(channel, registry.upi(channel.providerCode()));
    }

    public RoutedPayout payout(UUID userId) {
        ServiceProviderRouter.RoutedChannel channel = router.selectChannel(userId, ServiceProviderRouter.PAYOUT);
        return new RoutedPayout(channel, registry.payout(channel.providerCode()));
    }

    public RoutedAeps aeps(UUID userId) {
        ServiceProviderRouter.RoutedChannel channel = router.selectChannel(userId, ServiceProviderRouter.AEPS);
        return new RoutedAeps(channel, registry.aeps(channel.providerCode()));
    }

    public RoutedDmt dmt(UUID userId) {
        ServiceProviderRouter.RoutedChannel channel = router.selectChannel(userId, ServiceProviderRouter.DMT);
        return new RoutedDmt(channel, registry.dmt(channel.providerCode()));
    }
}
