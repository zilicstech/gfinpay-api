package com.fintech.kyc.surepass;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.surepass")
public record SurepassProperties(
        String mode,
        String baseUrl,
        String apiToken,
        String gateway,
        String digilockerLogoUrl,
        Boolean digilockerSkipMainScreen,
        List<String> digilockerMandatoryDocTypes,
        Long digilockerMaxDocBytes,
        List<String> digilockerDownloadHosts
) {
    public String effectiveGateway() {
        return (gateway == null || gateway.isBlank()) ? "production" : gateway.trim();
    }

    public boolean effectiveSkipMainScreen() {
        return digilockerSkipMainScreen == null || digilockerSkipMainScreen;
    }

    public List<String> effectiveMandatoryDocTypes() {
        if (digilockerMandatoryDocTypes == null || digilockerMandatoryDocTypes.isEmpty()) {
            return List.of("ADHAR", "PANCR");
        }
        return digilockerMandatoryDocTypes;
    }

    public long effectiveMaxDocBytes() {
        return (digilockerMaxDocBytes == null || digilockerMaxDocBytes <= 0)
                ? 15L * 1024 * 1024
                : digilockerMaxDocBytes;
    }

    public List<String> effectiveDownloadHosts() {
        if (digilockerDownloadHosts == null || digilockerDownloadHosts.isEmpty()) {
            return List.of("amazonaws.com");
        }
        return digilockerDownloadHosts;
    }
}
