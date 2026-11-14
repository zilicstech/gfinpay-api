package com.fintech.agreement.esign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(prefix = "app.esign", name = "mode", havingValue = "live", matchIfMissing = true)
@EnableConfigurationProperties(EsignProperties.class)
public class SurepassEsignClient implements EsignClient {

    private static final Logger log = LoggerFactory.getLogger(SurepassEsignClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EsignProperties props;
    private final RestClient rest;
    private final RestClient downloadClient;

    @Autowired
    public SurepassEsignClient(EsignProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        this.rest = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
        this.downloadClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    public SurepassEsignClient(EsignProperties props, RestClient rest, RestClient downloadClient) {
        this.props = props;
        this.rest = rest;
        this.downloadClient = downloadClient;
    }

    @Override
    public String mode() {
        return "live";
    }

    @Override
    @SuppressWarnings("unchecked")
    public EsignUpload uploadDocument(String fileName, byte[] pdfBytes) {
        requireToken();
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ESIGN_DOCUMENT_EMPTY",
                    "Cannot upload an empty agreement PDF to Surepass eSign");
        }
        String safeName = StringUtils.hasText(fileName) ? fileName : "agreement.pdf";

        ByteArrayResource fileResource = new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", fileResource);
        form.add("file_name", safeName);

        try {
            Map<String, Object> res = rest.post()
                    .uri("/api/v1/files/esign/upload")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken().trim())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            boolean success = res != null && Boolean.TRUE.equals(res.get("success"));
            Map<String, Object> data = res != null && res.get("data") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            boolean uploaded = Boolean.TRUE.equals(data.get("uploaded"));
            String fileId = str(data.get("file_id"));
            String clientId = str(data.get("client_id"));

            log.info("SUREPASS_ESIGN_UPLOAD fileName={} status={} messageCode={} fileId={} uploaded={}",
                    safeName, res != null ? res.get("status_code") : null,
                    res != null ? res.get("message_code") : null, fileId, uploaded);

            if (!success || !uploaded || !StringUtils.hasText(fileId)) {
                String msgCode = res != null ? str(res.get("message_code")) : "";
                String msg = res != null ? str(res.get("message")) : "Surepass eSign upload failed";
                if ("unauthorized".equalsIgnoreCase(msgCode) || (res != null && Integer.valueOf(401).equals(res.get("status_code")))) {
                    throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_AUTH_FAILED",
                            "Surepass eSign authentication failed (check APP_ESIGN_TOKEN)");
                }
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_UPLOAD_FAILED",
                        StringUtils.hasText(msg) ? msg : "Document upload to Surepass eSign failed");
            }

            return new EsignUpload(fileId, StringUtils.hasText(clientId) ? clientId : fileId);
        } catch (RestClientResponseException e) {
            throw translateHttpError("UPLOAD", e, "ESIGN_UPLOAD_FAILED");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("SUREPASS_ESIGN_UPLOAD_ERROR fileName={}", safeName, e);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_UPLOAD_FAILED",
                    "Failed to upload agreement PDF to Surepass eSign: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public EsignSession initialize(EsignInitRequest req) {
        requireToken();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("reason", StringUtils.hasText(req.reason()) ? req.reason() : "Agreement signing");
        config.put("positions", req.positions() != null ? req.positions() : Map.of());
        config.put("accept_virtual_sign", false);
        config.put("allow_virtual_sign_upload", false);
        config.put("track_location", true);
        config.put("allow_download", true);
        config.put("allow_number_change", true);
        config.put("skip_otp", false);
        config.put("skip_email", true);
        config.put("always_send_email", false);
        config.put("skip_main_screen", false);
        config.put("enforce_geo_location", false);
        config.put("name_match", Map.of(
                "match", true,
                "score", req.nameMatchScore() > 0 ? req.nameMatchScore() : props.nameMatchScore()
        ));

        Map<String, Object> prefill = new LinkedHashMap<>();
        prefill.put("full_name", StringUtils.hasText(req.signerFullName()) ? req.signerFullName() : "");
        prefill.put("mobile_number", StringUtils.hasText(req.signerMobile()) ? req.signerMobile() : "");
        prefill.put("user_email", StringUtils.hasText(req.signerEmail()) && !"—".equals(req.signerEmail()) ? req.signerEmail() : "");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_id", req.fileId());
        payload.put("send_email", false);
        payload.put("pdf_pre_uploaded", true);
        if (isPublicRedirectUrl(req.redirectUrl())) {
            payload.put("redirect_url", req.redirectUrl().trim());
        }
        payload.put("config", config);
        payload.put("prefill_options", prefill);
        payload.put("sign_type", "aadhaar");

        try {
            Map<String, Object> res = rest.post()
                    .uri("/api/v1/esign/initialize")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);

            boolean success = res != null && Boolean.TRUE.equals(res.get("success"));
            Map<String, Object> data = res != null && res.get("data") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            String clientId = str(data.get("client_id"));
            String token = str(data.get("token"));
            String url = str(data.get("url"));
            if (StringUtils.hasText(url)) {
                url = url.replace(" ", "");
            }

            log.info("SUREPASS_ESIGN_INIT fileId={} status={} messageCode={} clientId={}",
                    req.fileId(), res != null ? res.get("status_code") : null,
                    res != null ? res.get("message_code") : null, clientId);

            if (!success || !StringUtils.hasText(clientId) || !StringUtils.hasText(url)) {
                String msgCode = res != null ? str(res.get("message_code")) : "";
                String msg = res != null ? str(res.get("message")) : "Surepass eSign initialization failed";
                if ("unauthorized".equalsIgnoreCase(msgCode) || (res != null && Integer.valueOf(401).equals(res.get("status_code")))) {
                    throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_AUTH_FAILED",
                            "Surepass eSign authentication failed (check APP_ESIGN_TOKEN)");
                }
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_INIT_FAILED",
                        StringUtils.hasText(msg) ? msg : "Failed to initialize Surepass eSign session");
            }

            return new EsignSession(clientId, token, url);
        } catch (RestClientResponseException e) {
            throw translateHttpError("INIT", e, "ESIGN_INIT_FAILED");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("SUREPASS_ESIGN_INIT_ERROR fileId={}", req.fileId(), e);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_INIT_FAILED",
                    "Failed to initialize Surepass eSign session: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public EsignStatus status(String clientId) {
        requireToken();
        try {
            Map<String, Object> res = rest.get()
                    .uri("/api/v1/esign/status/{clientId}", clientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken().trim())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);

            boolean success = res != null && Boolean.TRUE.equals(res.get("success"));
            String msgCode = res != null ? str(res.get("message_code")) : "";
            Integer statusCode = res != null && res.get("status_code") instanceof Number n ? n.intValue() : null;

            if (!success) {
                if ("client_id_not_found".equalsIgnoreCase(msgCode) || Integer.valueOf(404).equals(statusCode)) {
                    return new EsignStatus(clientId, "client_id_not_found", Phase.NOT_FOUND,
                            "eSign session not found — please start again");
                }
                if ("unauthorized".equalsIgnoreCase(msgCode) || Integer.valueOf(401).equals(statusCode)) {
                    throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_AUTH_FAILED",
                            "Surepass eSign authentication failed");
                }
            }

            Map<String, Object> data = res != null && res.get("data") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            String rawStatus = str(data.get("status"));
            log.info("SUREPASS_ESIGN_STATUS clientId={} rawStatus={} messageCode={}", clientId, rawStatus, msgCode);
            return mapStatus(clientId, rawStatus);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getResponseBodyAsString().contains("client_id_not_found")) {
                return new EsignStatus(clientId, "client_id_not_found", Phase.NOT_FOUND,
                        "eSign session not found — please start again");
            }
            throw translateHttpError("STATUS", e, "ESIGN_STATUS_FAILED");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("SUREPASS_ESIGN_STATUS_ERROR clientId={}", clientId, e);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_STATUS_FAILED",
                    "Failed to check Surepass eSign status: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String signedDocumentUrl(String clientId) {
        requireToken();
        try {
            Map<String, Object> res = rest.get()
                    .uri("/api/v1/esign/get-signed-document/{clientId}", clientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken().trim())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);

            boolean success = res != null && Boolean.TRUE.equals(res.get("success"));
            String msgCode = res != null ? str(res.get("message_code")) : "";
            Integer statusCode = res != null && res.get("status_code") instanceof Number n ? n.intValue() : null;
            Map<String, Object> data = res != null && res.get("data") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            String url = str(data.get("url"));

            log.info("SUREPASS_ESIGN_SIGNED_DOC clientId={} status={} messageCode={} hasUrl={}",
                    clientId, statusCode, msgCode, StringUtils.hasText(url));

            if (!success || !StringUtils.hasText(url)) {
                if ("not_found".equalsIgnoreCase(msgCode) || Integer.valueOf(404).equals(statusCode)) {
                    throw ApiException.of(HttpStatus.NOT_FOUND, "ESIGN_SIGNED_DOC_NOT_READY",
                            "Signed document is not ready yet on Surepass");
                }
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_SIGNED_DOC_FAILED",
                        res != null && StringUtils.hasText(str(res.get("message")))
                                ? str(res.get("message")) : "Could not retrieve signed document link");
            }

            return url.trim();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getResponseBodyAsString().contains("not_found")) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "ESIGN_SIGNED_DOC_NOT_READY",
                        "Signed document is not ready yet on Surepass");
            }
            throw translateHttpError("SIGNED_DOC", e, "ESIGN_SIGNED_DOC_FAILED");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("SUREPASS_ESIGN_SIGNED_DOC_ERROR clientId={}", clientId, e);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_SIGNED_DOC_FAILED",
                    "Failed to retrieve signed document link: " + e.getMessage());
        }
    }

    @Override
    public byte[] download(String url) {
        if (!StringUtils.hasText(url)) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_DOWNLOAD_FAILED", "Signed document URL is blank");
        }
        try {
            byte[] bytes = downloadClient.get()
                    .uri(URI.create(url.trim()))
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length < 5) {
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_DOWNLOAD_FAILED",
                        "Downloaded signed document is empty");
            }
            String header = new String(bytes, 0, Math.min(bytes.length, 5), StandardCharsets.US_ASCII);
            if (!header.startsWith("%PDF")) {
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_INVALID_PDF",
                        "Downloaded file from Surepass is not a valid PDF");
            }
            return bytes;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("SUREPASS_ESIGN_DOWNLOAD_ERROR", e);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_DOWNLOAD_FAILED",
                    "Could not download signed PDF: " + e.getMessage());
        }
    }

    public static EsignStatus mapStatus(String clientId, String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return new EsignStatus(clientId, "unknown", Phase.IN_PROGRESS, "Waiting for signatory");
        }
        String norm = rawStatus.trim().toLowerCase();
        return switch (norm) {
            case "client_intiated", "client_initiated" ->
                    new EsignStatus(clientId, rawStatus, Phase.IN_PROGRESS, "Link generated — waiting for signatory");
            case "otp_sent" ->
                    new EsignStatus(clientId, rawStatus, Phase.IN_PROGRESS, "Aadhaar OTP sent to signatory");
            case "otp_verified" ->
                    new EsignStatus(clientId, rawStatus, Phase.IN_PROGRESS, "Aadhaar OTP verified");
            case "esign_started" ->
                    new EsignStatus(clientId, rawStatus, Phase.IN_PROGRESS, "Signing in progress");
            case "esign_completed" ->
                    new EsignStatus(clientId, rawStatus, Phase.COMPLETED, "eSign completed");
            case "esign_failed" ->
                    new EsignStatus(clientId, rawStatus, Phase.FAILED, "eSign failed — please retry");
            case "client_id_not_found" ->
                    new EsignStatus(clientId, rawStatus, Phase.NOT_FOUND, "eSign session not found — please start again");
            default -> {
                log.warn("SUREPASS_ESIGN_UNKNOWN_STATUS clientId={} rawStatus={}", clientId, rawStatus);
                yield new EsignStatus(clientId, rawStatus, Phase.IN_PROGRESS, "In progress (" + rawStatus + ")");
            }
        };
    }

    private void requireToken() {
        if (!StringUtils.hasText(props.apiToken())) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "ESIGN_NOT_CONFIGURED",
                    "Surepass eSign API token is not configured");
        }
    }

    @SuppressWarnings("unchecked")
    private ApiException translateHttpError(String step, RestClientResponseException e, String fallbackCode) {
        int code = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        String message = "Surepass eSign error (" + code + ")";
        String messageCode = "";
        try {
            if (StringUtils.hasText(body)) {
                Map<String, Object> parsed = MAPPER.readValue(body, Map.class);
                if (StringUtils.hasText(str(parsed.get("message")))) {
                    message = str(parsed.get("message"));
                }
                messageCode = str(parsed.get("message_code"));
            }
        } catch (Exception ignored) {
        }
        log.warn("SUREPASS_ESIGN_{}_HTTP_ERROR status={} messageCode={} message={}", step, code, messageCode, message);
        if (code == 401 || "unauthorized".equalsIgnoreCase(messageCode)) {
            return ApiException.of(HttpStatus.BAD_GATEWAY, "ESIGN_AUTH_FAILED",
                    "Surepass eSign authentication failed: " + message);
        }
        return ApiException.of(HttpStatus.BAD_GATEWAY, fallbackCode, message);
    }

    /**
     * AWS WAF in front of kyc-api.surepass.app blocks requests whose JSON body contains
     * loopback URLs (localhost / 127.0.0.1 / 0.0.0.0) in redirect_url with HTTP 403.
     * Only include redirect_url when it is a publicly routable HTTP(S) URL.
     */
    private static boolean isPublicRedirectUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String lower = url.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        return !lower.contains("://localhost")
                && !lower.contains("://127.0.0.1")
                && !lower.contains("://0.0.0.0")
                && !lower.contains("://[::1]");
    }

    private static String str(Object val) {
        if (val == null) return "";
        String s = String.valueOf(val).trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }
}

