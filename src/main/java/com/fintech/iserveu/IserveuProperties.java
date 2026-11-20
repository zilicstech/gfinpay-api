package com.fintech.iserveu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IserveuProperties {

    private final boolean enabled;
    private final String cashpointUrl;
    private final String cashpointStatusUrl;
    private final String payoutUrl;
    private final String payoutStatusUrl;
    private final String payoutStatusOp;
    private final String clientId;
    private final String clientSecret;
    private final String passKey;
    private final String encKey;
    private final String headerSecret;

    public IserveuProperties(
            @Value("${app.iserveu.enabled:false}") boolean enabled,
            @Value("${app.iserveu.cashpoint-url:}") String cashpointUrl,
            @Value("${app.iserveu.cashpoint-status-url:}") String cashpointStatusUrl,
            @Value("${app.iserveu.payout-url:}") String payoutUrl,
            @Value("${app.iserveu.payout-status-url:}") String payoutStatusUrl,
            @Value("${app.iserveu.payout-status-op:Cashout_addbank_status}") String payoutStatusOp,
            @Value("${app.iserveu.client-id:}") String clientId,
            @Value("${app.iserveu.client-secret:}") String clientSecret,
            @Value("${app.iserveu.pass-key:}") String passKey,
            @Value("${app.iserveu.enc-key:}") String encKey,
            @Value("${app.iserveu.header-secret:}") String headerSecret) {
        this.enabled = enabled;
        this.cashpointUrl = trim(cashpointUrl);
        this.cashpointStatusUrl = trim(cashpointStatusUrl);
        this.payoutUrl = trim(payoutUrl);
        this.payoutStatusUrl = trim(payoutStatusUrl);
        this.payoutStatusOp = trim(payoutStatusOp).isBlank() ? "Cashout_addbank_status" : trim(payoutStatusOp);
        this.clientId = trim(clientId);
        this.clientSecret = trim(clientSecret);
        this.passKey = trim(passKey);
        this.encKey = trim(encKey);
        this.headerSecret = trim(headerSecret);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean credentialsPresent() {
        return enabled && !clientId.isBlank() && !clientSecret.isBlank() && !passKey.isBlank() && !encKey.isBlank();
    }

    public boolean cashpointReady() {
        return credentialsPresent() && !cashpointUrl.isBlank();
    }

    public boolean cashpointStatusReady() {
        return credentialsPresent() && !cashpointStatusUrl.isBlank();
    }

    public boolean payoutSubmitReady() {
        return credentialsPresent() && !payoutUrl.isBlank();
    }

    public boolean payoutStatusReady() {
        return credentialsPresent() && !payoutStatusUrl.isBlank();
    }

    public String cashpointUrl() {
        return cashpointUrl;
    }

    public String cashpointStatusUrl() {
        return cashpointStatusUrl;
    }

    public String payoutUrl() {
        return payoutUrl;
    }

    public String payoutStatusUrl() {
        return payoutStatusUrl;
    }

    public String payoutStatusOp() {
        return payoutStatusOp;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String passKey() {
        return passKey;
    }

    public String headerSecret() {
        return headerSecret;
    }

    public String encKey() {
        return encKey;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
