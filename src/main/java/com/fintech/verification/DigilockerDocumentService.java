package com.fintech.verification;

import com.fintech.identity.AdminAccess;
import com.fintech.onboarding.DigilockerDocumentFetcher;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.storage.SpacesStorage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DigilockerDocumentService {

    public record DecryptedDocument(
            byte[] bytes,
            String mimeType,
            String filename
    ) {}

    private final JdbcTemplate jdbc;
    private final SpacesStorage spaces;
    private final FieldCipher fieldCipher;
    private final DigilockerDocumentFetcher documentFetcher;
    private final AdminAccess access;

    public DigilockerDocumentService(
            JdbcTemplate jdbc,
            SpacesStorage spaces,
            FieldCipher fieldCipher,
            DigilockerDocumentFetcher documentFetcher,
            AdminAccess access) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.fieldCipher = fieldCipher;
        this.documentFetcher = documentFetcher;
        this.access = access;
    }

    public List<Map<String, Object>> listDocumentsForUser(UUID userId) {
        String latestClientId = documentFetcher.resolveLatestClientIdForUser(userId);
        if (latestClientId == null || latestClientId.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id, d.source, d.doc_type, d.file_type, d.file_id, d.name, d.issuer, d.description,
                       d.mime_type, d.mandatory, d.size_bytes, d.fetch_status, d.fetch_error, d.updated_at,
                       d.review_status, d.reviewed_by, d.reviewed_at, d.review_note,
                       u.full_name AS reviewed_by_name
                  FROM digilocker_documents d
                  LEFT JOIN users u ON u.id = d.reviewed_by
                 WHERE d.client_id = ?
                   AND (
                        (UPPER(d.doc_type) = 'ADHAR' AND LOWER(d.file_type) = 'pdf')
                        OR UPPER(d.doc_type) = 'AADHAAR_PHOTO'
                        OR LOWER(d.file_id) = 'aadhaar_photo'
                        OR (UPPER(d.doc_type) = 'PANCR' AND LOWER(COALESCE(d.file_type, '')) <> 'xml')
                   )
                   AND (d.user_id = ?
                        OR d.journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                 ORDER BY
                       CASE d.doc_type
                            WHEN 'ADHAR' THEN 1
                            WHEN 'AADHAAR_PHOTO' THEN 2
                            WHEN 'PANCR' THEN 3
                            ELSE 4
                       END,
                       CASE d.file_type
                            WHEN 'pdf' THEN 1
                            WHEN 'jpg' THEN 2
                            ELSE 3
                       END,
                       d.created_at ASC
                """, latestClientId, userId, userId);

        return rows.stream().map(this::mapDocumentRow).toList();
    }

    public List<Map<String, Object>> listDocumentsForJourney(UUID journeyId) {
        if (journeyId == null) {
            return List.of();
        }
        String latestClientId = documentFetcher.resolveLatestClientIdForJourney(journeyId);
        if (latestClientId == null || latestClientId.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id, d.source, d.doc_type, d.file_type, d.file_id, d.name, d.issuer, d.description,
                       d.mime_type, d.mandatory, d.size_bytes, d.fetch_status, d.fetch_error, d.updated_at,
                       d.review_status, d.reviewed_by, d.reviewed_at, d.review_note,
                       u.full_name AS reviewed_by_name
                  FROM digilocker_documents d
                  LEFT JOIN users u ON u.id = d.reviewed_by
                 WHERE d.journey_id = ?
                   AND d.client_id = ?
                   AND (
                        (UPPER(d.doc_type) = 'ADHAR' AND LOWER(d.file_type) = 'pdf')
                        OR UPPER(d.doc_type) = 'AADHAAR_PHOTO'
                        OR LOWER(d.file_id) = 'aadhaar_photo'
                        OR (UPPER(d.doc_type) = 'PANCR' AND LOWER(COALESCE(d.file_type, '')) <> 'xml')
                   )
                 ORDER BY
                       CASE d.doc_type
                            WHEN 'ADHAR' THEN 1
                            WHEN 'AADHAAR_PHOTO' THEN 2
                            WHEN 'PANCR' THEN 3
                            ELSE 4
                       END,
                       CASE d.file_type
                            WHEN 'pdf' THEN 1
                            WHEN 'jpg' THEN 2
                            ELSE 3
                       END,
                       d.created_at ASC
                """, journeyId, latestClientId);
        return rows.stream().map(this::mapDocumentRow).toList();
    }

    private Map<String, Object> mapDocumentRow(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("source", r.get("source"));
        m.put("docType", r.get("doc_type"));
        m.put("fileType", r.get("file_type"));
        m.put("fileId", r.get("file_id"));
        m.put("name", r.get("name"));
        m.put("issuer", r.get("issuer"));
        m.put("description", r.get("description"));
        m.put("mimeType", r.get("mime_type"));
        m.put("mandatory", r.get("mandatory"));
        m.put("sizeBytes", r.get("size_bytes"));
        m.put("fetchStatus", r.get("fetch_status"));
        m.put("fetchError", r.get("fetch_error"));
        m.put("fetchedAt", r.get("updated_at"));
        m.put("reviewStatus", r.get("review_status"));
        m.put("reviewedBy", r.get("reviewed_by"));
        m.put("reviewedByName", r.get("reviewed_by_name"));
        m.put("reviewedAt", r.get("reviewed_at"));
        m.put("reviewNote", r.get("review_note"));
        return m;
    }

    public Map<String, Object> summarizeForUser(UUID userId) {
        List<Map<String, Object>> docs = listDocumentsForUser(userId);
        boolean aadhaarPdfStored = docs.stream().anyMatch(d ->
                "ADHAR".equalsIgnoreCase(String.valueOf(d.get("docType")))
                        && "pdf".equalsIgnoreCase(String.valueOf(d.get("fileType")))
                        && "STORED".equalsIgnoreCase(String.valueOf(d.get("fetchStatus"))));
        boolean panPdfStored = docs.stream().anyMatch(d ->
                "PANCR".equalsIgnoreCase(String.valueOf(d.get("docType")))
                        && !"xml".equalsIgnoreCase(String.valueOf(d.get("fileType")))
                        && "STORED".equalsIgnoreCase(String.valueOf(d.get("fetchStatus"))));
        boolean aadhaarPhotoStored = docs.stream().anyMatch(d ->
                ("AADHAAR_PHOTO".equalsIgnoreCase(String.valueOf(d.get("docType")))
                        || "aadhaar_photo".equalsIgnoreCase(String.valueOf(d.get("fileId"))))
                        && "STORED".equalsIgnoreCase(String.valueOf(d.get("fetchStatus"))));
        long storedCount = docs.stream()
                .filter(d -> "STORED".equalsIgnoreCase(String.valueOf(d.get("fetchStatus"))))
                .count();
        long approvedCount = docs.stream()
                .filter(d -> "APPROVED".equalsIgnoreCase(String.valueOf(d.get("reviewStatus"))))
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("aadhaarPdfStored", aadhaarPdfStored);
        summary.put("panPdfStored", panPdfStored);
        summary.put("aadhaarPhotoStored", aadhaarPhotoStored);
        summary.put("storedCount", storedCount);
        summary.put("approvedCount", approvedCount);
        summary.put("totalCount", docs.size());
        return summary;
    }

    public boolean hasMandatoryDocumentsStored(UUID userId) {
        List<Map<String, Object>> docs = listDocumentsForUser(userId);
        if (docs.isEmpty()) {
            // Legacy profiles created prior to DigiLocker vault migration
            return true;
        }
        boolean aadhaarPdf = docs.stream().anyMatch(d ->
                "ADHAR".equalsIgnoreCase(String.valueOf(d.get("docType")))
                        && "pdf".equalsIgnoreCase(String.valueOf(d.get("fileType")))
                        && "STORED".equalsIgnoreCase(String.valueOf(d.get("fetchStatus"))));
        boolean panPdf = docs.stream().anyMatch(d ->
                "PANCR".equalsIgnoreCase(String.valueOf(d.get("docType")))
                        && !"xml".equalsIgnoreCase(String.valueOf(d.get("fileType")))
                        && "STORED".equalsIgnoreCase(String.valueOf(d.get("fetchStatus"))));
        return aadhaarPdf && panPdf;
    }

    @Transactional
    public DecryptedDocument loadDecryptedDocument(AuthPrincipal me, UUID userId, UUID docId,
                                                   String ip, String userAgent) {
        requireAdmin(me);
        access.assertNetworkUser(userId);

        String latestClientId = documentFetcher.resolveLatestClientIdForUser(userId);
        Map<String, Object> row = null;
        if (latestClientId != null && !latestClientId.isBlank()) {
            try {
                row = jdbc.queryForMap("""
                        SELECT id, doc_type, file_type, storage_key, mime_type, fetch_status
                          FROM digilocker_documents
                         WHERE id = ?
                           AND client_id = ?
                           AND (user_id = ? OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                        """, docId, latestClientId, userId, userId);
            } catch (EmptyResultDataAccessException ignored) {
                row = null;
            }
        }
        if (row == null) {
            try {
                row = jdbc.queryForMap("""
                        SELECT id, doc_type, file_type, storage_key, mime_type, fetch_status
                          FROM digilocker_documents
                         WHERE id = ?
                           AND journey_id IN (
                               SELECT journey_id FROM business_signatories
                                WHERE user_id = ? AND active = TRUE AND journey_id IS NOT NULL
                           )
                        """, docId, userId);
            } catch (EmptyResultDataAccessException e) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "DigiLocker document not found");
            }
        }

        String fetchStatus = String.valueOf(row.get("fetch_status"));
        String storageKey = (String) row.get("storage_key");
        if (!"STORED".equals(fetchStatus) || storageKey == null || storageKey.isBlank()) {
            throw ApiException.of(HttpStatus.CONFLICT, "DOCUMENT_NOT_STORED",
                    "This DigiLocker document has not been fetched into encrypted storage yet");
        }

        byte[] encrypted = spaces.getBytes(storageKey);
        byte[] plaintext = fieldCipher.decryptBytes(encrypted);

        jdbc.update("""
                INSERT INTO digilocker_document_access_log
                    (document_id, accessed_by, action, ip, user_agent)
                VALUES (?, ?, 'VIEW', ?, ?)
                """, docId, me.userId(), ip, userAgent);

        String docType = String.valueOf(row.getOrDefault("doc_type", "DOC")).toUpperCase(Locale.ROOT);
        String fileType = String.valueOf(row.getOrDefault("file_type", "pdf")).toLowerCase(Locale.ROOT);
        String mimeType = row.get("mime_type") != null
                ? String.valueOf(row.get("mime_type"))
                : ("pdf".equals(fileType) ? "application/pdf"
                : ("jpg".equals(fileType) || "jpeg".equals(fileType) ? "image/jpeg" : "application/xml"));
        String filename = docType + "_" + docId + "." + fileType;
        return new DecryptedDocument(plaintext, mimeType, filename);
    }

    @Transactional
    public List<Map<String, Object>> reviewDocument(AuthPrincipal me, UUID userId, UUID docId,
                                                    boolean approve, String note,
                                                    String ip, String userAgent) {
        requireAdmin(me);
        access.assertNetworkUser(userId);

        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (!approve && cleanNote == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOTE_REQUIRED",
                    "Provide a reason when rejecting a DigiLocker document");
        }

        String latestClientId = documentFetcher.resolveLatestClientIdForUser(userId);
        String currentReviewStatus = null;
        boolean isSignatoryDoc = false;
        if (latestClientId != null && !latestClientId.isBlank()) {
            try {
                currentReviewStatus = jdbc.queryForObject("""
                        SELECT review_status
                          FROM digilocker_documents
                         WHERE id = ?
                           AND client_id = ?
                           AND (user_id = ? OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                        """, String.class, docId, latestClientId, userId, userId);
            } catch (EmptyResultDataAccessException ignored) {
                currentReviewStatus = null;
            }
        }
        if (currentReviewStatus == null) {
            try {
                currentReviewStatus = jdbc.queryForObject("""
                        SELECT review_status
                          FROM digilocker_documents
                         WHERE id = ?
                           AND journey_id IN (
                               SELECT journey_id FROM business_signatories
                                WHERE user_id = ? AND active = TRUE AND journey_id IS NOT NULL
                           )
                        """, String.class, docId, userId);
                isSignatoryDoc = true;
            } catch (EmptyResultDataAccessException e) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "DigiLocker document not found");
            }
        }

        if (currentReviewStatus != null && !"PENDING".equalsIgnoreCase(currentReviewStatus)) {
            throw ApiException.of(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_REVIEWED",
                    "This DigiLocker document has already been " + currentReviewStatus.toLowerCase(Locale.ROOT)
                            + " and cannot be modified");
        }

        String nextStatus = approve ? "APPROVED" : "REJECTED";
        int updated;
        if (isSignatoryDoc) {
            updated = jdbc.update("""
                    UPDATE digilocker_documents
                       SET review_status = ?,
                           reviewed_by = ?,
                           reviewed_at = now(),
                           review_note = ?,
                           updated_at = now()
                     WHERE id = ?
                       AND review_status = 'PENDING'
                       AND journey_id IN (
                           SELECT journey_id FROM business_signatories
                            WHERE user_id = ? AND active = TRUE AND journey_id IS NOT NULL
                       )
                    """, nextStatus, me.userId(), cleanNote, docId, userId);
        } else {
            updated = jdbc.update("""
                    UPDATE digilocker_documents
                       SET review_status = ?,
                           reviewed_by = ?,
                           reviewed_at = now(),
                           review_note = ?,
                           updated_at = now()
                     WHERE id = ?
                       AND client_id = ?
                       AND review_status = 'PENDING'
                       AND (user_id = ? OR journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = ?))
                    """, nextStatus, me.userId(), cleanNote, docId, latestClientId, userId, userId);
        }
        if (updated == 0) {
            throw ApiException.of(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_REVIEWED",
                    "This DigiLocker document has already been reviewed and cannot be modified");
        }

        jdbc.update("""
                INSERT INTO digilocker_document_access_log
                    (document_id, accessed_by, action, ip, user_agent)
                VALUES (?, ?, ?, ?, ?)
                """, docId, me.userId(), approve ? "APPROVE" : "REJECT", ip, userAgent);

        if (!isSignatoryDoc) {
            syncPersonalIdentityVerified(userId);
        }
        return listDocumentsForUser(userId);
    }

    public boolean syncPersonalIdentityVerified(UUID userId) {
        List<Map<String, Object>> profileRows = jdbc.queryForList("""
                SELECT identity_status, face_review_status
                  FROM agent_kyc_profiles
                 WHERE user_id = ?
                """, userId);
        if (profileRows.isEmpty()) {
            return false;
        }
        String identityStatus = String.valueOf(profileRows.get(0).getOrDefault("identity_status", "NOT_VERIFIED"));
        String faceStatus = String.valueOf(profileRows.get(0).getOrDefault("face_review_status", "PENDING"));
        List<Map<String, Object>> docs = listDocumentsForUser(userId);
        boolean allDocsApproved = !docs.isEmpty() && docs.stream()
                .allMatch(d -> "APPROVED".equalsIgnoreCase(String.valueOf(d.get("reviewStatus"))));
        boolean verified = "VERIFIED".equalsIgnoreCase(identityStatus)
                && "MATCHED".equalsIgnoreCase(faceStatus)
                && allDocsApproved;

        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET personal_identity_verified = ?,
                       personal_status = ?,
                       updated_at = now()
                 WHERE user_id = ?
                """,
                verified,
                verified ? "PERSONAL_VERIFIED" : "NOT_VERIFIED",
                userId);
        return verified;
    }

    public List<Map<String, Object>> refetchForUser(AuthPrincipal me, UUID userId) {
        requireAdmin(me);
        access.assertNetworkUser(userId);
        documentFetcher.refetchForUser(userId);
        return listDocumentsForUser(userId);
    }

    private static void requireAdmin(AuthPrincipal me) {
        if (me == null || !me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only platform administrators can access DigiLocker documents");
        }
    }
}
