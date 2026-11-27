package com.fintech.instantpay;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Environment-backed configuration for direct InstantPay integration ({@code app.instantpay.*}).
 */
@Component
@ConfigurationProperties(prefix = "app.instantpay")
public class InstantPayProperties {

    private boolean enabled = false;
    private String baseUrl = "https://api.instantpay.in";
    private String authCode = "1";
    private String clientId = "";
    private String clientSecret = "";
    private String encryptionKey = "";
    private String endpointIp = "";
    private String defaultSpkey = "WAP";
    private String defaultGw = "JA";
    private String payerBankProfileId = "0";
    private String payerAccountNumber = "9650043494";
    private String payoutPurpose = "REIMBURSEMENT";
    private boolean payoutIncludeOutletId = true;
    private String webhookSecret = "";
    private int aepsBankCacheMinutes = 30;
    private int dmtBankCacheMinutes = 60;

    public boolean enabled() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String baseUrl() {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.isEmpty() ? "https://api.instantpay.in" : u;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String authCode() {
        String c = authCode == null ? "" : authCode.trim();
        return c.isEmpty() ? "1" : c;
    }

    public String getAuthCode() {
        return authCode;
    }

    public void setAuthCode(String authCode) {
        this.authCode = authCode;
    }

    public String clientId() {
        return clientId == null ? "" : clientId.trim();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String clientSecret() {
        return clientSecret == null ? "" : clientSecret.trim();
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String encryptionKey() {
        return encryptionKey == null ? "" : encryptionKey.trim();
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String endpointIp() {
        return endpointIp == null ? "" : endpointIp.trim();
    }

    public String getEndpointIp() {
        return endpointIp;
    }

    public void setEndpointIp(String endpointIp) {
        this.endpointIp = endpointIp;
    }

    public String defaultSpkey() {
        String s = defaultSpkey == null ? "" : defaultSpkey.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? "WAP" : s;
    }

    public String getDefaultSpkey() {
        return defaultSpkey;
    }

    public void setDefaultSpkey(String defaultSpkey) {
        this.defaultSpkey = defaultSpkey;
    }

    public String defaultGw() {
        String g = defaultGw == null ? "" : defaultGw.trim().toUpperCase(Locale.ROOT);
        return g.isEmpty() ? "JA" : g;
    }

    public String getDefaultGw() {
        return defaultGw;
    }

    public void setDefaultGw(String defaultGw) {
        this.defaultGw = defaultGw;
    }

    public String payerBankProfileId() {
        String id = payerBankProfileId == null ? "" : payerBankProfileId.trim();
        return id.isEmpty() ? "0" : id;
    }

    public String getPayerBankProfileId() {
        return payerBankProfileId;
    }

    public void setPayerBankProfileId(String payerBankProfileId) {
        this.payerBankProfileId = payerBankProfileId;
    }

    public String payerAccountNumber() {
        String raw = payerAccountNumber == null ? "" : payerAccountNumber.trim();
        if ("0".equals(payerBankProfileId())) {
            String digits = raw.replaceAll("\\D", "");
            if (digits.length() == 12 && digits.startsWith("91")) {
                return digits.substring(2);
            }
            return digits;
        }
        return raw;
    }

    public String getPayerAccountNumber() {
        return payerAccountNumber;
    }

    public void setPayerAccountNumber(String payerAccountNumber) {
        this.payerAccountNumber = payerAccountNumber;
    }

    public String payoutPurpose() {
        String p = payoutPurpose == null ? "" : payoutPurpose.trim().toUpperCase(Locale.ROOT);
        return p.isEmpty() ? "REIMBURSEMENT" : p;
    }

    public String getPayoutPurpose() {
        return payoutPurpose;
    }

    public void setPayoutPurpose(String payoutPurpose) {
        this.payoutPurpose = payoutPurpose;
    }

    public boolean payoutIncludeOutletId() {
        return payoutIncludeOutletId;
    }

    public boolean isPayoutIncludeOutletId() {
        return payoutIncludeOutletId;
    }

    public void setPayoutIncludeOutletId(boolean payoutIncludeOutletId) {
        this.payoutIncludeOutletId = payoutIncludeOutletId;
    }

    public String webhookSecret() {
        return webhookSecret == null ? "" : webhookSecret.trim();
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public int aepsBankCacheMinutes() {
        return Math.max(1, aepsBankCacheMinutes);
    }

    public int getAepsBankCacheMinutes() {
        return aepsBankCacheMinutes;
    }

    public void setAepsBankCacheMinutes(int aepsBankCacheMinutes) {
        this.aepsBankCacheMinutes = aepsBankCacheMinutes;
    }

    public int dmtBankCacheMinutes() {
        return Math.max(1, dmtBankCacheMinutes);
    }

    public int getDmtBankCacheMinutes() {
        return dmtBankCacheMinutes;
    }

    public void setDmtBankCacheMinutes(int dmtBankCacheMinutes) {
        this.dmtBankCacheMinutes = dmtBankCacheMinutes;
    }

    public boolean isConfigured() {
        return !clientId().isEmpty() && !clientSecret().isEmpty();
    }
}
