package com.fintech.kyc.surepass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.platform.web.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DigilockerFileDownloaderTest {

    private final SurepassProperties props = new SurepassProperties(
            "live",
            "https://kyc-api.surepass.app",
            "tok",
            "production",
            null,
            true,
            List.of("ADHAR", "PANCR"),
            1024L,
            List.of("amazonaws.com")
    );

    @Test
    void downloadsValidPdfFromAllowlistedHttpsHost() {
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.UTF_8);
        DigilockerFileDownloader downloader = new DigilockerFileDownloader(
                props,
                uri -> new DigilockerFileDownloader.OpenedStream(
                        new ByteArrayInputStream(pdfBytes), "application/pdf", 200));

        DigilockerFileDownloader.DownloadedFile res = downloader.download(
                "https://aadhaar-kyc-docs.s3.amazonaws.com/test/digilocker/PANCR.pdf?X-Amz-Signature=abc",
                "application/pdf");
        assertEquals("application/pdf", res.mimeType());
        assertEquals(pdfBytes.length, res.bytes().length);
        assertEquals(64, res.sha256().length());
    }

    @Test
    void rejectsNonHttpsUnlistedHostOversizedAndInvalidPdfHeader() {
        byte[] pdfBytes = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8);
        DigilockerFileDownloader downloader = new DigilockerFileDownloader(
                props,
                uri -> new DigilockerFileDownloader.OpenedStream(
                        new ByteArrayInputStream(pdfBytes), "application/pdf", 200));

        ApiException schemeEx = assertThrows(ApiException.class, () ->
                downloader.download("http://aadhaar-kyc-docs.s3.amazonaws.com/doc.pdf", "application/pdf"));
        assertEquals("UNSAFE_DOWNLOAD_SCHEME", schemeEx.getCode());

        ApiException hostEx = assertThrows(ApiException.class, () ->
                downloader.download("https://evil.example.com/doc.pdf", "application/pdf"));
        assertEquals("UNSAFE_DOWNLOAD_HOST", hostEx.getCode());

        byte[] notPdf = "<html>error</html>".getBytes(StandardCharsets.UTF_8);
        DigilockerFileDownloader badPdfDownloader = new DigilockerFileDownloader(
                props,
                uri -> new DigilockerFileDownloader.OpenedStream(
                        new ByteArrayInputStream(notPdf), "application/pdf", 200));
        ApiException headerEx = assertThrows(ApiException.class, () ->
                badPdfDownloader.download("https://aadhaar-kyc-docs.s3.amazonaws.com/doc.pdf", "application/pdf"));
        assertEquals("INVALID_PDF_CONTENT", headerEx.getCode());

        byte[] oversized = new byte[2048];
        oversized[0] = '%';
        oversized[1] = 'P';
        oversized[2] = 'D';
        oversized[3] = 'F';
        oversized[4] = '-';
        DigilockerFileDownloader largeDownloader = new DigilockerFileDownloader(
                props,
                uri -> new DigilockerFileDownloader.OpenedStream(
                        new ByteArrayInputStream(oversized), "application/pdf", 200));
        ApiException sizeEx = assertThrows(ApiException.class, () ->
                largeDownloader.download("https://aadhaar-kyc-docs.s3.amazonaws.com/doc.pdf", "application/pdf"));
        assertTrue("DOCUMENT_TOO_LARGE".equals(sizeEx.getCode()));
    }
}

