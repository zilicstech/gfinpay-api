package com.fintech.provider.port;

import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import java.math.BigDecimal;
import java.util.List;

/**
 * Provider-neutral Domestic Money Transfer (DMT) port covering bank directory sync,
 * remitter lookup &amp; one-time registration/eKYC, OTP-verified beneficiary management,
 * IMPS/NEFT transfer OTP &amp; execution, and webhook-free transaction status reconfirmation.
 */
public interface DmtPort {

    String providerCode();

    List<ProviderModels.DmtBankItem> listBanks(ServiceProviderRouter.RoutedChannel channel, boolean forceRefresh);

    ProviderModels.DmtRemitterProfile fetchRemitterProfile(
            ServiceProviderRouter.RoutedChannel channel,
            String mobileNumber);

    ProviderModels.DmtOtpChallenge registerRemitter(
            ServiceProviderRouter.RoutedChannel channel,
            String mobileNumber,
            String plainAadhaar12,
            String referenceKey);

    ProviderModels.DmtOtpChallenge verifyRemitterRegistration(
            ServiceProviderRouter.RoutedChannel channel,
            String mobileNumber,
            String otp,
            String referenceKey);

    ProviderModels.DmtRemitterEkycResult submitRemitterEkyc(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.DmtRemitterEkycRequest request);

    ProviderModels.DmtOtpChallenge registerBeneficiary(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.DmtBeneRegisterRequest request);

    ProviderModels.DmtOtpChallenge verifyBeneficiaryRegistration(
            ServiceProviderRouter.RoutedChannel channel,
            String remitterMobile,
            String beneficiaryId,
            String otp,
            String referenceKey);

    ProviderModels.DmtOtpChallenge deleteBeneficiary(
            ServiceProviderRouter.RoutedChannel channel,
            String remitterMobile,
            String beneficiaryId);

    ProviderModels.DmtOtpChallenge verifyBeneficiaryDeletion(
            ServiceProviderRouter.RoutedChannel channel,
            String remitterMobile,
            String beneficiaryId,
            String otp,
            String referenceKey);

    ProviderModels.DmtOtpChallenge generateTransactionOtp(
            ServiceProviderRouter.RoutedChannel channel,
            String remitterMobile,
            BigDecimal amount,
            String referenceKey);

    ProviderModels.DmtTransferResult executeTransfer(
            ServiceProviderRouter.RoutedChannel channel,
            ProviderModels.DmtTransferRequest request);

    ProviderModels.DmtTransferStatus checkTransferStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String externalRef,
            String providerTxnId,
            String txnDate,
            boolean force);
}
