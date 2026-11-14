package com.fintech.agreement;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.agreement")
public record AgreementProperties(
        Boolean enforce,
        BigDecimal defaultOnboardingFee,
        CompanyProperties company
) {
    public AgreementProperties {
        if (enforce == null) {
            enforce = true;
        }
        if (company == null) {
            company = new CompanyProperties(null, null, null, null, null);
        }
    }

    public record CompanyProperties(
            String name,
            String address,
            String signatory,
            String boardResolutionDate,
            String place
    ) {
        public CompanyProperties {
            if (name == null || name.isBlank()) {
                name = "GATEWAYLINE FINTECH PRIVATE LIMITED";
            }
            if (address == null || address.isBlank()) {
                address = "PS Abacus, Unit 308, Plot No. IIE/23, New Town, PS ECO PARK, North 24 Parganas, West Bengal, India, 700161";
            }
            if (signatory == null || signatory.isBlank()) {
                signatory = "Manish Kumar Sinha";
            }
            if (boardResolutionDate == null || boardResolutionDate.isBlank()) {
                boardResolutionDate = "27/10/2026";
            }
            if (place == null || place.isBlank()) {
                place = "New Delhi";
            }
        }
    }
}

