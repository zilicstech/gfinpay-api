package com.fintech.agreement.esign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SurepassEsignClientTest {

    private HttpServer server;
    private SurepassEsignClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastInitBody = new AtomicReference<>();
    private final AtomicReference<String> lastUploadAuth = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/v1/files/esign/upload", ex -> {
            lastUploadAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(reqBody.contains("GFP-MSA-2026-00001.pdf"));
            respondJson(ex, 200, """
                    {
                      "data": {
                        "client_id": "upload_client_123",
                        "file_id": "file_xyz_789",
                        "uploaded": true
                      },
                      "status_code": 200,
                      "success": true,
                      "message": "Success",
                      "message_code": "success"
                    }
                    """);
        });

        server.createContext("/api/v1/esign/initialize", ex -> {
            lastInitBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(ex, 200, """
                    {
                      "data": {
                        "client_id": "esign_HwEwRYRYjexlRMwvolkS",
                        "token": "eyJ.surepass.token",
                        "url": "https://esign.surepass.io/?token=eyJ.surepass.token"
                      },
                      "status_code": 200,
                      "success": true,
                      "message": "Success",
                      "message_code": "success"
                    }
                    """);
        });

        server.createContext("/api/v1/esign/status/esign_completed_id", ex -> respondJson(ex, 200, """
                {
                  "data": {
                    "client_id": "esign_completed_id",
                    "status": "esign_completed"
                  },
                  "status_code": 200,
                  "success": true,
                  "message": "Success",
                  "message_code": "success"
                }
                """));

        server.createContext("/api/v1/esign/status/esign_otp_sent_id", ex -> respondJson(ex, 200, """
                {
                  "data": {
                    "client_id": "esign_otp_sent_id",
                    "status": "otp_sent"
                  },
                  "status_code": 200,
                  "success": true,
                  "message": "Success",
                  "message_code": "success"
                }
                """));

        server.createContext("/api/v1/esign/status/esign_failed_id", ex -> respondJson(ex, 200, """
                {
                  "data": {
                    "client_id": "esign_failed_id",
                    "status": "esign_failed"
                  },
                  "status_code": 200,
                  "success": true,
                  "message": "Success",
                  "message_code": "success"
                }
                """));

        server.createContext("/api/v1/esign/status/esign_missing_id", ex -> respondJson(ex, 404, """
                {
                  "status_code": 404,
                  "success": false,
                  "message": "Client ID not found",
                  "message_code": "client_id_not_found"
                }
                """));

        server.createContext("/api/v1/esign/get-signed-document/esign_completed_id", ex -> respondJson(ex, 200, """
                {
                  "data": {
                    "url": "http://localhost:%d/signed-files/GFP-MSA-2026-00001-signed.pdf"
                  },
                  "status_code": 200,
                  "success": true
                }
                """.formatted(port)));

        server.createContext("/signed-files/GFP-MSA-2026-00001-signed.pdf", ex -> {
            byte[] pdf = "%PDF-1.4\nSigned PDF Content\n%%EOF".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/pdf");
            ex.sendResponseHeaders(200, pdf.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pdf);
            }
        });

        server.start();

        EsignProperties props = new EsignProperties(
                "live",
                "http://localhost:" + port,
                "live-jwt-token-xyz",
                "https://app.gfinpay.com/esign/return",
                true,
                70,
                20,
                24
        );
        client = new SurepassEsignClient(props);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respondJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadInitializeStatusAndDownloadSignedDocument() throws Exception {
        byte[] samplePdf = "%PDF-1.4\nUnsigned Agreement\n%%EOF".getBytes(StandardCharsets.UTF_8);
        EsignClient.EsignUpload upload = client.uploadDocument("GFP-MSA-2026-00001.pdf", samplePdf);
        assertEquals("file_xyz_789", upload.fileId());
        assertEquals("Bearer live-jwt-token-xyz", lastUploadAuth.get());

        UUID agreementId = UUID.randomUUID();
        EsignClient.EsignSession init = client.initialize(new EsignClient.EsignInitRequest(
                upload.fileId(),
                agreementId.toString(),
                "GFP-MSA-2026-00001",
                "Partner Master Service Agreement (GFP-MSA-2026-00001)",
                "https://app.gfinpay.com/esign/return?a=" + agreementId,
                Map.of("1", List.of(Map.of("x", 410, "y", 24))),
                70,
                "HEMANT MISHRA",
                "9876543210",
                "hemant@zilics.com"
        ));
        assertEquals("esign_HwEwRYRYjexlRMwvolkS", init.clientId());
        assertEquals("eyJ.surepass.token", init.token());
        assertEquals("https://esign.surepass.io/?token=eyJ.surepass.token", init.url());

        Map<String, Object> sentPayload = mapper.readValue(lastInitBody.get(), Map.class);
        assertEquals("file_xyz_789", sentPayload.get("file_id"));
        assertEquals(true, sentPayload.get("pdf_pre_uploaded"));
        assertEquals(false, sentPayload.get("send_email"));
        assertEquals("aadhaar", sentPayload.get("sign_type"));
        Map<String, Object> config = (Map<String, Object>) sentPayload.get("config");
        Map<String, Object> nameMatch = (Map<String, Object>) config.get("name_match");
        assertEquals(true, nameMatch.get("match"));
        assertEquals(70, nameMatch.get("score"));
        Map<String, Object> prefill = (Map<String, Object>) sentPayload.get("prefill_options");
        assertEquals("HEMANT MISHRA", prefill.get("full_name"));
        assertEquals("9876543210", prefill.get("mobile_number"));

        // Status checks
        EsignClient.EsignStatus inProg = client.status("esign_otp_sent_id");
        assertEquals(EsignClient.Phase.IN_PROGRESS, inProg.phase());
        assertEquals("otp_sent", inProg.rawStatus());

        EsignClient.EsignStatus completed = client.status("esign_completed_id");
        assertEquals(EsignClient.Phase.COMPLETED, completed.phase());
        assertEquals("esign_completed", completed.rawStatus());

        EsignClient.EsignStatus failed = client.status("esign_failed_id");
        assertEquals(EsignClient.Phase.FAILED, failed.phase());
        assertEquals("esign_failed", failed.rawStatus());

        EsignClient.EsignStatus notFound = client.status("esign_missing_id");
        assertEquals(EsignClient.Phase.NOT_FOUND, notFound.phase());

        // Signed PDF download
        String signedUrl = client.signedDocumentUrl("esign_completed_id");
        assertTrue(signedUrl.endsWith("/signed-files/GFP-MSA-2026-00001-signed.pdf"));
        byte[] signedBytes = client.download(signedUrl);
        assertTrue(new String(signedBytes, StandardCharsets.UTF_8).startsWith("%PDF-"));
    }
}
