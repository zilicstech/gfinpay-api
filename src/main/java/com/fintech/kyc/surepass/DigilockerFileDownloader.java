package com.fintech.kyc.surepass;

import com.fintech.platform.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@EnableConfigurationProperties(SurepassProperties.class)
public class DigilockerFileDownloader {

    private static final int CONNECT_TIMEOUT_MS = (int) Duration.ofSeconds(10).toMillis();
    private static final int READ_TIMEOUT_MS = (int) Duration.ofSeconds(30).toMillis();

    public record DownloadedFile(byte[] bytes, String mimeType, String sha256) {}

    @FunctionalInterface
    public interface StreamOpener {
        OpenedStream open(URI uri) throws Exception;
    }

    public record OpenedStream(InputStream stream, String contentType, int statusCode) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private final SurepassProperties props;
    private final StreamOpener opener;

    @Autowired
    public DigilockerFileDownloader(SurepassProperties props) {
        this(props, DigilockerFileDownloader::openHttpUrlConnection);
    }

    public DigilockerFileDownloader(SurepassProperties props, StreamOpener opener) {
        this.props = props;
        this.opener = opener;
    }

    public DownloadedFile download(String downloadUrl, String expectedMime) {
        if (!StringUtils.hasText(downloadUrl)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DOWNLOAD_URL",
                    "Document download URL is empty");
        }
        String trimmed = downloadUrl.trim();
        if (trimmed.startsWith("mock://")) {
            byte[] mockBytes;
            if (trimmed.endsWith("pan.xml")) {
                mockBytes = ("<Certificate language=\"99\" name=\"PAN Verification Record\" type=\"PANCR\" number=\"ABCDE1234F\">"
                        + "<IssuedTo><Person name=\"Mock Partner\"/></IssuedTo>"
                        + "<CertificateData><PAN num=\"ABCDE1234F\" verifiedOn=\"03-11-2026\"/></CertificateData>"
                        + "</Certificate>").getBytes(StandardCharsets.UTF_8);
            } else if (trimmed.endsWith(".xml")) {
                mockBytes = "<Certificate><IssuedTo><Person name=\"Mock Partner\"/></IssuedTo></Certificate>".getBytes(StandardCharsets.UTF_8);
            } else {
                mockBytes = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
            }
            String mime = StringUtils.hasText(expectedMime)
                    ? expectedMime
                    : (trimmed.endsWith(".xml") ? "application/xml" : "application/pdf");
            return new DownloadedFile(mockBytes, mime, sha256Hex(mockBytes));
        }

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DOWNLOAD_URL",
                    "Document download URL is malformed");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNSAFE_DOWNLOAD_SCHEME",
                    "Document download URL must use HTTPS");
        }

        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!isAllowedHost(host, props.effectiveDownloadHosts())) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNSAFE_DOWNLOAD_HOST",
                    "Document download host is not allowlisted: " + host);
        }

        long maxBytes = props.effectiveMaxDocBytes();
        byte[] bytes;
        String detectedType;
        try (OpenedStream opened = opener.open(uri)) {
            if (opened.statusCode() < 200 || opened.statusCode() >= 300) {
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "DOCUMENT_DOWNLOAD_HTTP_ERROR",
                        "Document download failed with HTTP " + opened.statusCode());
            }
            detectedType = opened.contentType();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int r;
            while ((r = opened.stream().read(buf)) != -1) {
                total += r;
                if (total > maxBytes) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_TOO_LARGE",
                            "DigiLocker document exceeds maximum allowed size");
                }
                out.write(buf, 0, r);
            }
            bytes = out.toByteArray();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "DOCUMENT_DOWNLOAD_FAILED",
                    "Failed to download DigiLocker document");
        }

        if (bytes.length == 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_EMPTY",
                    "Downloaded DigiLocker document is empty");
        }

        String finalMime = resolveMime(expectedMime, detectedType);
        if ("application/pdf".equalsIgnoreCase(finalMime) && !startsWithPdfMagic(bytes)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PDF_CONTENT",
                    "Downloaded PDF document failed header validation");
        }

        return new DownloadedFile(bytes, finalMime, sha256Hex(bytes));
    }

    private static OpenedStream openHttpUrlConnection(URI uri) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        return new OpenedStream(in, conn.getContentType(), code);
    }

    private static boolean isAllowedHost(String host, List<String> allowedSuffixes) {
        if (host.isEmpty()) {
            return false;
        }
        for (String suffix : allowedSuffixes) {
            if (suffix == null || suffix.isBlank()) {
                continue;
            }
            String clean = suffix.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith(".")) {
                clean = clean.substring(1);
            }
            if (host.equals(clean) || host.endsWith("." + clean)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveMime(String expectedMime, String responseContentType) {
        String candidate = StringUtils.hasText(expectedMime) ? expectedMime : responseContentType;
        if (!StringUtils.hasText(candidate)) {
            return "application/octet-stream";
        }
        String clean = candidate.trim().toLowerCase(Locale.ROOT);
        int semi = clean.indexOf(';');
        if (semi >= 0) {
            clean = clean.substring(0, semi).trim();
        }
        return clean.isEmpty() ? "application/octet-stream" : clean;
    }

    private static boolean startsWithPdfMagic(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-';
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

