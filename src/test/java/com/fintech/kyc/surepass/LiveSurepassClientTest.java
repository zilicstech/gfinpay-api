package com.fintech.kyc.surepass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveSurepassClientTest {

    private HttpServer server;
    private LiveSurepassClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/digilocker/initialize", ex -> respond(ex, 200, """
                {
                  "data": {
                    "client_id": "digilocker_abc123",
                    "token": "eyJ.sdk.token",
                    "expiry_seconds": 600
                  },
                  "status_code": 200,
                  "success": true
                }
                """));
        server.createContext("/api/v1/digilocker/status/digilocker_abc123", ex -> respond(ex, 200, """
                {
                  "data": {
                    "error_description": null,
                    "status": "completed",
                    "completed": true,
                    "failed": false,
                    "aadhaar_linked": true
                  },
                  "status_code": 200,
                  "success": true
                }
                """));
        server.createContext("/api/v1/digilocker/list-documents/digilocker_abc123", ex -> respond(ex, 200, """
                {
                  "data": {
                    "documents": [
                      {
                        "file_id": "aadhaar",
                        "name": "Aadhaar Card",
                        "doc_type": "ADHAR",
                        "downloaded": true,
                        "issuer": "UIDAI",
                        "description": "Aadhaar Card",
                        "file_type": "pdf"
                      },
                      {
                        "file_id": "digilocker_file_pan",
                        "name": "PAN Verification Record",
                        "doc_type": "PANCR",
                        "downloaded": true,
                        "issuer": "Income Tax Department",
                        "description": "PAN Verification Record",
                        "file_type": "pdf"
                      }
                    ]
                  },
                  "status_code": 200,
                  "success": true
                }
                """));
        server.createContext("/api/v1/digilocker/download-document/digilocker_abc123/aadhaar", ex -> respond(ex, 200, """
                {
                  "data": {
                    "download_url": "https://aadhaar-kyc-docs.s3.amazonaws.com/test/ADHAR.pdf",
                    "mime_type": "application/pdf"
                  },
                  "status_code": 200,
                  "success": true
                }
                """));
        server.createContext("/api/v1/digilocker/download-aadhaar/digilocker_abc123", ex -> respond(ex, 200, """
                {
                  "data": {
                    "client_id": "digilocker_abc123",
                    "full_name": "Rajesh Kumar Sharma",
                    "dob": "1990-05-15",
                    "gender": "M",
                    "masked_aadhaar": "XXXXXXXX4512",
                    "profile_image": "/9j/4AAQSkZJRg==",
                    "address": {
                      "care_of": "S/O Ramesh Sharma",
                      "house": "12",
                      "street": "MG Road",
                      "loc": "Indiranagar",
                      "vtc": "Bengaluru",
                      "dist": "Bengaluru Urban",
                      "state": "Karnataka",
                      "zip": "560038",
                      "country": "India"
                    }
                  },
                  "status_code": 200,
                  "success": true
                }
                """));
        server.createContext("/api/v1/digilocker/status/missing_client", ex -> respond(ex, 404, """
                {
                  "data": null,
                  "status_code": 404,
                  "success": false,
                  "message": "Client not found",
                  "message_code": null
                }
                """));
        server.createContext("/api/v1/pan/pan-comprehensive", ex -> {
            if ("/api/v1/pan/pan-comprehensive".equals(ex.getRequestURI().getPath())) {
                byte[] html = "<html><body>308 Permanent Redirect</body></html>".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort() + "/api/v1/pan/pan-comprehensive/");
                ex.sendResponseHeaders(308, html.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(html);
                }
                return;
            }
            assertEquals("Bearer test-bearer-token", ex.getRequestHeaders().getFirst("Authorization"));
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var reqMap = new ObjectMapper().readValue(reqBody, java.util.Map.class);
            assertEquals("ABCDE1234F", reqMap.get("id_number"));
            respond(ex, 200, """
                    {
                      "data": {
                        "client_id": "pan_comp_123",
                        "full_name": "NITIN JHAMNANI",
                        "status": "VALID",
                        "aadhaar_linked": true,
                        "masked_aadhaar": "XXXXXXXX1234",
                        "dob": "1995-01-01",
                        "gender": "M"
                      },
                      "status_code": 200,
                      "success": true
                    }
                    """);
        });
        server.createContext("/api/v1/bank-verification", ex -> {
            assertEquals("/api/v1/bank-verification/", ex.getRequestURI().getPath());
            assertEquals("Bearer test-bearer-token", ex.getRequestHeaders().getFirst("Authorization"));
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var reqMap = new ObjectMapper().readValue(reqBody, java.util.Map.class);
            if ("05456166001".equals(reqMap.get("id_number"))) {
                respond(ex, 422, """
                        {
                            "data": {
                                "client_id": "bank_validation_invalid_01",
                                "account_number": "05456166001",
                                "account_exists": false,
                                "upi_id": null,
                                "full_name": null,
                                "imps_ref_no": null,
                                "remarks": "Invalid Account Number",
                                "status": "invalid_account",
                                "ifsc_details": {
                                    "ifsc": "KKBK0006406",
                                    "bank": "Kotak Mahindra Bank",
                                    "bank_code": "KKBK",
                                    "bank_name": "Kotak Mahindra Bank",
                                    "branch": "RAIPUR MG ROAD BRANCH",
                                    "state": "CHHATTISGARH",
                                    "city": "RAIPUR",
                                    "contact": "+919999999999",
                                    "imps": true,
                                    "rtgs": true,
                                    "neft": true,
                                    "upi": true,
                                    "micr_check": true
                                }
                            },
                            "status_code": 422,
                            "message_code": "verification_failed",
                            "message": "Verification Failed.",
                            "success": false
                        }
                        """);
                return;
            }
            assertEquals("219101000000000", reqMap.get("id_number"));
            assertEquals("BARB0DAMOHA", reqMap.get("ifsc"));
            assertEquals(Boolean.TRUE, reqMap.get("ifsc_details"));
            respond(ex, 200, """
                    {
                        "data": {
                            "client_id": "bank_validation_pjTDReiGjNBputtmJIGC",
                            "account_exists": true,
                            "upi_id": null,
                            "full_name": "A YADAV",
                            "imps_ref_no": "506026134771",
                            "remarks": "",
                            "status": "success",
                            "ifsc_details": {
                                "id": 0,
                                "ifsc": "CNRB0000000",
                                "micr": "123456789",
                                "iso3166": "IN-UP",
                                "swift": null,
                                "bank": "C Bank",
                                "bank_code": "CNRB",
                                "bank_name": "C Bank",
                                "branch": "AGRA",
                                "centre": "AGRA",
                                "district": "AGRA",
                                "state": "UTTAR PRADESH",
                                "city": "AGRA",
                                "address": "AAGRA (UP",
                                "contact": "+911234567890",
                                "imps": true,
                                "rtgs": true,
                                "neft": true,
                                "upi": true,
                                "micr_check": true
                            }
                        },
                        "status_code": 200,
                        "success": true,
                        "message": null,
                        "message_code": "success"
                    }
                    """);
        });
        server.start();

        int port = server.getAddress().getPort();
        SurepassProperties props = new SurepassProperties(
                "live",
                "http://localhost:" + port,
                "test-bearer-token",
                "production",
                null,
                true,
                List.of("ADHAR", "PANCR"),
                15L * 1024 * 1024,
                List.of("amazonaws.com")
        );
        client = new LiveSurepassClient(props);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void parsesInitializeStatusListDownloadAndStructuredAadhaar() {
        SurepassClient.DigilockerSession session = client.digilockerInitialize("Rajesh");
        assertEquals("digilocker_abc123", session.clientId());
        assertEquals("eyJ.sdk.token", session.sdkToken());
        assertEquals("production", session.gateway());

        SurepassClient.DigilockerStatus status = client.digilockerStatus("digilocker_abc123");
        assertTrue(status.completed());
        assertTrue(status.aadhaarLinked());

        List<SurepassClient.DigilockerDocument> docs = client.digilockerListDocuments("digilocker_abc123");
        assertEquals(2, docs.size());
        assertEquals("ADHAR", docs.get(0).docType());

        SurepassClient.DigilockerDownloadLink link = client.digilockerDownloadDocument("digilocker_abc123", "aadhaar");
        assertEquals("application/pdf", link.mimeType());
        assertTrue(link.downloadUrl().startsWith("https://aadhaar-kyc-docs.s3.amazonaws.com/"));

        SurepassClient.AadhaarRecord aadhaar = client.digilockerAadhaar("digilocker_abc123");
        assertEquals("Rajesh Kumar Sharma", aadhaar.fullName());
        assertEquals("560038", aadhaar.structuredAddress().pincode());
        assertEquals("Bengaluru", aadhaar.structuredAddress().vtc());
        assertTrue(aadhaar.photoBytes().length > 0);
    }

    @Test
    void translates404ClientNotFoundToDomainException() {
        ApiException ex = assertThrows(ApiException.class, () -> client.digilockerStatus("missing_client"));
        assertEquals("DIGILOCKER_SESSION_NOT_FOUND", ex.getCode());
    }

    @Test
    void bankVerificationParsesIfscDetailsAndOmitsBranchContactNumber() {
        SurepassClient.BankRecord rec = client.bankVerification("219101000000000", "BARB0DAMOHA", "Amit Yadav");
        assertTrue(rec.accountExists());
        assertEquals("A YADAV", rec.fullName());
        assertEquals("C Bank", rec.bankName());
        assertEquals("bank_validation_pjTDReiGjNBputtmJIGC", rec.providerRef());
        assertEquals("506026134771", rec.impsRefNo());
        assertEquals("success", rec.status());

        SurepassClient.IfscDetails d = rec.ifscDetails();
        assertEquals("CNRB", d.bankCode());
        assertEquals("C Bank", d.bankName());
        assertEquals("AGRA", d.branch());
        assertEquals("UTTAR PRADESH", d.state());
        assertTrue(d.imps());
        assertTrue(d.rtgs());
        assertTrue(d.neft());
        assertTrue(d.upi());
        // Branch phone `contact` (+911234567890) must never be mapped
        org.junit.jupiter.api.Assertions.assertFalse(rec.toString().contains("911234567890"));
    }

    @Test
    void followsSameOrigin308RedirectPreservingBearerTokenAndJsonBody() {
        SurepassClient.PanRecord pan = client.panComprehensive("ABCDE1234F", "Nitin");
        assertTrue(pan.valid());
        assertEquals("NITIN JHAMNANI", pan.fullName());
        assertEquals("pan_comp_123", pan.providerRef());
    }

    @Test
    void bankVerification422StructuredFailureReturnsRemarksAndIfscDetails() {
        SurepassClient.BankRecord rec = client.bankVerification("05456166001", "KKBK0006406", "Nitin Jhamnani");
        org.junit.jupiter.api.Assertions.assertFalse(rec.accountExists());
        assertEquals("Invalid Account Number", rec.remarks());
        assertEquals("invalid_account", rec.status());
        assertEquals("Kotak Mahindra Bank", rec.bankName());
        assertEquals("bank_validation_invalid_01", rec.providerRef());
        assertEquals("RAIPUR MG ROAD BRANCH", rec.ifscDetails().branch());
        org.junit.jupiter.api.Assertions.assertFalse(rec.toString().contains("9999999999"));
    }
}
