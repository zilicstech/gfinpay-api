package com.fintech.storage;

import com.fintech.platform.web.ApiException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@EnableConfigurationProperties(SpacesProperties.class)
public class SpacesStorage {

    private static final Logger log = LoggerFactory.getLogger(SpacesStorage.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long MAX_BYTES = 8 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp");

    private final SpacesProperties props;
    private final S3Client s3;
    private final S3Presigner presigner;

    public SpacesStorage(SpacesProperties props) {
        this.props = props;
        if (!configured()) {
            this.s3 = null;
            this.presigner = null;
            return;
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        URI endpoint = URI.create(props.endpoint());
        Region region = Region.of(StringUtils.hasText(props.region()) ? props.region() : "sgp1");
        S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
    }

    public record StoredObject(String key, String filename, String contentType) {}

    public StoredObject upload(String kind, MultipartFile file) {
        requireConfigured();
        if (file == null || file.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PROOF_REQUIRED",
                    "Upload a payment proof (PDF or image)");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PROOF_TOO_LARGE",
                    "Payment proof must be 8 MB or smaller");
        }
        String contentType = normalizeType(file.getContentType());
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PROOF_TYPE",
                    "Use a PDF, JPG, PNG, or WebP file");
        }
        String original = file.getOriginalFilename() == null ? "proof" : file.getOriginalFilename();
        String safe = original.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "proof";
        }
        String day = LocalDate.now(IST).toString();
        String folder = StringUtils.hasText(props.prefix()) ? props.prefix().replaceAll("^/+|/+$", "") : "dev";
        String key = folder + "/" + day + "/" + kind.toLowerCase(Locale.ROOT) + "/" + UUID.randomUUID() + "-" + safe;
        try {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength(file.getSize())
                    .build(), RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (Exception e) {
            log.error("SPACES_UPLOAD_FAILED key={}", key, e);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PROOF_UPLOAD_FAILED",
                    "Could not store the payment proof. Try again.");
        }
        log.info("SPACES_UPLOAD key={} bytes={}", key, file.getSize());
        return new StoredObject(key, original, contentType);
    }

    public String presignedGet(String key) {
        requireConfigured();
        if (!StringUtils.hasText(key)) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "PROOF_NOT_FOUND", "No payment proof on this request");
        }
        GetObjectRequest get = GetObjectRequest.builder().bucket(props.bucket()).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(get)
                .build()).url().toString();
    }

    private boolean configured() {
        return StringUtils.hasText(props.accessKey()) && StringUtils.hasText(props.secretKey())
                && StringUtils.hasText(props.bucket()) && StringUtils.hasText(props.endpoint());
    }

    private void requireConfigured() {
        if (s3 == null || presigner == null) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "SPACES_NOT_CONFIGURED",
                    "File storage is not configured");
        }
    }

    private static String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "application/octet-stream";
        }
        String type = raw.trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(type)) {
            return "image/jpeg";
        }
        return type;
    }
}
