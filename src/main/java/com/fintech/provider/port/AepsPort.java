package com.fintech.provider.port;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral Aadhaar Enabled Payment System (AePS) port supporting
 * bank directory lookup, daily/per-txn merchant 2FA, >₹5,000 customer OTP challenges,
 * CW / BE / MS / CD execution, and status reconciliation.
 */
public interface AepsPort {

    String providerCode();

    ProviderModels.AepsResult transact(ServiceProviderRouter.RoutedChannel channel, Map<String, Object> payload);

    default ProviderModels.AepsResult transact(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.AepsTxnRequest request) {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("pipeCode", channel.pipeCode());
        legacy.put("txnType", request.aepsType());
        legacy.put("bankIin", request.bankIin());
        legacy.put("mobile", request.customerMobile());
        legacy.put("amount", request.amount() == null ? "0.00" : request.amount().toPlainString());
        legacy.put("externalRef", request.externalRef());
        legacy.put("referenceKey", request.referenceKey() == null ? "" : request.referenceKey());
        legacy.put("captureType", request.captureType() == null ? "FINGER" : request.captureType());
        legacy.put("latitude", request.latitude());
        legacy.put("longitude", request.longitude());
        if (request.pid() != null) {
            legacy.put("biometricData", request.pid().toEmeraldBiometricData(request.customerAadhaar12()));
        }
        return transact(channel, legacy);
    }

    default List<ProviderModels.AepsBank> listBanks(ServiceProviderRouter.RoutedChannel channel) {
        return List.of();
    }

    default ProviderModels.AepsLoginStatus checkOutletLoginStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String serviceType) {
        return new ProviderModels.AepsLoginStatus(
                true, false, false, "", false, false, false, "2000", "LOGGEDIN", "Ready", "");
    }

    default ProviderModels.AepsLoginStatus submitOutletLogin(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.AepsOutletLoginRequest request) {
        return new ProviderModels.AepsLoginStatus(
                true, false, false, "", false, false, false, "2000", "LOGGEDIN", "Logged in", "");
    }

    default ProviderModels.AepsOtpChallenge requestTransactionOtp(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.AepsOtpRequest request) {
        return new ProviderModels.AepsOtpChallenge(
                true, false, false, false, "", "", "2000", "OTP not required on this rail");
    }

    default ProviderModels.AepsStatus checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String externalRef,
            LocalDate transactionDate) {
        return new ProviderModels.AepsStatus(
                ProviderModels.Outcome.PENDING, "PENDING", "Status check not supported", "", "");
    }

    default BigDecimal defaultCwOtpThreshold() {
        return new BigDecimal("5000.01");
    }
}
