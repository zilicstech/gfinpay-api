package com.fintech.onboarding;

import com.fintech.kyc.surepass.DigilockerFileDownloader;
import com.fintech.kyc.surepass.SurepassClient;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.web.ApiException;
import com.fintech.storage.SpacesStorage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DigilockerDocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(DigilockerDocumentFetcher.class);
    private static final int MAX_RETRIES = 3;
    private static final Pattern XML_PAN_NUM_PATTERN = Pattern.compile(
            "(?i)<PAN\\b[^>]*\\bnum\\s*=\\s*[\"']([A-Z]{5}[0-9]{4}[A-Z])[\"']");
    private static final Pattern XML_CERT_NUMBER_PATTERN = Pattern.compile(
            "(?i)<Certificate\\b[^>]*\\bnumber\\s*=\\s*[\"']([A-Z]{5}[0-9]{4}[A-Z])[\"']");
    private static final Pattern GENERIC_PAN_PATTERN = Pattern.compile(
            "(?<![A-Z0-9])([A-Z]{5}[0-9]{4}[A-Z])(?![A-Z0-9])");

    private final JdbcTemplate jdbc;
    private final SurepassClient surepass;
    private final DigilockerFileDownloader downloader;
    private final FieldCipher fieldCipher;
    private final SpacesStorage spaces;
    private final Executor executor;

    @Autowired
    public DigilockerDocumentFetcher(
            JdbcTemplate jdbc,
            SurepassClient surepass,
            DigilockerFileDownloader downloader,
            FieldCipher fieldCipher,
            SpacesStorage spaces,
            @Qualifier("digilockerExecutor") Executor executor
    ) {
        this.jdbc = jdbc;
        this.surepass = surepass;
        this.downloader = downloader;
        this.fieldCipher = fieldCipher;
        this.spaces = spaces;
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    public DigilockerDocumentFetcher(
            JdbcTemplate jdbc,
            SurepassClient surepass,
            DigilockerFileDownloader downloader,
            FieldCipher fieldCipher,
            SpacesStorage spaces
    ) {
        this(jdbc, surepass, downloader, fieldCipher, spaces, ForkJoinPool.commonPool());
    }

    public void upsertListedDocuments(
            UUID journeyId,
            UUID userId,
            String clientId,
            List<SurepassClient.DigilockerDocument> docs,
            List<String> mandatoryDocTypes
    ) {
        purgeSupersededClientDocuments(journeyId, userId, clientId);

        Set<String> mandatorySet = mandatoryDocTypes.stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        for (SurepassClient.DigilockerDocument doc : docs) {
            if (doc.fileId() == null || doc.fileId().isBlank()) {
                continue;
            }
            String docType = doc.docType() == null ? "UNKNOWN" : doc.docType().trim().toUpperCase(Locale.ROOT);
            String fileType = doc.fileType() == null ? "" : doc.fileType().trim().toLowerCase(Locale.ROOT);
            boolean mandatory = mandatorySet.contains(docType);

            jdbc.update("""
                    INSERT INTO digilocker_documents
                        (id, journey_id, user_id, client_id, source, file_id, doc_type, name, issuer,
                         description, file_type, mandatory, encrypted, fetch_status, review_status)
                    VALUES (?, ?, ?, ?, 'LIST_DOCUMENTS', ?, ?, ?, ?, ?, ?, ?, TRUE, 'PENDING', 'PENDING')
                    ON CONFLICT (client_id, file_id) DO UPDATE
                       SET journey_id  = EXCLUDED.journey_id,
                           user_id     = COALESCE(digilocker_documents.user_id, EXCLUDED.user_id),
                           doc_type    = EXCLUDED.doc_type,
                           name        = EXCLUDED.name,
                           issuer      = EXCLUDED.issuer,
                           description = EXCLUDED.description,
                           file_type   = EXCLUDED.file_type,
                           mandatory   = EXCLUDED.mandatory,
                           updated_at  = now()
                    """,
                    UUID.randomUUID(), journeyId, userId, clientId,
                    doc.fileId().trim(), docType, doc.name(), doc.issuer(),
                    doc.description(), fileType, mandatory);
        }
    }

    public void bindLatestClientToUser(UUID journeyId, UUID userId, String latestClientId) {
        if (userId == null) {
            return;
        }
        String resolvedClientId = (latestClientId == null || latestClientId.isBlank())
                ? resolveLatestClientIdForJourney(journeyId)
                : latestClientId.trim();
        if (resolvedClientId == null || resolvedClientId.isBlank()) {
            return;
        }
        purgeSupersededClientDocuments(journeyId, userId, resolvedClientId);
        jdbc.update("""
                UPDATE digilocker_documents
                   SET user_id = ?, updated_at = now()
                 WHERE journey_id = ? AND client_id = ?
                """, userId, journeyId, resolvedClientId);
    }

    public void purgeSupersededClientDocuments(UUID journeyId, UUID userId, String keepClientId) {
        if (keepClientId == null || keepClientId.isBlank()) {
            return;
        }
        String keep = keepClientId.trim();
        if (userId != null && journeyId != null) {
            jdbc.update("""
                    DELETE FROM digilocker_document_access_log
                     WHERE document_id IN (
                           SELECT id FROM digilocker_documents
                            WHERE client_id <> ?
                              AND (journey_id = ?
                                   OR user_id = ?
                                   OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                     )
                    """, keep, journeyId, userId, userId);
            int removed = jdbc.update("""
                    DELETE FROM digilocker_documents
                     WHERE client_id <> ?
                       AND (journey_id = ?
                            OR user_id = ?
                            OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                    """, keep, journeyId, userId, userId);
            if (removed > 0) {
                log.info("DIGILOCKER_SUPERSEDED_DOCS_PURGED journeyId={} userId={} keepClientId={} removed={}",
                        journeyId, userId, keep, removed);
            }
        } else if (journeyId != null) {
            jdbc.update("""
                    DELETE FROM digilocker_document_access_log
                     WHERE document_id IN (
                           SELECT id FROM digilocker_documents
                            WHERE journey_id = ? AND client_id <> ?
                     )
                    """, journeyId, keep);
            int removed = jdbc.update("""
                    DELETE FROM digilocker_documents
                     WHERE journey_id = ? AND client_id <> ?
                    """, journeyId, keep);
            if (removed > 0) {
                log.info("DIGILOCKER_SUPERSEDED_DOCS_PURGED journeyId={} keepClientId={} removed={}",
                        journeyId, keep, removed);
            }
        } else if (userId != null) {
            jdbc.update("""
                    DELETE FROM digilocker_document_access_log
                     WHERE document_id IN (
                           SELECT id FROM digilocker_documents
                            WHERE client_id <> ?
                              AND (user_id = ?
                                   OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                     )
                    """, keep, userId, userId);
            int removed = jdbc.update("""
                    DELETE FROM digilocker_documents
                     WHERE client_id <> ?
                       AND (user_id = ?
                            OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                    """, keep, userId, userId);
            if (removed > 0) {
                log.info("DIGILOCKER_SUPERSEDED_DOCS_PURGED userId={} keepClientId={} removed={}",
                        userId, keep, removed);
            }
        }
    }

    public String resolveLatestClientIdForUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        // 1. Prefer agent_kyc_profiles.aadhaar_ref_key (or identity_details->>'digilockerRef') if documents exist for it
        List<String> profileRefs = jdbc.queryForList("""
                SELECT COALESCE(NULLIF(BTRIM(aadhaar_ref_key), ''), NULLIF(BTRIM(identity_details->>'digilockerRef'), ''))
                  FROM agent_kyc_profiles
                 WHERE user_id = ?
                """, String.class, userId);
        if (!profileRefs.isEmpty() && profileRefs.get(0) != null && !profileRefs.get(0).isBlank()) {
            String ref = profileRefs.get(0).trim();
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*)::int
                      FROM digilocker_documents
                     WHERE client_id = ?
                       AND (user_id = ? OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                    """, Integer.class, ref, userId, userId);
            if (count != null && count > 0) {
                return ref;
            }
        }

        // 2. Next check the latest onboarding_journeys for this user
        List<String> journeyRefs = jdbc.queryForList("""
                SELECT COALESCE(NULLIF(BTRIM(identity->>'digilockerRef'), ''), NULLIF(BTRIM(identity->>'digilockerClientId'), ''))
                  FROM onboarding_journeys
                 WHERE user_id = ?
                 ORDER BY started_at DESC
                """, String.class, userId);
        for (String ref : journeyRefs) {
            if (ref != null && !ref.isBlank()) {
                Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*)::int
                          FROM digilocker_documents
                         WHERE client_id = ?
                           AND (user_id = ? OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                        """, Integer.class, ref.trim(), userId, userId);
                if (count != null && count > 0) {
                    return ref.trim();
                }
            }
        }

        // 3. Fallback to most recently created document's client_id for this user
        List<String> docClients = jdbc.queryForList("""
                SELECT client_id
                  FROM digilocker_documents
                 WHERE user_id = ?
                    OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?)
                 ORDER BY created_at DESC
                 LIMIT 1
                """, String.class, userId, userId);
        return docClients.isEmpty() ? null : docClients.get(0);
    }

    public String resolveLatestClientIdForJourney(UUID journeyId) {
        if (journeyId == null) {
            return null;
        }
        List<String> refs = jdbc.queryForList("""
                SELECT COALESCE(NULLIF(BTRIM(identity->>'digilockerRef'), ''), NULLIF(BTRIM(identity->>'digilockerClientId'), ''))
                  FROM onboarding_journeys
                 WHERE id = ?
                """, String.class, journeyId);
        if (!refs.isEmpty() && refs.get(0) != null && !refs.get(0).isBlank()) {
            return refs.get(0).trim();
        }
        List<String> docClients = jdbc.queryForList("""
                SELECT client_id
                  FROM digilocker_documents
                 WHERE journey_id = ?
                 ORDER BY created_at DESC
                 LIMIT 1
                """, String.class, journeyId);
        return docClients.isEmpty() ? null : docClients.get(0);
    }

    private boolean isLatestClientForJourney(UUID journeyId, String clientId) {
        if (journeyId == null || clientId == null || clientId.isBlank()) {
            return false;
        }
        String latest = resolveLatestClientIdForJourney(journeyId);
        return latest == null || latest.equals(clientId.trim());
    }

    public void storeAadhaarPhoto(UUID journeyId, UUID userId, String clientId, byte[] photoBytes) {
        if (photoBytes == null || photoBytes.length == 0) {
            return;
        }
        byte[] encrypted = fieldCipher.encryptBytes(photoBytes);
        String sha256 = DigilockerFileDownloader.sha256Hex(photoBytes);
        String filename = journeyId + "/AADHAAR_PHOTO-aadhaar_photo.jpg.enc";
        SpacesStorage.StoredObject stored = spaces.uploadBytes(
                "digilocker", filename, "application/octet-stream", encrypted);

        jdbc.update("""
                INSERT INTO digilocker_documents
                    (id, journey_id, user_id, client_id, source, file_id, doc_type, name, issuer,
                     description, file_type, mime_type, mandatory, storage_key, size_bytes, sha256,
                     encrypted, fetch_status, fetch_error, review_status)
                VALUES (?, ?, ?, ?, 'DOWNLOAD_AADHAAR', 'aadhaar_photo', 'AADHAAR_PHOTO',
                        'Aadhaar Photograph', 'Unique Identification Authority of India (UIDAI)',
                        'Photograph extracted from DigiLocker e-Aadhaar XML', 'jpg', 'image/jpeg',
                        TRUE, ?, ?, ?, TRUE, 'STORED', NULL, 'PENDING')
                ON CONFLICT (client_id, file_id) DO UPDATE
                   SET journey_id  = EXCLUDED.journey_id,
                       user_id     = COALESCE(digilocker_documents.user_id, EXCLUDED.user_id),
                       storage_key = EXCLUDED.storage_key,
                       size_bytes  = EXCLUDED.size_bytes,
                       sha256      = EXCLUDED.sha256,
                       fetch_status = 'STORED',
                       fetch_error = NULL,
                       updated_at  = now()
                """,
                UUID.randomUUID(), journeyId, userId, clientId,
                stored.key(), (long) photoBytes.length, sha256);
    }

    public void storeManualPanDocument(
            UUID journeyId,
            UUID userId,
            String clientId,
            byte[] fileBytes,
            String mimeType,
            String fileType
    ) {
        if (fileBytes == null || fileBytes.length == 0) {
            return;
        }
        String effectiveClientId = (clientId != null && !clientId.isBlank())
                ? clientId.trim()
                : resolveLatestClientIdForJourney(journeyId);
        if (effectiveClientId == null || effectiveClientId.isBlank()) {
            effectiveClientId = "manual_" + journeyId;
        }
        String cleanExt = resolveExtension(fileType, mimeType);
        String cleanMime = (mimeType != null && !mimeType.isBlank())
                ? mimeType.trim().toLowerCase(Locale.ROOT)
                : ("pdf".equals(cleanExt) ? "application/pdf" : "image/jpeg");
        byte[] encrypted = fieldCipher.encryptBytes(fileBytes);
        String sha256 = DigilockerFileDownloader.sha256Hex(fileBytes);
        String filename = journeyId + "/PANCR-manual_pan." + cleanExt + ".enc";
        SpacesStorage.StoredObject stored = spaces.uploadBytes(
                "digilocker", filename, "application/octet-stream", encrypted);

        jdbc.update("""
                INSERT INTO digilocker_documents
                    (id, journey_id, user_id, client_id, source, file_id, doc_type, name, issuer,
                     description, file_type, mime_type, mandatory, storage_key, size_bytes, sha256,
                     encrypted, fetch_status, fetch_error, review_status)
                VALUES (?, ?, ?, ?, 'MANUAL_UPLOAD', 'manual_pan', 'PANCR',
                        'PAN Card (Uploaded)', 'Income Tax Department (Manual Upload)',
                        'PAN card uploaded/captured during onboarding', ?, ?,
                        TRUE, ?, ?, ?, TRUE, 'STORED', NULL, 'PENDING')
                ON CONFLICT (client_id, file_id) DO UPDATE
                   SET journey_id    = EXCLUDED.journey_id,
                       user_id       = COALESCE(digilocker_documents.user_id, EXCLUDED.user_id),
                       file_type     = EXCLUDED.file_type,
                       mime_type     = EXCLUDED.mime_type,
                       storage_key   = EXCLUDED.storage_key,
                       size_bytes    = EXCLUDED.size_bytes,
                       sha256        = EXCLUDED.sha256,
                       fetch_status  = 'STORED',
                       fetch_error   = NULL,
                       review_status = 'PENDING',
                       updated_at    = now()
                """,
                UUID.randomUUID(), journeyId, userId, effectiveClientId,
                cleanExt, cleanMime, stored.key(), (long) fileBytes.length, sha256);
    }

    public String fetchAndExtractPanNumber(UUID journeyId, String clientId) {
        if (journeyId == null || clientId == null || clientId.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, file_id, doc_type, file_type, name, description, storage_key, fetch_status
                  FROM digilocker_documents
                 WHERE journey_id = ? AND client_id = ?
                   AND UPPER(doc_type) = 'PANCR'
                 ORDER BY CASE WHEN LOWER(COALESCE(file_type, '')) = 'xml' THEN 0 ELSE 1 END,
                          created_at ASC
                """, journeyId, clientId);
        if (rows.isEmpty()) {
            return null;
        }

        // 1. Prefer extracting from the PANCR XML document content
        for (Map<String, Object> row : rows) {
            String fileType = String.valueOf(row.getOrDefault("file_type", "")).toLowerCase(Locale.ROOT);
            String fileId = String.valueOf(row.getOrDefault("file_id", ""));
            if (!"xml".equals(fileType) && !"pan".equalsIgnoreCase(fileId)) {
                continue;
            }
            UUID docId = (UUID) row.get("id");
            String docType = String.valueOf(row.getOrDefault("doc_type", "PANCR"));
            String fetchStatus = String.valueOf(row.getOrDefault("fetch_status", "PENDING"));
            if (!"STORED".equalsIgnoreCase(fetchStatus)) {
                fetchOneWithRetries(docId, journeyId, clientId, fileId, docType, fileType);
            }
            List<String> keys = jdbc.queryForList("""
                    SELECT storage_key FROM digilocker_documents
                     WHERE id = ? AND fetch_status = 'STORED' AND storage_key IS NOT NULL
                    """, String.class, docId);
            if (!keys.isEmpty() && keys.get(0) != null && !keys.get(0).isBlank()) {
                try {
                    byte[] encBytes = spaces.getBytes(keys.get(0));
                    byte[] plainBytes = fieldCipher.decryptBytes(encBytes);
                    String xml = new String(plainBytes, StandardCharsets.UTF_8);
                    String extracted = extractPanFromText(xml);
                    if (extracted != null) {
                        log.info("DIGILOCKER_PAN_EXTRACTED_FROM_XML journeyId={} clientId={} panLast4={}",
                                journeyId, clientId, extracted.substring(6));
                        return extracted;
                    }
                } catch (Exception e) {
                    log.warn("DIGILOCKER_PAN_XML_PARSE_FAILED journeyId={} clientId={} err={}",
                            journeyId, clientId, e.getMessage());
                }
            }
        }

        // 2. Fallback: check file_id / name / description of PANCR rows (e.g. PANCR-PANCR-EYAPS7027F)
        for (Map<String, Object> row : rows) {
            for (String candidateField : List.of("file_id", "name", "description")) {
                Object raw = row.get(candidateField);
                if (raw != null) {
                    String extracted = extractPanFromText(String.valueOf(raw));
                    if (extracted != null) {
                        log.info("DIGILOCKER_PAN_EXTRACTED_FROM_METADATA journeyId={} clientId={} field={} panLast4={}",
                                journeyId, clientId, candidateField, extracted.substring(6));
                        return extracted;
                    }
                }
            }
        }
        return null;
    }

    public static String extractPanFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m1 = XML_PAN_NUM_PATTERN.matcher(text);
        if (m1.find()) {
            return m1.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher m2 = XML_CERT_NUMBER_PATTERN.matcher(text);
        if (m2.find()) {
            return m2.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher m3 = GENERIC_PAN_PATTERN.matcher(text.toUpperCase(Locale.ROOT));
        while (m3.find()) {
            String candidate = m3.group(1);
            if (!"PANCR".equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public void fetchMandatoryPdfsSynchronously(UUID journeyId, String clientId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, file_id, doc_type, file_type
                  FROM digilocker_documents
                 WHERE journey_id = ? AND client_id = ?
                   AND source = 'LIST_DOCUMENTS'
                   AND mandatory = TRUE
                   AND (file_type = 'pdf' OR file_type = '')
                   AND fetch_status <> 'STORED'
                 ORDER BY created_at ASC
                """, journeyId, clientId);

        if (rows.isEmpty()) {
            return;
        }

        List<CompletableFuture<Map.Entry<String, Boolean>>> futures = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            UUID docId = (UUID) row.get("id");
            String fileId = String.valueOf(row.get("file_id"));
            String docType = String.valueOf(row.get("doc_type"));
            String fileType = String.valueOf(row.get("file_type"));
            futures.add(CompletableFuture.supplyAsync(
                    () -> Map.entry(docType, fetchOneWithRetries(docId, journeyId, clientId, fileId, docType, fileType)),
                    executor
            ));
        }

        for (CompletableFuture<Map.Entry<String, Boolean>> future : futures) {
            Map.Entry<String, Boolean> res = future.join();
            if (!Boolean.TRUE.equals(res.getValue())) {
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "DIGILOCKER_DOCUMENT_FETCH_FAILED",
                        "We could not save your mandatory " + res.getKey() + " document from DigiLocker. Please tap Retry.");
            }
        }
    }

    @Async("digilockerExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDocumentsRequested(DigilockerDocumentsRequested event) {
        fetchRemainingForJourney(event.journeyId(), event.clientId());
    }

    public int fetchRemainingForJourney(UUID journeyId, String clientId) {
        if (!isLatestClientForJourney(journeyId, clientId)) {
            log.info("DIGILOCKER_SKIP_SUPERSEDED_FETCH journeyId={} supersededClientId={}", journeyId, clientId);
            return 0;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, file_id, doc_type, file_type
                  FROM digilocker_documents
                 WHERE journey_id = ? AND client_id = ?
                   AND source = 'LIST_DOCUMENTS'
                   AND fetch_status <> 'STORED'
                 ORDER BY mandatory DESC, created_at ASC
                """, journeyId, clientId);

        if (rows.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<Boolean>> futures = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            UUID docId = (UUID) row.get("id");
            String fileId = String.valueOf(row.get("file_id"));
            String docType = String.valueOf(row.get("doc_type"));
            String fileType = String.valueOf(row.get("file_type"));
            futures.add(CompletableFuture.supplyAsync(
                    () -> fetchOneWithRetries(docId, journeyId, clientId, fileId, docType, fileType),
                    executor
            ));
        }

        int storedCount = 0;
        for (CompletableFuture<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.join())) {
                storedCount++;
            }
        }
        return storedCount;
    }

    public int refetchForUser(UUID userId) {
        String latestClientId = resolveLatestClientIdForUser(userId);
        if (latestClientId == null || latestClientId.isBlank()) {
            return 0;
        }
        purgeSupersededClientDocuments(null, userId, latestClientId);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, journey_id, client_id, file_id, doc_type, file_type
                  FROM digilocker_documents
                 WHERE client_id = ?
                   AND (user_id = ? OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                   AND source = 'LIST_DOCUMENTS'
                   AND fetch_status <> 'STORED'
                 ORDER BY mandatory DESC, created_at ASC
                """, latestClientId, userId, userId);

        if (rows.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<Boolean>> futures = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            UUID docId = (UUID) row.get("id");
            UUID journeyId = (UUID) row.get("journey_id");
            String clientId = String.valueOf(row.get("client_id"));
            String fileId = String.valueOf(row.get("file_id"));
            String docType = String.valueOf(row.get("doc_type"));
            String fileType = String.valueOf(row.get("file_type"));
            futures.add(CompletableFuture.supplyAsync(
                    () -> fetchOneWithRetries(docId, journeyId, clientId, fileId, docType, fileType),
                    executor
            ));
        }

        int storedCount = 0;
        for (CompletableFuture<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.join())) {
                storedCount++;
            }
        }
        return storedCount;
    }

    public boolean fetchOneWithRetries(
            UUID docId,
            UUID journeyId,
            String clientId,
            String fileId,
            String docType,
            String fileType
    ) {
        if (!isLatestClientForJourney(journeyId, clientId)) {
            return false;
        }
        String lastError = "FETCH_FAILED";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (!isLatestClientForJourney(journeyId, clientId)) {
                return false;
            }
            try {
                SurepassClient.DigilockerDownloadLink link = surepass.digilockerDownloadDocument(clientId, fileId);
                DigilockerFileDownloader.DownloadedFile downloaded = downloader.download(link.downloadUrl(), link.mimeType());
                byte[] encrypted = fieldCipher.encryptBytes(downloaded.bytes());
                String ext = resolveExtension(fileType, downloaded.mimeType());
                String safeFileId = fileId.replaceAll("[^A-Za-z0-9._-]", "_");
                String filename = journeyId + "/" + docType + "-" + safeFileId + "." + ext + ".enc";
                SpacesStorage.StoredObject stored = spaces.uploadBytes(
                        "digilocker", filename, "application/octet-stream", encrypted);

                int updated = jdbc.update("""
                        UPDATE digilocker_documents
                           SET mime_type = ?,
                               storage_key = ?,
                               size_bytes = ?,
                               sha256 = ?,
                               encrypted = TRUE,
                               fetch_status = 'STORED',
                               fetch_error = NULL,
                               updated_at = now()
                         WHERE id = ? AND client_id = ?
                        """,
                        downloaded.mimeType(), stored.key(), (long) downloaded.bytes().length,
                        downloaded.sha256(), docId, clientId);
                if (updated > 0) {
                    log.info("DIGILOCKER_DOC_STORED docId={} clientId={} fileId={} docType={} bytes={}",
                            docId, clientId, fileId, docType, downloaded.bytes().length);
                    return true;
                }
                return false;
            } catch (ApiException e) {
                lastError = e.getCode();
                log.warn("DIGILOCKER_DOC_FETCH_ATTEMPT_FAILED attempt={}/{} clientId={} fileId={} docType={} code={}",
                        attempt, MAX_RETRIES, clientId, fileId, docType, e.getCode());
            } catch (Exception e) {
                lastError = "UNEXPECTED_ERROR";
                log.warn("DIGILOCKER_DOC_FETCH_ATTEMPT_FAILED attempt={}/{} clientId={} fileId={} docType={} err={}",
                        attempt, MAX_RETRIES, clientId, fileId, docType, e.getMessage());
            }
        }

        jdbc.update("""
                UPDATE digilocker_documents
                   SET fetch_status = 'FAILED',
                       fetch_error = ?,
                       updated_at = now()
                 WHERE id = ? AND client_id = ?
                """, lastError, docId, clientId);
        return false;
    }

    private static String resolveExtension(String fileType, String mimeType) {
        if (fileType != null && !fileType.isBlank()) {
            return fileType.trim().toLowerCase(Locale.ROOT);
        }
        if (mimeType != null) {
            String lower = mimeType.toLowerCase(Locale.ROOT);
            if (lower.contains("pdf")) {
                return "pdf";
            }
            if (lower.contains("xml")) {
                return "xml";
            }
            if (lower.contains("jpeg") || lower.contains("jpg")) {
                return "jpg";
            }
            if (lower.contains("png")) {
                return "png";
            }
        }
        return "bin";
    }
}

