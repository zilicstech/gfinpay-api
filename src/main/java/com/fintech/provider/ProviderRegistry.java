package com.fintech.provider;

import com.fintech.platform.web.ApiException;
import com.fintech.provider.port.AepsPort;
import com.fintech.provider.port.DmtPort;
import com.fintech.provider.port.PayoutPort;
import com.fintech.provider.port.UpiCashoutPort;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Looks up a provider port by the channel the router selected. */
@Component
public class ProviderRegistry {

    private final Map<String, UpiCashoutPort> upi;
    private final Map<String, PayoutPort> payout;
    private final Map<String, AepsPort> aeps;
    private final Map<String, DmtPort> dmt;

    public ProviderRegistry(List<UpiCashoutPort> upi, List<PayoutPort> payout, List<AepsPort> aeps) {
        this(upi, payout, aeps, List.of());
    }

    @Autowired
    public ProviderRegistry(
            List<UpiCashoutPort> upi,
            List<PayoutPort> payout,
            List<AepsPort> aeps,
            List<DmtPort> dmt) {
        this.upi = index(upi, UpiCashoutPort::providerCode);
        this.payout = index(payout, PayoutPort::providerCode);
        this.aeps = index(aeps, AepsPort::providerCode);
        this.dmt = index(dmt == null ? List.of() : dmt, DmtPort::providerCode);
    }

    public UpiCashoutPort upi(String providerCode) {
        return require(upi, providerCode, "cash-out");
    }

    public PayoutPort payout(String providerCode) {
        return require(payout, providerCode, "payout");
    }

    public AepsPort aeps(String providerCode) {
        return require(aeps, providerCode, "AePS");
    }

    public DmtPort dmt(String providerCode) {
        return require(dmt, providerCode, "DMT");
    }

    private static <T> T require(Map<String, T> ports, String providerCode, String service) {
        T port = ports.get(normalize(providerCode));
        if (port == null) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "CHANNEL_UNSUPPORTED",
                    "Selected " + service + " terminal is not yet active.");
        }
        return port;
    }

    private static <T> Map<String, T> index(List<T> ports, Function<T, String> code) {
        return ports.stream().collect(Collectors.toMap(p -> normalize(code.apply(p)), Function.identity(), (a, b) -> a));
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
