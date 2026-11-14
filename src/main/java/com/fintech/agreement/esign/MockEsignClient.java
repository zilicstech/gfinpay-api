package com.fintech.agreement.esign;

import com.fintech.platform.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "app.esign", name = "mode", havingValue = "mock")
@EnableConfigurationProperties(EsignProperties.class)
public class MockEsignClient implements EsignClient {

    public record UploadedFile(String fileId, String fileName, byte[] pdfBytes) {}

    public static class MockSessionState {
        public final String clientId;
        public final EsignInitRequest request;
        public final byte[] unsignedPdf;
        public volatile String rawStatus = "client_intiated";
        public volatile String signerNameOverride;
        public volatile boolean signedDocReady = true;

        public MockSessionState(String clientId, EsignInitRequest request, byte[] unsignedPdf) {
            this.clientId = clientId;
            this.request = request;
            this.unsignedPdf = unsignedPdf;
            this.signerNameOverride = request.signerFullName();
        }
    }

    private final EsignProperties props;
    private final Map<String, UploadedFile> uploads = new ConcurrentHashMap<>();
    private final Map<String, MockSessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger uploadCalls = new AtomicInteger();
    private final AtomicInteger initCalls = new AtomicInteger();
    private final AtomicInteger statusCalls = new AtomicInteger();
    private final AtomicInteger signedDocCalls = new AtomicInteger();

    public MockEsignClient(EsignProperties props) {
        this.props = props;
    }

    @Override
    public String mode() {
        return "mock";
    }

    @Override
    public EsignUpload uploadDocument(String fileName, byte[] pdfBytes) {
        uploadCalls.incrementAndGet();
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ESIGN_DOCUMENT_EMPTY", "PDF is empty");
        }
        String id = "files_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        uploads.put(id, new UploadedFile(id, fileName, pdfBytes));
        return new EsignUpload(id, id);
    }

    @Override
    public EsignSession initialize(EsignInitRequest request) {
        initCalls.incrementAndGet();
        if (!StringUtils.hasText(request.fileId()) || !uploads.containsKey(request.fileId())) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_INIT_FAILED", "Invalid or missing file_id");
        }
        UploadedFile uploaded = uploads.get(request.fileId());
        String clientId = "esign_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String token = "mock-jwt-" + clientId;
        String baseRedirect = props.redirectUrl();
        String origin = "http://localhost:3001";
        int idx = baseRedirect.indexOf("/esign/return");
        if (idx > 0) {
            origin = baseRedirect.substring(0, idx);
        }
        String url = origin + "/esign/mock/" + clientId + "?a=" + request.agreementId();
        sessions.put(clientId, new MockSessionState(clientId, request, uploaded.pdfBytes()));
        return new EsignSession(clientId, token, url);
    }

    @Override
    public EsignStatus status(String clientId) {
        statusCalls.incrementAndGet();
        MockSessionState state = sessions.get(clientId);
        if (state == null) {
            return new EsignStatus(clientId, "client_id_not_found", Phase.NOT_FOUND,
                    "eSign session not found — please start again");
        }
        return SurepassEsignClient.mapStatus(clientId, state.rawStatus);
    }

    @Override
    public String signedDocumentUrl(String clientId) {
        signedDocCalls.incrementAndGet();
        MockSessionState state = sessions.get(clientId);
        if (state == null || !"esign_completed".equalsIgnoreCase(state.rawStatus) || !state.signedDocReady) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "ESIGN_SIGNED_DOC_NOT_READY",
                    "No signed document found for the provided client ID");
        }
        return "mock://signed/" + clientId;
    }

    @Override
    public byte[] download(String url) {
        if (url != null && url.startsWith("mock://signed/")) {
            String clientId = url.substring("mock://signed/".length());
            MockSessionState state = sessions.get(clientId);
            if (state == null) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "ESIGN_DOWNLOAD_FAILED", "Mock session not found");
            }
            return stampSignedPdf(state);
        }
        throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_DOWNLOAD_FAILED", "Unsupported mock URL: " + url);
    }

    public MockSessionState getSession(String clientId) {
        return sessions.get(clientId);
    }

    public void setStatus(String clientId, String rawStatus) {
        MockSessionState state = sessions.get(clientId);
        if (state != null) {
            state.rawStatus = rawStatus;
        }
    }

    public void complete(String clientId, String signerName) {
        MockSessionState state = sessions.get(clientId);
        if (state != null) {
            if (StringUtils.hasText(signerName)) {
                state.signerNameOverride = signerName.trim();
            }
            state.rawStatus = "esign_completed";
            state.signedDocReady = true;
        }
    }

    public void removeSession(String clientId) {
        sessions.remove(clientId);
    }

    public void resetCounters() {
        uploadCalls.set(0);
        initCalls.set(0);
        statusCalls.set(0);
        signedDocCalls.set(0);
    }

    public int uploadCalls() { return uploadCalls.get(); }
    public int initCalls() { return initCalls.get(); }
    public int statusCalls() { return statusCalls.get(); }
    public int signedDocCalls() { return signedDocCalls.get(); }

    private byte[] stampSignedPdf(MockSessionState state) {
        String signer = StringUtils.hasText(state.signerNameOverride)
                ? state.signerNameOverride : state.request.signerFullName();
        try (PDDocument doc = PDDocument.load(state.unsignedPdf);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.getDocumentInformation().setCustomMetadataValue("EsignSignerName", signer);
            doc.getDocumentInformation().setCustomMetadataValue("EsignClientId", state.clientId);
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 7.5f);
                    cs.newLineAtOffset(350, 16);
                    cs.showText("Aadhaar eSigned by: " + asciiSafe(signer) + " [" + state.clientId + "]");
                    cs.endText();
                    if (i == total - 1) {
                        cs.beginText();
                        cs.setFont(PDType1Font.HELVETICA_BOLD, 8.5f);
                        cs.newLineAtOffset(320, 270);
                        cs.showText("Digitally Signed via Aadhaar eSign: " + asciiSafe(signer));
                        cs.newLineAtOffset(0, -12);
                        cs.setFont(PDType1Font.HELVETICA, 7.5f);
                        cs.showText("Session: " + state.clientId + " | " + Instant.now().toString());
                        cs.endText();
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            return state.unsignedPdf;
        }
    }

    private static String asciiSafe(String s) {
        if (s == null) return "";
        return new String(s.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}

