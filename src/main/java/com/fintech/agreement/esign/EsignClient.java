package com.fintech.agreement.esign;

import java.util.List;
import java.util.Map;

public interface EsignClient {

    enum Phase {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        NOT_FOUND
    }

    record EsignUpload(String fileId, String clientId) {}

    record EsignInitRequest(
            String fileId,
            String agreementId,
            String agreementNo,
            String reason,
            String redirectUrl,
            Map<String, List<Map<String, Integer>>> positions,
            int nameMatchScore,
            String signerFullName,
            String signerMobile,
            String signerEmail
    ) {}

    record EsignSession(String clientId, String token, String url) {}

    record EsignStatus(String clientId, String rawStatus, Phase phase, String label) {}

    String mode();

    EsignUpload uploadDocument(String fileName, byte[] pdfBytes);

    EsignSession initialize(EsignInitRequest request);

    EsignStatus status(String clientId);

    String signedDocumentUrl(String clientId);

    byte[] download(String url);
}

