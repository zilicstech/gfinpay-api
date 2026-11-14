package com.fintech.agreement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.agreement.esign.EsignClient;
import com.fintech.agreement.esign.EsignProperties;
import com.fintech.agreement.esign.MockEsignClient;
import com.fintech.identity.AdminAccess;
import com.fintech.onboarding.JourneyTracker;
import com.fintech.onboarding.NameMatcher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PublicAppUrl;
import com.fintech.storage.SpacesStorage;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@EnableConfigurationProperties({AgreementProperties.class, EsignProperties.class})
public class AgreementService {

    private static final Logger log = LoggerFactory.getLogger(AgreementService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a 'IST'", Locale.ENGLISH);
    private static final long MAX_PDF_BYTES = 10L * 1024 * 1024;

    public record LoadedPdf(byte[] bytes, String filename) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgreementProperties agreementProps;
    private final EsignProperties esignProps;
    private final AgreementRenderer renderer;
    private final AgreementPartyBuilder partyBuilder;
    private final SpacesStorage spaces;
    private final AdminAccess access;
    private final JourneyTracker journeyTracker;
    private final EsignClient esignClient;
    private final String publicAppUrl;

    public AgreementService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AgreementProperties agreementProps,
            EsignProperties esignProps,
            AgreementRenderer renderer,
            AgreementPartyBuilder partyBuilder,
            SpacesStorage spaces,
            AdminAccess access,
            JourneyTracker journeyTracker,
            EsignClient esignClient,
            @Value("${app.public-app-url:http://localhost:3001}") String publicAppUrl) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.agreementProps = agreementProps;
        this.esignProps = esignProps;
        this.renderer = renderer;
        this.partyBuilder = partyBuilder;
        this.spaces = spaces;
        this.access = access;
        this.journeyTracker = journeyTracker;
        this.esignClient = esignClient;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
    }

    @Transactional
    public Map<String, Object> generate(
            AuthPrincipal me,
            UUID userId,
            BigDecimal onboardingFee,
            String ip,
            String userAgent) {
        requireAdmin(me);
        access.assertNetworkUser(userId);
        ensureKycProfile(userId);

        Map<String, Object> profile = loadKycProfile(userId);
        String businessStatus = text(profile.get("business_status"));
        boolean businessVerified = Boolean.TRUE.equals(profile.get("business_verified"))
                || "BUSINESS_VERIFIED".equals(businessStatus);
        if (!businessVerified) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_NOT_VERIFIED",
                    "Verify the partner's business registration before generating the Master Service Agreement");
        }

        // Supersede any existing non-VOID agreement (unless already SIGNED)
        Map<String, Object> existing = findLiveAgreementRow(userId);
        if (existing != null) {
            String curStatus = text(existing.get("status"));
            UUID existingId = (UUID) existing.get("id");
            if ("SIGNED".equals(curStatus)) {
                throw ApiException.of(HttpStatus.CONFLICT, "AGREEMENT_ALREADY_SIGNED",
                        "Partner already has a signed Master Service Agreement. Void it with a reason before generating a new one.");
            }
            jdbc.update("""
                    UPDATE partner_agreements
                       SET status = 'VOID',
                           voided_by = ?,
                           voided_at = now(),
                           void_reason = 'Superseded by regeneration',
                           updated_at = now()
                     WHERE id = ?
                    """, me.userId(), existingId);
            recordEvent(existingId, "VOIDED", me.userId(), "Superseded by regeneration",
                    Map.of("reason", "Superseded by regeneration", "ip", text(ip)));
        }

        long seq = jdbc.queryForObject("SELECT nextval('partner_agreement_no_seq')", Long.class);
        int year = LocalDate.now(IST).getYear();
        String agreementNo = String.format("GFP-MSA-%d-%05d", year, seq);

        BigDecimal effectiveFee = onboardingFee != null ? onboardingFee : agreementProps.defaultOnboardingFee();
        String adminName = loadUserFullName(me.userId());
        String draftStamp = "Generated by " + adminName + " on " + ZonedDateTime.now(IST).format(TS_FMT)
                + " (Status: DRAFT — Pending Release)";

        AgreementPartyBuilder.BuiltParty built = partyBuilder.build(userId, agreementNo, effectiveFee, draftStamp);
        AgreementRenderer.RenderedPdf rendered = renderer.render(built.agreementType(), built.templateValues());

        SpacesStorage.StoredObject stored = spaces.uploadBytes(
                "agreements/unsigned",
                agreementNo + "-unsigned.pdf",
                "application/pdf",
                rendered.bytes());

        UUID agreementId = UUID.randomUUID();
        Map<String, Object> partySnapshot = new LinkedHashMap<>(built.partySnapshot());
        partySnapshot.put("templateValues", built.templateValues());
        partySnapshot.put("onboardingFeeDisplay", AgreementPartyBuilder.formatOnboardingFee(effectiveFee));

        jdbc.update("""
                INSERT INTO partner_agreements (
                    id, agreement_no, user_id, agreement_type, template_version,
                    status, party_snapshot, onboarding_fee,
                    unsigned_key, unsigned_sha256, page_count,
                    signatory_id, signatory_name, signatory_mobile, signatory_is_self,
                    esign_mode, generated_by, generated_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    'DRAFT', ?::jsonb, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, now(), now(), now()
                )
                """,
                agreementId,
                agreementNo,
                userId,
                built.agreementType().name(),
                built.agreementType().templateVersion(),
                toJson(partySnapshot),
                effectiveFee,
                stored.key(),
                rendered.sha256(),
                rendered.pageCount(),
                built.signatoryId(),
                built.signatoryName(),
                built.signatoryMobile(),
                built.signatoryIsSelf(),
                built.signatoryIsSelf() ? "SIGN" : "SHARE",
                me.userId());

        syncKycAgreementColumns(userId, "DRAFT", agreementId, agreementNo, built.signatoryName(), null, null);
        recordEvent(agreementId, "GENERATED", me.userId(), "Generated draft agreement " + agreementNo,
                Map.of(
                        "agreementNo", agreementNo,
                        "agreementType", built.agreementType().name(),
                        "sha256", rendered.sha256(),
                        "pageCount", rendered.pageCount(),
                        "signatoryName", built.signatoryName(),
                        "signatoryIsSelf", built.signatoryIsSelf()));
        journeyTracker.recordUserMilestone(
                userId,
                JourneyTracker.AGREEMENT_GENERATED,
                "SUCCESS",
                Map.of("agreementNo", agreementNo, "agreementId", agreementId.toString()));

        return getForAdmin(me, userId);
    }

    @Transactional
    public Map<String, Object> release(AuthPrincipal me, UUID agreementId, String ip, String userAgent) {
        requireAdmin(me);
        Map<String, Object> row = requireAgreementById(agreementId);
        UUID userId = (UUID) row.get("user_id");
        access.assertNetworkUser(userId);

        String status = text(row.get("status"));
        if ("RELEASED".equals(status)) {
            ensureUploadedToSurepass(row, me.userId());
            return getForAdmin(me, userId);
        }
        if (!"DRAFT".equals(status)) {
            throw ApiException.of(HttpStatus.CONFLICT, "INVALID_AGREEMENT_STATE",
                    "Only a DRAFT agreement can be released (current status: " + status + ")");
        }

        String agreementNo = text(row.get("agreement_no"));
        AgreementType agreementType = AgreementType.valueOf(text(row.get("agreement_type")));
        Map<String, Object> partySnapshot = new LinkedHashMap<>(fromJson(text(row.get("party_snapshot_json"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> rawVars = partySnapshot.get("templateValues") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>(partySnapshot);

        String adminName = loadUserFullName(me.userId());
        String releaseStamp = "Released by " + adminName + " on " + ZonedDateTime.now(IST).format(TS_FMT);
        Map<String, String> renderVars = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawVars.entrySet()) {
            if (e.getValue() != null && !(e.getValue() instanceof Map<?, ?>)) {
                renderVars.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        renderVars.put("agreementNo", agreementNo);
        renderVars.put("releaseStamp", releaseStamp);
        partySnapshot.put("releaseStamp", releaseStamp);
        partySnapshot.put("templateValues", renderVars);

        AgreementRenderer.RenderedPdf rendered = renderer.render(agreementType, renderVars);
        SpacesStorage.StoredObject stored = spaces.uploadBytes(
                "agreements/unsigned",
                agreementNo + "-released.pdf",
                "application/pdf",
                rendered.bytes());

        // Pre-upload released PDF to Surepass (/api/v1/files/esign/upload) immediately on release
        EsignClient.EsignUpload upload = esignClient.uploadDocument(agreementNo + ".pdf", rendered.bytes());
        Map<String, Object> esignDetails = new LinkedHashMap<>();
        esignDetails.put("fileId", upload.fileId());
        esignDetails.put("uploadClientId", upload.clientId());
        esignDetails.put("uploadedAt", Instant.now().toString());

        jdbc.update("""
                UPDATE partner_agreements
                   SET status = 'RELEASED',
                       unsigned_key = ?,
                       unsigned_sha256 = ?,
                       page_count = ?,
                       party_snapshot = ?::jsonb,
                       esign_file_id = ?,
                       esign_details = ?::jsonb,
                       released_by = ?,
                       released_at = now(),
                       updated_at = now()
                 WHERE id = ?
                """,
                stored.key(),
                rendered.sha256(),
                rendered.pageCount(),
                toJson(partySnapshot),
                upload.fileId(),
                toJson(esignDetails),
                me.userId(),
                agreementId);

        syncKycAgreementColumns(
                userId,
                "RELEASED",
                agreementId,
                agreementNo,
                text(row.get("signatory_name")),
                upload.fileId(),
                null);
        recordEvent(agreementId, "RELEASED", me.userId(),
                releaseStamp + " · Uploaded to Surepass (file_id: " + upload.fileId() + ")",
                Map.of(
                        "agreementNo", agreementNo,
                        "sha256", rendered.sha256(),
                        "releaseStamp", releaseStamp,
                        "fileId", upload.fileId(),
                        "uploadClientId", upload.clientId()));
        journeyTracker.recordUserMilestone(
                userId,
                JourneyTracker.AGREEMENT_RELEASED,
                "SUCCESS",
                Map.of(
                        "agreementNo", agreementNo,
                        "agreementId", agreementId.toString(),
                        "fileId", upload.fileId(),
                        "uploadClientId", upload.clientId()));

        return getForAdmin(me, userId);
    }

    @Transactional
    public Map<String, Object> voidAgreement(
            AuthPrincipal me,
            UUID agreementId,
            String reason,
            String ip,
            String userAgent) {
        requireAdmin(me);
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_REQUIRED",
                    "Provide a reason to void the agreement");
        }
        Map<String, Object> row = requireAgreementById(agreementId);
        UUID userId = (UUID) row.get("user_id");
        access.assertNetworkUser(userId);

        String status = text(row.get("status"));
        if ("VOID".equals(status)) {
            return getForAdmin(me, userId);
        }

        jdbc.update("""
                UPDATE partner_agreements
                   SET status = 'VOID',
                       voided_by = ?,
                       voided_at = now(),
                       void_reason = ?,
                       esign_url = NULL,
                       updated_at = now()
                 WHERE id = ?
                """, me.userId(), cleanReason, agreementId);

        syncKycAgreementColumns(userId, "NOT_SIGNED", null, null, null, null, null);
        recordEvent(agreementId, "VOIDED", me.userId(), cleanReason,
                Map.of("reason", cleanReason, "previousStatus", status));

        return getForAdmin(me, userId);
    }

    @Transactional
    public Map<String, Object> startEsign(
            AuthPrincipal me,
            String latitude,
            String longitude,
            String ip,
            String userAgent) {
        requirePartner(me);
        UUID userId = me.userId();

        // Sync first if currently ESIGN_PENDING
        syncIfPendingForUser(userId, false);

        Map<String, Object> row = findLiveAgreementRow(userId);
        if (row == null || "DRAFT".equals(text(row.get("status")))) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AGREEMENT_NOT_RELEASED",
                    "Your Master Service Agreement has not been released by the compliance team yet");
        }

        UUID agreementId = (UUID) row.get("id");
        String status = text(row.get("status"));
        if ("SIGNED".equals(status)) {
            throw ApiException.of(HttpStatus.CONFLICT, "AGREEMENT_ALREADY_SIGNED",
                    "Your Master Service Agreement is already signed");
        }
        if ("UNDER_REVIEW".equals(status)) {
            throw ApiException.of(HttpStatus.CONFLICT, "PHYSICAL_UPLOAD_UNDER_REVIEW",
                    "Your uploaded signed agreement is currently under admin review");
        }
        if (!"RELEASED".equals(status) && !"ESIGN_PENDING".equals(status)) {
            throw ApiException.of(HttpStatus.CONFLICT, "INVALID_AGREEMENT_STATE",
                    "Cannot start eSign in state: " + status);
        }

        boolean signatoryIsSelf = Boolean.TRUE.equals(row.get("signatory_is_self"));
        String signatoryName = text(row.get("signatory_name"));
        String signatoryMobile = text(row.get("signatory_mobile"));
        Map<String, Object> partySnapshot = fromJson(text(row.get("party_snapshot_json")));
        String signatoryEmail = text(partySnapshot.get("partnerEmail"));
        String agreementNo = text(row.get("agreement_no"));
        int pageCount = row.get("page_count") instanceof Number n ? n.intValue() : 1;

        Instant initiatedAt = toInstant(row.get("esign_initiated_at"));
        Instant expiresAt = initiatedAt != null
                ? initiatedAt.plus(esignProps.sessionTtlHours(), ChronoUnit.HOURS)
                : null;
        String existingUrl = text(row.get("esign_url"));
        String existingClientId = text(row.get("esign_client_id"));
        String existingProviderStatus = text(row.get("esign_provider_status"));

        boolean reusable = "ESIGN_PENDING".equals(status)
                && !existingUrl.isBlank()
                && !existingClientId.isBlank()
                && expiresAt != null
                && expiresAt.isAfter(Instant.now().plusSeconds(60))
                && !"esign_failed".equalsIgnoreCase(existingProviderStatus)
                && !"client_id_not_found".equalsIgnoreCase(existingProviderStatus);

        if (reusable) {
            return buildEsignStartResponse(
                    signatoryIsSelf,
                    existingUrl,
                    existingClientId,
                    expiresAt,
                    signatoryName,
                    signatoryMobile,
                    getForPartner(me));
        }

        String existingFileId = text(row.get("esign_file_id"));
        Map<String, Object> existingEsignDetails = fromJson(text(row.get("esign_details_json")));
        String existingUploadClientId = text(existingEsignDetails.getOrDefault("uploadClientId", existingFileId));

        String fileIdToUse = existingFileId;
        String uploadClientIdToUse = existingUploadClientId;
        if (fileIdToUse.isBlank()) {
            byte[] unsignedPdf = spaces.getBytes(text(row.get("unsigned_key")));
            EsignClient.EsignUpload upload = esignClient.uploadDocument(agreementNo + ".pdf", unsignedPdf);
            fileIdToUse = upload.fileId();
            uploadClientIdToUse = upload.clientId();
        }
        String redirectUrl = (publicAppUrl.isBlank() ? "http://localhost:3001" : publicAppUrl)
                + "/esign/return?a=" + agreementId;
        Map<String, List<Map<String, Integer>>> positions = renderer.buildSignaturePositions(
                pageCount, Boolean.TRUE.equals(esignProps.signAllPages()));

        EsignClient.EsignSession session = esignClient.initialize(new EsignClient.EsignInitRequest(
                fileIdToUse,
                agreementId.toString(),
                agreementNo,
                "Partner Master Service Agreement (" + agreementNo + ")",
                redirectUrl,
                positions,
                esignProps.nameMatchScore(),
                signatoryName,
                signatoryMobile,
                signatoryEmail));

        Instant newExpiresAt = Instant.now().plus(esignProps.sessionTtlHours(), ChronoUnit.HOURS);
        String esignMode = signatoryIsSelf ? "SIGN" : "SHARE";
        BigDecimal lat = parseCoord(latitude);
        BigDecimal lng = parseCoord(longitude);

        Map<String, Object> esignDetails = new LinkedHashMap<>(existingEsignDetails);
        esignDetails.put("clientId", session.clientId());
        esignDetails.put("fileId", fileIdToUse);
        esignDetails.put("uploadClientId", uploadClientIdToUse.isBlank() ? fileIdToUse : uploadClientIdToUse);
        esignDetails.put("expiresAt", newExpiresAt.toString());
        esignDetails.put("mode", esignMode);
        esignDetails.put("redirectUrl", redirectUrl);

        jdbc.update("""
                UPDATE partner_agreements
                   SET status = 'ESIGN_PENDING',
                       sign_method = 'ESIGN',
                       esign_mode = ?,
                       esign_file_id = ?,
                       esign_client_id = ?,
                       esign_url = ?,
                       esign_provider_status = 'client_intiated',
                       esign_initiated_at = now(),
                       esign_last_checked_at = now(),
                       esign_details = ?::jsonb,
                       sign_latitude = COALESCE(?, sign_latitude),
                       sign_longitude = COALESCE(?, sign_longitude),
                       updated_at = now()
                 WHERE id = ?
                """,
                esignMode,
                fileIdToUse,
                session.clientId(),
                session.url(),
                toJson(esignDetails),
                lat,
                lng,
                agreementId);

        syncKycAgreementColumns(userId, "ESIGN_PENDING", agreementId, agreementNo, signatoryName, session.clientId(), "ESIGN");
        recordEvent(agreementId, "ESIGN_INITIATED", userId,
                "Initiated Aadhaar eSign (" + esignMode + " mode)",
                Map.of(
                        "clientId", session.clientId(),
                        "fileId", fileIdToUse,
                        "uploadClientId", uploadClientIdToUse.isBlank() ? fileIdToUse : uploadClientIdToUse,
                        "mode", esignMode,
                        "signatoryName", signatoryName,
                        "expiresAt", newExpiresAt.toString()));

        return buildEsignStartResponse(
                signatoryIsSelf,
                session.url(),
                session.clientId(),
                newExpiresAt,
                signatoryName,
                signatoryMobile,
                getForPartner(me));
    }

    @Transactional
    public Map<String, Object> recordLinkCopied(AuthPrincipal me, String ip, String userAgent) {
        requirePartner(me);
        Map<String, Object> row = findLiveAgreementRow(me.userId());
        if (row == null) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AGREEMENT_NOT_FOUND", "No active agreement found");
        }
        UUID agreementId = (UUID) row.get("id");
        recordEvent(agreementId, "ESIGN_LINK_COPIED", me.userId(),
                "Copied eSign link to share with authorized signatory",
                Map.of("signatoryName", text(row.get("signatory_name"))));
        return Map.of("copied", true);
    }

    @Transactional
    public Map<String, Object> refreshMyEsign(AuthPrincipal me, String ip, String userAgent) {
        requirePartner(me);
        syncIfPendingForUser(me.userId(), true);
        return getForPartner(me);
    }

    @Transactional
    public Map<String, Object> uploadSigned(
            AuthPrincipal me,
            MultipartFile file,
            String latitude,
            String longitude,
            String ip,
            String userAgent) {
        requirePartner(me);
        UUID userId = me.userId();

        if (file == null || file.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_REQUIRED",
                    "Upload the signed agreement PDF file");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_TOO_LARGE",
                    "Signed agreement PDF must be 10 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.of(HttpStatus.BAD_REQUEST, "PDF_READ_ERROR", "Could not read uploaded file");
        }
        if (!isPdfBytes(bytes)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PDF",
                    "Only valid PDF documents (.pdf) are accepted for signed agreements");
        }

        // If ESIGN_PENDING, sync first in case eSign already completed
        syncIfPendingForUser(userId, false);

        Map<String, Object> row = findLiveAgreementRow(userId);
        if (row == null || "DRAFT".equals(text(row.get("status")))) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AGREEMENT_NOT_RELEASED",
                    "Your Master Service Agreement has not been released yet");
        }
        UUID agreementId = (UUID) row.get("id");
        String status = text(row.get("status"));
        if ("SIGNED".equals(status)) {
            throw ApiException.of(HttpStatus.CONFLICT, "AGREEMENT_ALREADY_SIGNED",
                    "Your Master Service Agreement is already signed");
        }
        if (!"RELEASED".equals(status) && !"ESIGN_PENDING".equals(status) && !"UNDER_REVIEW".equals(status)) {
            throw ApiException.of(HttpStatus.CONFLICT, "INVALID_AGREEMENT_STATE",
                    "Cannot upload a signed agreement in state: " + status);
        }

        String agreementNo = text(row.get("agreement_no"));
        String sha256 = AgreementRenderer.sha256Hex(bytes);
        String originalFilename = file.getOriginalFilename() == null ? agreementNo + "-signed.pdf" : file.getOriginalFilename();
        SpacesStorage.StoredObject stored = spaces.uploadBytes(
                "agreements/signed",
                agreementNo + "-signed-physical.pdf",
                "application/pdf",
                bytes);

        BigDecimal lat = parseCoord(latitude);
        BigDecimal lng = parseCoord(longitude);

        jdbc.update("""
                UPDATE partner_agreements
                   SET status = 'UNDER_REVIEW',
                       sign_method = 'UPLOAD',
                       signed_key = ?,
                       signed_sha256 = ?,
                       signed_filename = ?,
                       signed_content_type = 'application/pdf',
                       sign_latitude = COALESCE(?, sign_latitude),
                       sign_longitude = COALESCE(?, sign_longitude),
                       review_note = NULL,
                       esign_url = NULL,
                       updated_at = now()
                 WHERE id = ?
                """,
                stored.key(),
                sha256,
                originalFilename,
                lat,
                lng,
                agreementId);

        syncKycAgreementColumns(
                userId,
                "UNDER_REVIEW",
                agreementId,
                agreementNo,
                text(row.get("signatory_name")),
                null,
                "UPLOAD");

        recordEvent(agreementId, "PHYSICAL_UPLOADED", userId,
                "Uploaded signed PDF (" + originalFilename + ") for admin review",
                Map.of(
                        "filename", originalFilename,
                        "bytes", bytes.length,
                        "sha256", sha256));

        return getForPartner(me);
    }

    @Transactional
    public Map<String, Object> review(
            AuthPrincipal me,
            UUID agreementId,
            boolean approve,
            String note,
            String ip,
            String userAgent) {
        requireAdmin(me);
        Map<String, Object> row = requireAgreementById(agreementId);
        UUID userId = (UUID) row.get("user_id");
        access.assertNetworkUser(userId);

        String status = text(row.get("status"));
        String cleanNote = note == null ? null : note.trim();
        if (cleanNote != null && cleanNote.isEmpty()) {
            cleanNote = null;
        }
        if (!approve && cleanNote == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOTE_REQUIRED",
                    "Provide a rejection reason so the partner knows what to fix");
        }
        if ("SIGNED".equals(status) && approve) {
            return getForAdmin(me, userId);
        }
        if (!"UNDER_REVIEW".equals(status)) {
            throw ApiException.of(HttpStatus.CONFLICT, "INVALID_AGREEMENT_STATE",
                    "Only an agreement in UNDER_REVIEW can be reviewed (current status: " + status + ")");
        }

        String agreementNo = text(row.get("agreement_no"));
        String signatoryName = text(row.get("signatory_name"));
        String signMethod = text(row.get("sign_method"));
        if (signMethod.isBlank()) {
            signMethod = "UPLOAD";
        }

        if (approve) {
            jdbc.update("""
                    UPDATE partner_agreements
                       SET status = 'SIGNED',
                           signed_at = COALESCE(signed_at, now()),
                           reviewed_by = ?,
                           reviewed_at = now(),
                           review_note = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, me.userId(), cleanNote, agreementId);

            syncKycAgreementColumns(
                    userId,
                    "SIGNED",
                    agreementId,
                    agreementNo,
                    signatoryName,
                    text(row.get("esign_client_id")),
                    signMethod);
            recordEvent(agreementId, "REVIEW_APPROVED", me.userId(),
                    cleanNote != null ? cleanNote : "Approved signed agreement",
                    Map.of("note", cleanNote == null ? "" : cleanNote, "signMethod", signMethod));
            journeyTracker.recordUserMilestone(
                    userId,
                    JourneyTracker.AGREEMENT_SIGNED,
                    "SUCCESS",
                    Map.of("agreementNo", agreementNo, "signMethod", signMethod, "reviewedBy", me.userId().toString()));
        } else {
            jdbc.update("""
                    UPDATE partner_agreements
                       SET status = 'RELEASED',
                           reviewed_by = ?,
                           reviewed_at = now(),
                           review_note = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, me.userId(), cleanNote, agreementId);

            syncKycAgreementColumns(
                    userId,
                    "RELEASED",
                    agreementId,
                    agreementNo,
                    signatoryName,
                    null,
                    null);
            recordEvent(agreementId, "REVIEW_REJECTED", me.userId(), cleanNote,
                    Map.of("note", cleanNote));
        }

        return getForAdmin(me, userId);
    }

    @Transactional
    public Map<String, Object> getForAdmin(AuthPrincipal me, UUID userId) {
        requireAdmin(me);
        access.assertNetworkUser(userId);
        syncIfPendingForUser(userId, false);
        Map<String, Object> row = findLiveAgreementRow(userId);
        Map<String, Object> profile = loadKycProfileSafe(userId);
        boolean businessVerified = profile != null
                && (Boolean.TRUE.equals(profile.get("business_verified"))
                || "BUSINESS_VERIFIED".equals(text(profile.get("business_status"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("businessVerified", businessVerified);
        out.put("canGenerate", businessVerified && (row == null || !"SIGNED".equals(text(row.get("status")))));
        if (row == null) {
            out.put("agreement", null);
            out.put("events", List.of());
            return out;
        }
        if ("RELEASED".equals(text(row.get("status"))) && text(row.get("esign_file_id")).isBlank()) {
            try {
                ensureUploadedToSurepass(row, me.userId());
                row = findLiveAgreementRow(userId);
            } catch (Exception e) {
                log.warn("AGREEMENT_SUREPASS_UPLOAD_ON_VIEW_FAILED agreementId={} err={}", row.get("id"), e.getMessage());
            }
        }
        UUID agreementId = (UUID) row.get("id");
        out.put("agreement", hydrateAgreement(row, true));
        out.put("events", listEvents(agreementId));
        return out;
    }

    @Transactional
    public Map<String, Object> getForPartner(AuthPrincipal me) {
        requirePartner(me);
        UUID userId = me.userId();
        // Cross-verify with Surepass if an eSign session exists and is not yet SIGNED in DB (2s dedup window)
        syncIfPendingForUser(userId, 2L, false);
        Map<String, Object> row = findLiveAgreementRow(userId);
        if (row == null || "DRAFT".equals(text(row.get("status")))) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("available", false);
            empty.put("status", "NOT_RELEASED");
            empty.put("agreement", null);
            return empty;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("status", text(row.get("status")));
        out.put("agreement", hydrateAgreement(row, false));
        return out;
    }

    @Transactional
    public Map<String, Object> summaryForVerificationView(UUID userId, boolean isAdmin) {
        syncIfPendingForUser(userId, false);
        Map<String, Object> row = findLiveAgreementRow(userId);
        if (row == null) {
            return null;
        }
        if (!isAdmin && "DRAFT".equals(text(row.get("status")))) {
            return null;
        }
        return hydrateAgreement(row, isAdmin);
    }

    @Transactional
    public Map<String, Object> publicReturnStatus(UUID agreementId) {
        if (agreementId == null) {
            throw ApiException.of(HttpStatus.BAD_REQUEST, "AGREEMENT_ID_REQUIRED", "Missing agreement identifier");
        }
        syncIfPending(agreementId, true);
        Map<String, Object> row = requireAgreementById(agreementId);
        String status = text(row.get("status"));
        String providerStatus = text(row.get("esign_provider_status"));
        boolean signed = "SIGNED".equals(status);
        String label = switch (status) {
            case "SIGNED" -> "Agreement Digitally Signed & Active";
            case "UNDER_REVIEW" -> "Agreement Signed — Pending Compliance Review";
            case "ESIGN_PENDING" -> "eSign In Progress (" + (providerStatus.isBlank() ? "awaiting signature" : providerStatus) + ")";
            case "RELEASED" -> "eSign Not Completed — Ready to Retry";
            default -> status;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agreementId", agreementId);
        out.put("agreementNo", text(row.get("agreement_no")));
        out.put("status", status);
        out.put("signed", signed);
        out.put("providerStatus", providerStatus);
        out.put("signatoryName", text(row.get("signatory_name")));
        out.put("signedAt", row.get("signed_at"));
        out.put("label", label);
        return out;
    }

    @Transactional
    public LoadedPdf loadFileForAdmin(AuthPrincipal me, UUID agreementId, String variant) {
        requireAdmin(me);
        Map<String, Object> row = requireAgreementById(agreementId);
        UUID userId = (UUID) row.get("user_id");
        access.assertNetworkUser(userId);
        return loadAgreementPdf(row, variant);
    }

    @Transactional
    public LoadedPdf loadFileForPartner(
            AuthPrincipal me,
            String variant,
            String ip,
            String userAgent) {
        requirePartner(me);
        UUID userId = me.userId();
        syncIfPendingForUser(userId, false);
        Map<String, Object> row = findLiveAgreementRow(userId);
        if (row == null || "DRAFT".equals(text(row.get("status")))) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AGREEMENT_NOT_RELEASED",
                    "Your Master Service Agreement is not available for download yet");
        }
        LoadedPdf loaded = loadAgreementPdf(row, variant);
        if (!"signed".equalsIgnoreCase(variant)) {
            UUID agreementId = (UUID) row.get("id");
            recordEvent(agreementId, "DOWNLOADED", userId, "Partner downloaded unsigned agreement PDF",
                    Map.of("variant", "unsigned"));
        }
        return loaded;
    }

    private LoadedPdf loadAgreementPdf(Map<String, Object> row, String variant) {
        String agreementNo = text(row.get("agreement_no"));
        boolean wantSigned = "signed".equalsIgnoreCase(variant)
                || ("auto".equalsIgnoreCase(variant) && !text(row.get("signed_key")).isBlank());
        if (wantSigned) {
            String signedKey = text(row.get("signed_key"));
            if (signedKey.isBlank()) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "SIGNED_PDF_NOT_AVAILABLE",
                        "No signed PDF is stored for this agreement yet");
            }
            return new LoadedPdf(spaces.getBytes(signedKey), agreementNo + "-signed.pdf");
        }
        String unsignedKey = text(row.get("unsigned_key"));
        return new LoadedPdf(spaces.getBytes(unsignedKey), agreementNo + ".pdf");
    }

    /**
     * On-demand pull synchronization & cross-verification for Surepass eSign status.
     * STRICT RULE: Once an agreement reaches SIGNED (with signed PDF stored), Surepass is never called again.
     * Whenever an eSign clientId exists in DB and status is not yet SIGNED, cross-verifies from Surepass,
     * persists the latest provider status + check timestamp in DB, and retries on subsequent checks until signed.
     */
    @Transactional
    public void syncIfPendingForUser(UUID userId, boolean force) {
        long minInterval = force ? 0L : esignProps.statusCheckThrottleSeconds();
        syncIfPendingForUser(userId, minInterval, force);
    }

    @Transactional
    public void syncIfPendingForUser(UUID userId, long minIntervalSeconds, boolean allowReleasedReverify) {
        if (userId == null) {
            return;
        }
        Map<String, Object> row = findLiveAgreementRow(userId);
        if (row == null || !shouldCrossVerifyWithSurepass(row, allowReleasedReverify)) {
            return;
        }
        syncPendingRow(row, minIntervalSeconds);
    }

    @Transactional
    public void syncIfPending(UUID agreementId, boolean force) {
        if (agreementId == null) {
            return;
        }
        Map<String, Object> row;
        try {
            row = requireAgreementById(agreementId);
        } catch (ApiException e) {
            return;
        }
        if (!shouldCrossVerifyWithSurepass(row, force)) {
            return;
        }
        long minInterval = force ? 0L : esignProps.statusCheckThrottleSeconds();
        syncPendingRow(row, minInterval);
    }

    private boolean shouldCrossVerifyWithSurepass(Map<String, Object> row, boolean allowReleasedReverify) {
        String status = text(row.get("status"));
        if ("SIGNED".equals(status) && !text(row.get("signed_key")).isBlank()) {
            return false;
        }
        if ("ESIGN_PENDING".equals(status)) {
            return true;
        }
        // If an eSign session was previously created (`esign_client_id` stored in DB) and status fell back
        // to RELEASED (e.g. before completion or transient error), explicit refresh/return re-verifies Surepass.
        if (allowReleasedReverify && "RELEASED".equals(status) && !text(row.get("esign_client_id")).isBlank()) {
            String prevProviderStatus = text(row.get("esign_provider_status"));
            return !"client_id_not_found".equalsIgnoreCase(prevProviderStatus)
                    && !"expired".equalsIgnoreCase(prevProviderStatus);
        }
        return false;
    }

    private void syncPendingRow(Map<String, Object> row, long minIntervalSeconds) {
        UUID agreementId = (UUID) row.get("id");
        UUID userId = (UUID) row.get("user_id");
        String agreementNo = text(row.get("agreement_no"));
        String clientId = text(row.get("esign_client_id"));
        String expectedSigner = text(row.get("signatory_name"));
        String previousProviderStatus = text(row.get("esign_provider_status"));
        Instant lastChecked = toInstant(row.get("esign_last_checked_at"));
        Instant initiatedAt = toInstant(row.get("esign_initiated_at"));
        Instant expiresAt = initiatedAt != null
                ? initiatedAt.plus(esignProps.sessionTtlHours(), ChronoUnit.HOURS)
                : null;

        if (minIntervalSeconds > 0 && lastChecked != null) {
            long elapsedSeconds = Duration.between(lastChecked, Instant.now()).getSeconds();
            if (elapsedSeconds < minIntervalSeconds) {
                return;
            }
        }

        if (clientId.isBlank()) {
            jdbc.update("""
                    UPDATE partner_agreements
                       SET status = 'RELEASED',
                           esign_url = NULL,
                           updated_at = now()
                     WHERE id = ?
                    """, agreementId);
            syncKycAgreementColumns(userId, "RELEASED", agreementId, agreementNo, expectedSigner, null, null);
            return;
        }

        Map<String, Object> esignDetails = new LinkedHashMap<>(fromJson(text(row.get("esign_details_json"))));
        EsignClient.EsignStatus sr;
        try {
            sr = esignClient.status(clientId);
        } catch (Exception e) {
            log.warn("ESIGN_STATUS_PULL_WARN agreementId={} clientId={} err={}",
                    agreementId, clientId, e.getMessage());
            esignDetails.put("lastCheckError", e.getMessage());
            esignDetails.put("lastCheckedAt", Instant.now().toString());
            jdbc.update("""
                    UPDATE partner_agreements
                       SET esign_last_checked_at = now(),
                           esign_details = ?::jsonb,
                           updated_at = now()
                     WHERE id = ?
                    """, toJson(esignDetails), agreementId);
            return;
        }

        esignDetails.remove("lastCheckError");
        esignDetails.put("lastProviderStatus", sr.rawStatus());
        esignDetails.put("lastProviderPhase", sr.phase().name());
        esignDetails.put("lastProviderLabel", sr.label());
        esignDetails.put("lastCheckedAt", Instant.now().toString());

        switch (sr.phase()) {
            case COMPLETED -> {
                byte[] signedBytes;
                try {
                    String signedUrl = esignClient.signedDocumentUrl(clientId);
                    signedBytes = esignClient.download(signedUrl);
                } catch (Exception docEx) {
                    // Surepass marked esign_completed, but signed PDF may take a moment to finalize.
                    // Store esign_completed in DB and keep ESIGN_PENDING so the next check completes download.
                    log.warn("ESIGN_SIGNED_DOC_PENDING agreementId={} clientId={} err={}",
                            agreementId, clientId, docEx.getMessage());
                    esignDetails.put("pendingSignedPdf", true);
                    esignDetails.put("pendingSignedPdfReason", docEx.getMessage());
                    jdbc.update("""
                            UPDATE partner_agreements
                               SET status = 'ESIGN_PENDING',
                                   sign_method = 'ESIGN',
                                   esign_provider_status = ?,
                                   esign_last_checked_at = now(),
                                   esign_details = ?::jsonb,
                                   updated_at = now()
                             WHERE id = ?
                            """, sr.rawStatus(), toJson(esignDetails), agreementId);
                    syncKycAgreementColumns(userId, "ESIGN_PENDING", agreementId, agreementNo, expectedSigner, clientId, "ESIGN");
                    return;
                }

                esignDetails.remove("pendingSignedPdf");
                esignDetails.remove("pendingSignedPdfReason");

                String sha256 = AgreementRenderer.sha256Hex(signedBytes);
                SpacesStorage.StoredObject stored = spaces.uploadBytes(
                        "agreements/signed",
                        agreementNo + "-signed-esign.pdf",
                        "application/pdf",
                        signedBytes);

                String extractedSigner = renderer.extractSignerNameFromPdf(signedBytes);
                int nameScore = 100;
                boolean nameMismatch = false;
                if (extractedSigner != null && !extractedSigner.isBlank() && !expectedSigner.isBlank()) {
                    nameScore = NameMatcher.score(expectedSigner, extractedSigner);
                    if (nameScore < esignProps.nameMatchScore()) {
                        nameMismatch = true;
                    }
                }

                String nextStatus = nameMismatch ? "UNDER_REVIEW" : "SIGNED";
                String reviewNote = nameMismatch
                        ? "Aadhaar signer name '" + extractedSigner + "' scored " + nameScore
                                + "% against authorized signatory '" + expectedSigner + "'. Admin review required."
                        : null;

                esignDetails.put("signedSha256", sha256);
                esignDetails.put("completedAt", Instant.now().toString());
                if (extractedSigner != null) {
                    esignDetails.put("aadhaarSignerName", extractedSigner);
                    esignDetails.put("nameMatchScore", nameScore);
                }

                jdbc.update("""
                        UPDATE partner_agreements
                           SET status = ?,
                               sign_method = 'ESIGN',
                               esign_provider_status = ?,
                               esign_last_checked_at = now(),
                               esign_details = ?::jsonb,
                               esign_url = NULL,
                               signed_key = ?,
                               signed_sha256 = ?,
                               signed_filename = ?,
                               signed_content_type = 'application/pdf',
                               signed_at = CASE WHEN ? = 'SIGNED' THEN now() ELSE signed_at END,
                               review_note = COALESCE(?, review_note),
                               updated_at = now()
                         WHERE id = ?
                        """,
                        nextStatus,
                        sr.rawStatus(),
                        toJson(esignDetails),
                        stored.key(),
                        sha256,
                        agreementNo + "-signed-esign.pdf",
                        nextStatus,
                        reviewNote,
                        agreementId);

                syncKycAgreementColumns(
                        userId,
                        nextStatus,
                        agreementId,
                        agreementNo,
                        expectedSigner,
                        clientId,
                        "ESIGN");

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("clientId", clientId);
                detail.put("providerStatus", sr.rawStatus());
                detail.put("sha256", sha256);
                detail.put("status", nextStatus);
                if (extractedSigner != null) {
                    detail.put("aadhaarSignerName", extractedSigner);
                    detail.put("nameMatchScore", nameScore);
                }
                recordEvent(agreementId, "ESIGN_COMPLETED", null,
                        reviewNote != null ? reviewNote : "Completed Aadhaar eSign (" + clientId + ")",
                        detail);

                if ("SIGNED".equals(nextStatus)) {
                    journeyTracker.recordUserMilestone(
                            userId,
                            JourneyTracker.AGREEMENT_SIGNED,
                            "SUCCESS",
                            Map.of("agreementNo", agreementNo, "signMethod", "ESIGN", "clientId", clientId));
                }
            }
            case FAILED, NOT_FOUND -> {
                jdbc.update("""
                        UPDATE partner_agreements
                           SET status = 'RELEASED',
                               esign_provider_status = ?,
                               esign_last_checked_at = now(),
                               esign_details = ?::jsonb,
                               esign_url = NULL,
                               updated_at = now()
                         WHERE id = ?
                        """, sr.rawStatus(), toJson(esignDetails), agreementId);
                syncKycAgreementColumns(userId, "RELEASED", agreementId, agreementNo, expectedSigner, clientId, null);
                recordEvent(agreementId, "ESIGN_FAILED", null,
                        "eSign session ended with status: " + sr.rawStatus(),
                        Map.of("clientId", clientId, "providerStatus", sr.rawStatus()));
            }
            case IN_PROGRESS -> {
                if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
                    esignDetails.put("lastProviderStatus", "expired");
                    jdbc.update("""
                            UPDATE partner_agreements
                               SET status = 'RELEASED',
                                   esign_provider_status = 'expired',
                                   esign_last_checked_at = now(),
                                   esign_details = ?::jsonb,
                                   esign_url = NULL,
                                   updated_at = now()
                             WHERE id = ?
                            """, toJson(esignDetails), agreementId);
                    syncKycAgreementColumns(userId, "RELEASED", agreementId, agreementNo, expectedSigner, clientId, null);
                    recordEvent(agreementId, "ESIGN_EXPIRED", null,
                            "eSign session expired after " + esignProps.sessionTtlHours() + " hours",
                            Map.of("clientId", clientId, "expiredAt", expiresAt.toString()));
                } else {
                    jdbc.update("""
                            UPDATE partner_agreements
                               SET status = 'ESIGN_PENDING',
                                   esign_provider_status = ?,
                                   esign_last_checked_at = now(),
                                   esign_details = ?::jsonb,
                                   updated_at = now()
                             WHERE id = ?
                            """, sr.rawStatus(), toJson(esignDetails), agreementId);
                    syncKycAgreementColumns(userId, "ESIGN_PENDING", agreementId, agreementNo, expectedSigner, clientId, "ESIGN");
                    if (!sr.rawStatus().equalsIgnoreCase(previousProviderStatus)) {
                        recordEvent(agreementId, "ESIGN_STATUS_SYNCED", null,
                                sr.label(),
                                Map.of("clientId", clientId, "providerStatus", sr.rawStatus()));
                    }
                }
            }
        }
    }

    // ─── Mock eSign Helper Endpoints (when app.esign.mode=mock) ───────────────

    public Map<String, Object> getMockSessionInfo(String clientId) {
        if (!(esignClient instanceof MockEsignClient mock)) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "MOCK_DISABLED", "Mock eSign is disabled in live mode");
        }
        MockEsignClient.MockSessionState session = mock.getSession(clientId);
        if (session == null) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "ESIGN_SESSION_NOT_FOUND", "Mock eSign session not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientId", session.clientId);
        out.put("agreementId", session.request.agreementId());
        out.put("agreementNo", session.request.agreementNo());
        out.put("signerName", session.request.signerFullName());
        out.put("signerMobile", maskMobile(session.request.signerMobile()));
        out.put("signerEmail", session.request.signerEmail());
        out.put("status", session.rawStatus);
        out.put("redirectUrl", session.request.redirectUrl());
        return out;
    }

    @Transactional
    public Map<String, Object> performMockAction(String clientId, String action, String signerName) {
        if (!(esignClient instanceof MockEsignClient mock)) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "MOCK_DISABLED", "Mock eSign is disabled in live mode");
        }
        MockEsignClient.MockSessionState session = mock.getSession(clientId);
        if (session == null) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "ESIGN_SESSION_NOT_FOUND", "Mock eSign session not found");
        }
        if ("fail".equalsIgnoreCase(action)) {
            mock.setStatus(clientId, "esign_failed");
        } else {
            mock.complete(clientId, signerName);
        }
        if (session.request.agreementId() != null && !session.request.agreementId().isBlank()) {
            syncIfPending(UUID.fromString(session.request.agreementId()), true);
        }
        return Map.of(
                "clientId", clientId,
                "status", session.rawStatus,
                "redirectUrl", session.request.redirectUrl());
    }

    // ─── Internal Helpers ─────────────────────────────────────────────────────

    private Map<String, Object> buildEsignStartResponse(
            boolean signatoryIsSelf,
            String signingUrl,
            String clientId,
            Instant expiresAt,
            String signatoryName,
            String signatoryMobile,
            Map<String, Object> partnerView) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", signatoryIsSelf ? "SIGN" : "SHARE");
        out.put("signingUrl", signingUrl);
        out.put("clientId", clientId);
        out.put("expiresAt", expiresAt);
        out.put("signatoryName", signatoryName);
        out.put("signatoryMobileMasked", maskMobile(signatoryMobile));
        out.put("signatoryIsSelf", signatoryIsSelf);
        out.put("agreement", partnerView);
        return out;
    }

    private Map<String, Object> hydrateAgreement(Map<String, Object> row, boolean includeAdminFields) {
        UUID id = (UUID) row.get("id");
        String status = text(row.get("status"));
        boolean hasSignedPdf = !text(row.get("signed_key")).isBlank();
        boolean signatoryIsSelf = Boolean.TRUE.equals(row.get("signatory_is_self"));
        Map<String, Object> partySnapshot = fromJson(text(row.get("party_snapshot_json")));
        Map<String, Object> esignDetails = fromJson(text(row.get("esign_details_json")));
        String esignFileId = text(row.get("esign_file_id"));
        String esignUploadClientId = text(esignDetails.getOrDefault("uploadClientId", esignFileId));
        Instant initiatedAt = toInstant(row.get("esign_initiated_at"));
        Instant expiresAt = initiatedAt != null
                ? initiatedAt.plus(esignProps.sessionTtlHours(), ChronoUnit.HOURS)
                : null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("agreementNo", text(row.get("agreement_no")));
        m.put("userId", row.get("user_id"));
        m.put("partnerRole", partySnapshot.getOrDefault("userType",
                "DISTRIBUTOR_MSA".equals(text(row.get("agreement_type"))) ? "MASTER_DISTRIBUTOR" : "RETAILER"));
        m.put("agreementType", text(row.get("agreement_type")));
        m.put("templateVersion", text(row.get("template_version")));
        m.put("status", status);
        m.put("signingMode", row.get("sign_method"));
        m.put("onboardingFee", row.get("onboarding_fee"));
        m.put("onboardingFeeDisplay", partySnapshot.getOrDefault("onboardingFeeDisplay",
                partySnapshot.getOrDefault("onboardingFee", "NIL")));
        m.put("partyName", partySnapshot.getOrDefault("partyName", ""));
        m.put("partyKind", partySnapshot.getOrDefault("partyKind", ""));
        m.put("partyAddress", partySnapshot.getOrDefault("partyAddress", ""));
        m.put("actingThrough", partySnapshot.getOrDefault("actingThrough", ""));
        m.put("signatoryName", text(row.get("signatory_name")));
        m.put("signatoryDesignation", partySnapshot.getOrDefault("signatoryDesignation", ""));
        m.put("signatoryMobileMasked", maskMobile(text(row.get("signatory_mobile"))));
        m.put("signatoryEmail", partySnapshot.getOrDefault("partnerEmail", ""));
        m.put("signatoryIsSelf", signatoryIsSelf);
        m.put("esignMode", signatoryIsSelf ? "SIGN" : "SHARE");
        m.put("esignFileId", esignFileId.isBlank() ? null : esignFileId);
        m.put("esignUploadClientId", esignUploadClientId.isBlank() ? null : esignUploadClientId);
        m.put("esignClientId", row.get("esign_client_id"));
        m.put("esignProviderStatus", row.get("esign_provider_status"));
        m.put("esignInitiatedAt", row.get("esign_initiated_at"));
        m.put("esignExpiresAt", expiresAt);
        m.put("esignLastCheckedAt", row.get("esign_last_checked_at"));
        if ("ESIGN_PENDING".equals(status)) {
            m.put("esignUrl", row.get("esign_url"));
        }
        m.put("pageCount", row.get("page_count"));
        m.put("hasUnsignedPdf", !text(row.get("unsigned_key")).isBlank());
        m.put("hasSignedPdf", hasSignedPdf);
        m.put("unsignedPdfSha256", row.get("unsigned_sha256"));
        m.put("signedPdfSha256", row.get("signed_sha256"));
        m.put("generatedAt", row.get("generated_at"));
        m.put("releasedAt", row.get("released_at"));
        m.put("signedAt", row.get("signed_at"));
        m.put("reviewedAt", row.get("reviewed_at"));
        m.put("reviewNote", row.get("review_note"));
        if (includeAdminFields) {
            m.put("signatoryMobile", text(row.get("signatory_mobile")));
            m.put("generatedBy", row.get("generated_by"));
            m.put("generatedByName", row.get("generated_by_name"));
            m.put("releasedBy", row.get("released_by"));
            m.put("releasedByName", row.get("released_by_name"));
            m.put("reviewedBy", row.get("reviewed_by"));
            m.put("reviewedByName", row.get("reviewed_by_name"));
            m.put("partySnapshot", partySnapshot);
        }
        return m;
    }

    private void ensureUploadedToSurepass(Map<String, Object> row, UUID actorId) {
        if (row == null) return;
        String existingFileId = text(row.get("esign_file_id"));
        if (!existingFileId.isBlank()) return;
        UUID agreementId = (UUID) row.get("id");
        String agreementNo = text(row.get("agreement_no"));
        String unsignedKey = text(row.get("unsigned_key"));
        if (unsignedKey.isBlank()) return;
        byte[] unsignedPdf = spaces.getBytes(unsignedKey);
        EsignClient.EsignUpload upload = esignClient.uploadDocument(agreementNo + ".pdf", unsignedPdf);
        Map<String, Object> esignDetails = new LinkedHashMap<>(fromJson(text(row.get("esign_details_json"))));
        esignDetails.put("fileId", upload.fileId());
        esignDetails.put("uploadClientId", upload.clientId());
        esignDetails.put("uploadedAt", Instant.now().toString());
        jdbc.update("""
                UPDATE partner_agreements
                   SET esign_file_id = ?,
                       esign_details = ?::jsonb,
                       updated_at = now()
                 WHERE id = ?
                """,
                upload.fileId(),
                toJson(esignDetails),
                agreementId);
        recordEvent(agreementId, "SUREPASS_UPLOADED", actorId,
                "Uploaded released PDF to Surepass (file_id: " + upload.fileId() + ", client_id: " + upload.clientId() + ")",
                Map.of("fileId", upload.fileId(), "uploadClientId", upload.clientId()));
    }

    private List<Map<String, Object>> listEvents(UUID agreementId) {
        return jdbc.queryForList("""
                SELECT e.id, e.action AS event_type, e.actor_id,
                       u.full_name AS actor_name,
                       e.note,
                       e.payload::text AS payload_json,
                       e.created_at
                  FROM partner_agreement_events e
                  LEFT JOIN users u ON u.id = e.actor_id
                 WHERE e.agreement_id = ?
                 ORDER BY e.created_at DESC
                """, agreementId).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(r);
            Object raw = m.remove("payload_json");
            m.put("detail", fromJson(raw == null ? null : String.valueOf(raw)));
            return m;
        }).toList();
    }

    private Map<String, Object> findLiveAgreementRow(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.*,
                       a.party_snapshot::text AS party_snapshot_json,
                       a.esign_details::text AS esign_details_json,
                       gb.full_name AS generated_by_name,
                       rb.full_name AS released_by_name,
                       rv.full_name AS reviewed_by_name
                  FROM partner_agreements a
                  LEFT JOIN users gb ON gb.id = a.generated_by
                  LEFT JOIN users rb ON rb.id = a.released_by
                  LEFT JOIN users rv ON rv.id = a.reviewed_by
                 WHERE a.user_id = ?
                   AND a.status <> 'VOID'
                 ORDER BY a.created_at DESC
                 LIMIT 1
                """, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> requireAgreementById(UUID agreementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.*,
                       a.party_snapshot::text AS party_snapshot_json,
                       a.esign_details::text AS esign_details_json,
                       gb.full_name AS generated_by_name,
                       rb.full_name AS released_by_name,
                       rv.full_name AS reviewed_by_name
                  FROM partner_agreements a
                  LEFT JOIN users gb ON gb.id = a.generated_by
                  LEFT JOIN users rb ON rb.id = a.released_by
                  LEFT JOIN users rv ON rv.id = a.reviewed_by
                 WHERE a.id = ?
                """, agreementId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "AGREEMENT_NOT_FOUND", "Agreement was not found");
        }
        return rows.get(0);
    }

    private void syncKycAgreementColumns(
            UUID userId,
            String agreementStatus,
            UUID agreementId,
            String agreementNo,
            String signatoryName,
            String providerRef,
            String signingMode) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (agreementId != null) {
            details.put("agreementId", agreementId.toString());
        }
        if (agreementNo != null) {
            details.put("agreementNo", agreementNo);
        }
        if (signatoryName != null) {
            details.put("signatoryName", signatoryName);
        }
        if (providerRef != null && !providerRef.isBlank()) {
            details.put("providerRef", providerRef);
        }
        if (signingMode != null && !signingMode.isBlank()) {
            details.put("signingMode", signingMode);
        }
        details.put("status", agreementStatus);
        details.put("updatedAt", Instant.now().toString());

        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET agreement_status = ?,
                       agreement_details = ?::jsonb,
                       updated_at = now()
                 WHERE user_id = ?
                """, agreementStatus, toJson(details), userId);
    }

    private void recordEvent(
            UUID agreementId,
            String action,
            UUID actorId,
            String note,
            Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO partner_agreement_events
                    (agreement_id, action, actor_id, note, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """,
                agreementId,
                action,
                actorId,
                note,
                toJson(payload));
    }

    private static boolean isPdfBytes(byte[] bytes) {
        return bytes != null
                && bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-';
    }

    private void ensureKycProfile(UUID userId) {
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, kyc_status)
                VALUES (?, 'PENDING')
                ON CONFLICT (user_id) DO NOTHING
                """, userId);
    }

    private Map<String, Object> loadKycProfile(UUID userId) {
        try {
            return jdbc.queryForMap("""
                    SELECT business_status, COALESCE(business_verified, FALSE) AS business_verified,
                           business_type, agreement_status
                      FROM agent_kyc_profiles
                     WHERE user_id = ?
                    """, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND", "Verification profile was not found");
        }
    }

    private Map<String, Object> loadKycProfileSafe(UUID userId) {
        try {
            return loadKycProfile(userId);
        } catch (ApiException e) {
            return null;
        }
    }

    private String loadUserFullName(UUID userId) {
        try {
            String name = jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, userId);
            return (name == null || name.isBlank()) ? "Admin" : name.trim();
        } catch (EmptyResultDataAccessException e) {
            return "Admin";
        }
    }

    private static void requireAdmin(AuthPrincipal me) {
        if (me == null || !me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only platform staff can manage partner agreements");
        }
    }

    private static void requirePartner(AuthPrincipal me) {
        if (me == null || (!"RETAILER".equals(me.userType()) && !"MASTER_DISTRIBUTOR".equals(me.userType()))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only a retailer or distributor can access partner agreements");
        }
    }

    private static Instant toInstant(Object val) {
        if (val instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (val instanceof Instant inst) {
            return inst;
        }
        if (val instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return null;
    }

    private static BigDecimal parseCoord(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String maskMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return "";
        }
        String digits = mobile.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return mobile;
        }
        return "XXXXXX" + digits.substring(digits.length() - 4);
    }

    private static String text(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize agreement JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
