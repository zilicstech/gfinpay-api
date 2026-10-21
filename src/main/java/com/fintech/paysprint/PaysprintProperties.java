package com.fintech.paysprint;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.paysprint")
public record PaysprintProperties(
        String baseUrl,
        String partnerId,
        String merchantCode,
        String jwtKey,
        String authorisedKey,
        String aesKey,
        String aesIv,
        String jwtProduct,
        /** 0 = unsecured, 1 = secured FD card. Generate UTM uses 1. */
        String fdCardType,
        boolean callbackEnabled,
        String callbackSecret,
        String wadhBank2,
        String wadhBank6,
        String rdEnv,
        String isCasa,
        boolean allowPastedPid) {

    public String merchantCodeOrPartner() {
        if (merchantCode == null || merchantCode.isBlank()) {
            return partnerId;
        }
        return merchantCode;
    }

    public String fdCardTypeOrSecured() {
        if (fdCardType == null || fdCardType.isBlank()) {
            return "1";
        }
        return fdCardType;
    }

    public String wadhFor(String pipe) {
        return "bank6".equals(pipe) ? wadhBank6 : wadhBank2;
    }

    public String rdEnvOrLive() {
        return rdEnv == null || rdEnv.isBlank() ? "P" : rdEnv;
    }
}
