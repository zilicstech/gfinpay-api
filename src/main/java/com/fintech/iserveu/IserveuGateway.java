package com.fintech.iserveu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Shared AES header and body for every iServeU call. */
@Component
public class IserveuGateway {

    private final IserveuProperties props;
    private final ObjectMapper mapper;
    private final IserveuAuditLogService audit;
    private final HttpClient http;

    public IserveuGateway(IserveuProperties props, ObjectMapper mapper, IserveuAuditLogService audit) {
        this.props = props;
        this.mapper = mapper;
        this.audit = audit;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public void noteNotSent(String rail, String message, Object plainBody) {
        if (audit != null) {
            audit.recordNotSent(rail, message, plainBody);
        }
    }

    public JsonNode post(String url, Object plainBody) throws Exception {
        byte[] key = IserveuCrypto.key(props.encKey());
        String plainJson = mapper.writeValueAsString(plainBody);
        String headerCipher = IserveuCrypto.encrypt(mapper.writeValueAsString(Map.of(
                "client_id", props.clientId(),
                "client_secret", props.clientSecret(),
                "epoch", String.valueOf(Instant.now().getEpochSecond()))), key);
        String requestData = mapper.writeValueAsString(Map.of(
                "RequestData", IserveuCrypto.encrypt(plainJson, key)));
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("pass_key", props.passKey())
                .header("header_secrets", headerCipher)
                .POST(HttpRequest.BodyPublishers.ofString(requestData))
                .build();
        long started = System.currentTimeMillis();
        Integer httpStatus = null;
        String rawResponse = null;
        String plainResponse = null;
        String error = null;
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            httpStatus = response.statusCode();
            rawResponse = response.body();
            JsonNode plain = readResponse(rawResponse, key);
            plainResponse = mapper.writeValueAsString(plain);
            return plain;
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            throw e;
        } finally {
            if (audit != null) {
                audit.recordOutbound(
                        url,
                        headerCipher,
                        plainJson,
                        requestData,
                        httpStatus,
                        rawResponse,
                        plainResponse,
                        error,
                        System.currentTimeMillis() - started);
            }
        }
    }

    private JsonNode readResponse(String raw, byte[] key) throws Exception {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        JsonNode root = mapper.readTree(raw);
        String encrypted = root.path("ResponseData").asText("");
        if (!encrypted.isBlank()) {
            return mapper.readTree(IserveuCrypto.decrypt(encrypted, key));
        }
        return root;
    }
}
