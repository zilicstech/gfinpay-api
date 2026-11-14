package com.fintech.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.agreement.AgreementService;
import com.fintech.identity.AdminAccess;
import com.fintech.kyc.surepass.SurepassClient;
import com.fintech.onboarding.JourneyTracker;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.storage.SpacesStorage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VerificationService {

    public static final Set<String> ENTITY_TYPES = Set.of(
            "INDIVIDUAL", "SOLE_PROPRIETORSHIP", "PARTNERSHIP", "LLP", "OPC",
            "PRIVATE_LIMITED", "PUBLIC_LIMITED", "HUF", "TRUST", "SOCIETY");

    private static final Map<String, Set<String>> REQUIRED_DOCS = Map.of(
            "INDIVIDUAL", Set.of("SHOP_PHOTO", "ADDRESS_PROOF"),
            "SOLE_PROPRIETORSHIP", Set.of("SHOP_PHOTO", "ADDRESS_PROOF"),
            "PARTNERSHIP", Set.of("PARTNERSHIP_DEED"),
            "HUF", Set.of("HUF_DEED"),
            "LLP", Set.of("LLP_AGREEMENT", "CERTIFICATE_OF_INCORPORATION"),
            "OPC", Set.of("CERTIFICATE_OF_INCORPORATION"),
            "PRIVATE_LIMITED", Set.of("CERTIFICATE_OF_INCORPORATION"),
            "PUBLIC_LIMITED", Set.of("CERTIFICATE_OF_INCORPORATION"),
            "TRUST", Set.of("REGISTRATION_CERTIFICATE"),
            "SOCIETY", Set.of("REGISTRATION_CERTIFICATE"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SurepassClient surepass;
    private final AgreementService agreements;
    private final SpacesStorage spaces;
    private final JourneyTracker journeyTracker;
    private final AdminAccess access;
    private final DigilockerDocumentService digilockerDocuments;
    private final FaceReviewService faceReview;
    private final BusinessRegistrationService businessRegistration;
    private final FieldCipher fieldCipher;

    @org.springframework.beans.factory.annotation.Autowired
    public VerificationService(JdbcTemplate jdbc, ObjectMapper mapper, SurepassClient surepass,
                               AgreementService agreements, SpacesStorage spaces, JourneyTracker journeyTracker,
                               AdminAccess access,
                               DigilockerDocumentService digilockerDocuments,
                               FaceReviewService faceReview,
                               BusinessRegistrationService businessRegistration,
                               FieldCipher fieldCipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.surepass = surepass;
        this.agreements = agreements;
        this.spaces = spaces;
        this.journeyTracker = journeyTracker;
        this.access = access;
        this.digilockerDocuments = digilockerDocuments;
        this.faceReview = faceReview;
        this.businessRegistration = businessRegistration;
        this.fieldCipher = fieldCipher;
    }

    public VerificationService(JdbcTemplate jdbc, ObjectMapper mapper, SurepassClient surepass,
                               AgreementService agreements, SpacesStorage spaces, JourneyTracker journeyTracker,
                               AdminAccess access,
                               DigilockerDocumentService digilockerDocuments,
                               FaceReviewService faceReview,
                               BusinessRegistrationService businessRegistration) {
        this(jdbc, mapper, surepass, agreements, spaces, journeyTracker, access, digilockerDocuments, faceReview, businessRegistration, null);
    }

    public VerificationService(JdbcTemplate jdbc, ObjectMapper mapper, SurepassClient surepass,
                               AgreementService agreements, SpacesStorage spaces, JourneyTracker journeyTracker,
                               AdminAccess access,
                               DigilockerDocumentService digilockerDocuments,
                               FaceReviewService faceReview) {
        this(jdbc, mapper, surepass, agreements, spaces, journeyTracker, access, digilockerDocuments, faceReview, null, null);
    }

    public Map<String, Object> status(AuthPrincipal me) {
        requirePartner(me);
        ensureProfile(me.userId());
        return view(me.userId(), false);
    }

    @Transactional
    public Map<String, Object> saveFullAadhaar(AuthPrincipal me, String aadhaarRaw) {
        requirePartner(me);
        ensureProfile(me.userId());
        requireIdentityRow(me.userId());
        if (fieldCipher == null) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "CIPHER_UNAVAILABLE",
                    "Encryption service is unavailable");
        }
        String aadhaarLast4 = jdbc.queryForObject(
                "SELECT COALESCE(aadhaar_last4, '') FROM agent_kyc_profiles WHERE user_id = ?",
                String.class, me.userId());
        String expectedLast4 = (aadhaarLast4 == null || aadhaarLast4.isBlank() || "0000".equals(aadhaarLast4.trim()))
                ? null
                : aadhaarLast4.trim();
        String normalized = AadhaarValidator.validateAndNormalize(aadhaarRaw, expectedLast4);
        String encrypted = fieldCipher.encrypt(normalized);
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET aadhaar_enc = ?,
                       aadhaar_last4 = COALESCE(NULLIF(TRIM(aadhaar_last4), ''), ?),
                       updated_at = now()
                 WHERE user_id = ?
                """, encrypted, normalized.substring(8), me.userId());
        return view(me.userId(), false);
    }

    public Map<String, Object> statusFor(UUID userId) {
        access.assertNetworkUser(userId);
        ensureProfile(userId);
        return view(userId, true);
    }

    public Map<String, Object> lookupGstin(AuthPrincipal me, String gstinRaw) {
        requirePartner(me);
        String gstin = required(gstinRaw, "Enter a GSTIN").toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        SurepassClient.GstinRecord rec = surepass.gstin(gstin);
        if (!rec.valid()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GSTIN", "GSTIN could not be verified");
        }
        return Map.of(
                "gstin", rec.gstin(),
                "legalName", rec.legalName(),
                "tradeName", rec.tradeName(),
                "address", rec.address(),
                "status", rec.status(),
                "providerRef", rec.providerRef());
    }

    public Map<String, Object> lookupUdyam(AuthPrincipal me, String udyamRaw) {
        requirePartner(me);
        String udyam = required(udyamRaw, "Enter an Udyam registration number").toUpperCase(Locale.ROOT).trim();
        SurepassClient.UdyamRecord rec = surepass.udyam(udyam);
        if (!rec.valid()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_UDYAM", "Udyam registration could not be verified");
        }
        return Map.of(
                "udyamNumber", rec.udyamNumber(),
                "enterpriseName", rec.enterpriseName(),
                "enterpriseType", rec.enterpriseType(),
                "address", rec.address(),
                "providerRef", rec.providerRef());
    }

    public Map<String, Object> lookupCin(AuthPrincipal me, String cinRaw) {
        requirePartner(me);
        String cin = required(cinRaw, "Enter a CIN / LLPIN").toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        SurepassClient.CompanyRecord rec = surepass.company(cin);
        if (!rec.valid()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CIN", "Company registration could not be verified");
        }
        return Map.of(
                "cin", rec.cin(),
                "companyName", rec.companyName(),
                "registeredAddress", rec.registeredAddress(),
                "status", rec.status(),
                "providerRef", rec.providerRef());
    }

    public Map<String, Object> kycLocation(AuthPrincipal me) {
        return businessRegistration.getKycLocation(me);
    }

    @Transactional
    public Map<String, Object> submitIndividualBusiness(
            AuthPrincipal me,
            String locationSource,
            String latitude,
            String longitude,
            String shopName,
            AddressFormatter.BusinessAddressInput address) {
        return submitIndividualBusiness(me, locationSource, latitude, longitude, shopName, address, null, null, null);
    }

    @Transactional
    public Map<String, Object> submitIndividualBusiness(
            AuthPrincipal me,
            String locationSource,
            String latitude,
            String longitude,
            String shopName,
            AddressFormatter.BusinessAddressInput address,
            String email,
            String aadhaarNumber,
            String gstin) {
        businessRegistration.submitIndividual(
                me, locationSource, latitude, longitude, shopName, address, email, aadhaarNumber, gstin);
        return view(me.userId(), false);
    }

    public Map<String, Object> lookupCompanyDetails(AuthPrincipal me, String cin) {
        return businessRegistration.lookupCompany(me, cin);
    }

    @Transactional
    public Map<String, Object> submitCompanyBusiness(
            AuthPrincipal me,
            String cin,
            String din,
            String shopName,
            String signatoryMobile,
            String latitude,
            String longitude) {
        return submitCompanyBusiness(
                me, cin, din, shopName, signatoryMobile, latitude, longitude, null, null, null, null);
    }

    @Transactional
    public Map<String, Object> submitCompanyBusiness(
            AuthPrincipal me,
            String cin,
            String din,
            String shopName,
            String signatoryMobile,
            String latitude,
            String longitude,
            AddressFormatter.BusinessAddressInput address,
            String email,
            String aadhaarNumber,
            String gstin) {
        businessRegistration.submitCompany(
                me, cin, din, shopName, signatoryMobile, latitude, longitude, address, email, aadhaarNumber, gstin);
        return view(me.userId(), false);
    }

    @Transactional
    public Map<String, Object> resendSignatoryLink(AuthPrincipal me) {
        businessRegistration.resendSignatoryLink(me);
        return view(me.userId(), false);
    }

    @Transactional
    public Map<String, Object> changeSignatory(AuthPrincipal me) {
        businessRegistration.changeSignatory(me);
        return view(me.userId(), false);
    }

    @Transactional
    public Map<String, Object> reviewSignatoryFace(AuthPrincipal me, UUID userId, boolean matched, String note) {
        ensureProfile(userId);
        businessRegistration.reviewSignatoryFace(me, userId, matched, note);
        return view(userId, true);
    }

    @Transactional
    public Map<String, Object> saveBusiness(AuthPrincipal me, String entityType, String legalName, String tradeName,
                                            String address, AddressFormatter.BusinessAddressInput businessAddress,
                                            Boolean sameAsPersonal,
                                            String signatoryName, String gstin, String udyam,
                                            String cin, String latitude, String longitude) {
        requirePartner(me);
        ensureProfile(me.userId());
        requireIdentityRow(me.userId());
        BigDecimal lat = location(latitude, true);
        BigDecimal lng = location(longitude, false);
        String type = entity(entityType);

        Map<String, Object> profile = profile(me.userId());
        Map<String, Object> personalMap = jsonMap(profile.get("personal_details"));
        if (personalMap.isEmpty()) {
            personalMap = jsonMap(profile.get("identity_details"));
        }
        Map<String, Object> permanentAddress = extractPermanentAddress(personalMap);

        Map<String, Object> structuredAddress = resolveBusinessAddress(
                address, businessAddress, sameAsPersonal, permanentAddress, personalMap, lat, lng);
        boolean differsFromPersonal = AddressFormatter.differsFromPersonal(
                structuredAddress,
                permanentAddress,
                text(personalMap.get("pincode")),
                text(personalMap.get("state")));

        String cleanGstin = blank(gstin);
        String cleanUdyam = blank(udyam);
        String cleanCin = blank(cin);

        Map<String, Object> registry = new LinkedHashMap<>();
        if (cleanGstin != null) {
            SurepassClient.GstinRecord rec = surepass.gstin(cleanGstin.toUpperCase(Locale.ROOT));
            registry.put("gstin", Map.of(
                    "number", rec.gstin(),
                    "legalName", rec.legalName(),
                    "tradeName", rec.tradeName(),
                    "address", rec.address() == null ? "" : rec.address(),
                    "status", rec.status(),
                    "providerRef", rec.providerRef()));
        }
        if (cleanUdyam != null) {
            SurepassClient.UdyamRecord rec = surepass.udyam(cleanUdyam.toUpperCase(Locale.ROOT));
            registry.put("udyam", Map.of(
                    "number", rec.udyamNumber(),
                    "enterpriseName", rec.enterpriseName(),
                    "enterpriseType", rec.enterpriseType(),
                    "address", rec.address() == null ? "" : rec.address(),
                    "providerRef", rec.providerRef()));
        }
        if (cleanCin != null) {
            SurepassClient.CompanyRecord rec = surepass.company(cleanCin.toUpperCase(Locale.ROOT));
            registry.put("cin", Map.of(
                    "number", rec.cin(),
                    "companyName", rec.companyName(),
                    "registeredAddress", rec.registeredAddress() == null ? "" : rec.registeredAddress(),
                    "status", rec.status(),
                    "providerRef", rec.providerRef()));
        }

        String fullAddressText = text(structuredAddress.get("fullAddress"));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("entityType", type);
        details.put("legalName", required(legalName, "Legal name is required"));
        details.put("tradeName", required(tradeName, "Trade name is required"));
        details.put("address", structuredAddress);
        details.put("addressText", fullAddressText);
        details.put("addressDiffersFromPersonal", differsFromPersonal);
        details.put("signatoryName", required(signatoryName, "Authorized signatory is required"));
        details.put("gstin", cleanGstin);
        details.put("udyam", cleanUdyam);
        details.put("cin", cleanCin);
        if (!registry.isEmpty()) {
            details.put("registry", registry);
        }

        Map<String, Object> shopAddress = new LinkedHashMap<>(structuredAddress);
        shopAddress.put("address", fullAddressText);

        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET business_status = CASE WHEN business_status = 'BUSINESS_VERIFIED'
                                              THEN 'BUSINESS_VERIFIED' ELSE 'DETAILS_CAPTURED' END,
                       business_details = ?::jsonb,
                       shop_address = ?::jsonb,
                       shop_name = ?,
                       gstin = ?,
                       updated_at = now()
                 WHERE user_id = ?
                """, json(details), json(shopAddress), details.get("tradeName"), cleanGstin, me.userId());
        event(me.userId(), "BUSINESS", lat, lng, null, details);
        return view(me.userId(), false);
    }

    @Transactional
    public Map<String, Object> addDocument(AuthPrincipal me, String docType, MultipartFile file) {
        requirePartner(me);
        ensureProfile(me.userId());
        requireIdentityRow(me.userId());
        String type = required(docType, "Document type is required").toUpperCase(Locale.ROOT).replace(' ', '_');
        SpacesStorage.StoredObject stored = spaces.upload("business-proof", file);
        if ("SHOP_PHOTO".equals(type)) {
            jdbc.update("DELETE FROM partner_business_documents WHERE user_id = ? AND doc_type = 'SHOP_PHOTO'", me.userId());
        }
        jdbc.update("""
                INSERT INTO partner_business_documents (user_id, doc_type, storage_key, filename, content_type)
                VALUES (?, ?, ?, ?, ?)
                """, me.userId(), type, stored.key(), stored.filename(), stored.contentType());
        return view(me.userId(), false);
    }

    @Transactional
    public Map<String, Object> signAgreement(AuthPrincipal me, String signatoryName, String latitude, String longitude) {
        requirePartner(me);
        ensureProfile(me.userId());
        requireIdentityRow(me.userId());
        Map<String, Object> profile = profile(me.userId());
        String business = String.valueOf(profile.get("business_status"));
        if (!"DETAILS_CAPTURED".equals(business)
                && !"UNDER_REVIEW".equals(business)
                && !"REJECTED".equals(business)
                && !"BUSINESS_VERIFIED".equals(business)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_DETAILS_REQUIRED",
                    "Save the business details before signing");
        }
        String entityType = text(jsonMap(profile.get("business_details")).get("entityType"));
        requireProof(me.userId(), entityType);
        BigDecimal lat = location(latitude, true);
        BigDecimal lng = location(longitude, false);
        String signatory = required(signatoryName, "Authorized signatory is required");
        String ref = "BIZ-SUBMIT-" + me.userId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", "2026-11-06");
        details.put("signatoryName", signatory);
        details.put("providerRef", ref);
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET business_status = 'UNDER_REVIEW',
                       business_review_note = NULL,
                       updated_at = now()
                 WHERE user_id = ?
                """, me.userId());
        event(me.userId(), "AGREEMENT", lat, lng, ref, details);
        journeyTracker.recordUserMilestone(
                me.userId(), JourneyTracker.BUSINESS_SUBMITTED, "SUCCESS",
                Map.of("signatoryName", signatory, "providerRef", ref));
        return view(me.userId(), false);
    }

    @Transactional
    public Map<String, Object> reviewFace(AuthPrincipal me, UUID userId, boolean matched, String note) {
        ensureProfile(userId);
        faceReview.review(me, userId, matched, note);
        if (matched) {
            digilockerDocuments.syncPersonalIdentityVerified(userId);
        }
        return view(userId, true);
    }

    @Transactional
    public Map<String, Object> reviewPersonal(AuthPrincipal me, UUID userId, boolean approve, String note) {
        if (!me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only an admin can review personal identity");
        }
        access.assertNetworkUser(userId);
        ensureProfile(userId);
        Map<String, Object> profile = profile(userId);
        String cleanNote = blank(note);
        if (!approve && cleanNote == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOTE_REQUIRED",
                    "Provide a reason when rejecting personal identity verification");
        }
        if (approve) {
            String faceStatus = String.valueOf(profile.getOrDefault("face_review_status", "PENDING"));
            if (!"MATCHED".equals(faceStatus)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FACE_REVIEW_REQUIRED",
                        "Verify and mark the partner's live selfie as Matched against their Aadhaar photo before approving personal identity");
            }
            if (!digilockerDocuments.hasMandatoryDocumentsStored(userId)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DIGILOCKER_DOCUMENTS_MISSING",
                        "Mandatory DigiLocker documents (Aadhaar & PAN) must be verified before approving personal identity");
            }
            List<Map<String, Object>> docs = digilockerDocuments.listDocumentsForUser(userId);
            boolean anyRejected = docs.stream()
                    .anyMatch(d -> "REJECTED".equalsIgnoreCase(String.valueOf(d.get("reviewStatus"))));
            if (anyRejected) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DIGILOCKER_DOCUMENT_REJECTED",
                        "Cannot approve personal identity when a DigiLocker document is marked as Rejected");
            }
            // Mark any remaining PENDING reviewable DigiLocker documents for this user as APPROVED
            for (Map<String, Object> doc : docs) {
                if ("PENDING".equalsIgnoreCase(String.valueOf(doc.get("reviewStatus")))) {
                    UUID docId = (UUID) doc.get("id");
                    digilockerDocuments.reviewDocument(me, userId, docId, true, cleanNote, "127.0.0.1", "ADMIN_PERSONAL_APPROVAL");
                }
            }
            jdbc.update("""
                    UPDATE agent_kyc_profiles
                       SET personal_identity_verified = TRUE,
                           personal_status = 'PERSONAL_VERIFIED',
                           updated_at = now()
                     WHERE user_id = ?
                    """, userId);
        } else {
            jdbc.update("""
                    UPDATE agent_kyc_profiles
                       SET personal_identity_verified = FALSE,
                           personal_status = 'NOT_VERIFIED',
                           identity_status = 'REVERIFY_REQUIRED',
                           rejection_reason = ?,
                           updated_at = now()
                     WHERE user_id = ?
                    """, cleanNote, userId);
        }
        journeyTracker.recordUserMilestone(
                userId,
                approve ? "PERSONAL_IDENTITY_VERIFIED" : "PERSONAL_IDENTITY_REJECTED",
                approve ? "SUCCESS" : "FAILURE",
                Map.of("reviewedBy", me.userId().toString(), "note", cleanNote == null ? "" : cleanNote));
        return view(userId, true);
    }

    @Transactional
    public Map<String, Object> reviewBusiness(AuthPrincipal me, UUID userId, boolean approve, String note) {
        if (!me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only an admin can review business registrations");
        }
        access.assertNetworkUser(userId);
        ensureProfile(userId);
        Map<String, Object> profile = profile(userId);
        String current = String.valueOf(profile.get("business_status"));
        if ("NOT_VERIFIED".equals(current)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_NOT_SUBMITTED",
                    "Partner has not submitted business details yet");
        }
        String cleanNote = blank(note);
        if (!approve && cleanNote == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOTE_REQUIRED",
                    "Provide a reason when rejecting business verification");
        }
        if (approve) {
            boolean personalVerified = Boolean.TRUE.equals(profile.get("personal_identity_verified"));
            String faceStatus = String.valueOf(profile.getOrDefault("face_review_status", "PENDING"));
            if (!personalVerified && !"MATCHED".equals(faceStatus)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FACE_REVIEW_REQUIRED",
                        "Verify and mark the partner's live selfie as Matched against their Aadhaar photo before approving business registration");
            }
            if (!personalVerified && !digilockerDocuments.hasMandatoryDocumentsStored(userId)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DIGILOCKER_DOCUMENTS_MISSING",
                        "Mandatory DigiLocker documents (Aadhaar & PAN) must be verified before approving business registration");
            }
            if ("AWAITING_SIGNATORY_KYC".equals(current)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_KYC_REQUIRED",
                        "Authorized signatory has not completed their identity KYC yet");
            }
            if (businessRegistration != null) {
                Map<String, Object> sig = businessRegistration.getActiveSignatoryView(userId, true);
                if (sig != null && !Boolean.TRUE.equals(sig.get("isSelf"))) {
                    String sigStatus = String.valueOf(sig.get("status"));
                    if (!"KYC_COMPLETED".equals(sigStatus)) {
                        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_KYC_REQUIRED",
                                "Authorized signatory must complete identity KYC before business registration can be approved");
                    }
                    String sigFace = String.valueOf(sig.getOrDefault("faceReviewStatus", "PENDING"));
                    if (!"MATCHED".equals(sigFace)) {
                        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_FACE_REVIEW_REQUIRED",
                                "Verify and mark the authorized signatory's live selfie as Matched against their Aadhaar photo before approving business registration");
                    }
                }
            }
        }
        String nextStatus = approve ? "BUSINESS_VERIFIED" : "REJECTED";
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET business_status = ?,
                       business_verified = ?,
                       business_reviewed_by = ?,
                       business_reviewed_at = now(),
                       business_review_note = ?,
                       updated_at = now()
                 WHERE user_id = ?
                """, nextStatus, approve, me.userId(), cleanNote, userId);

        journeyTracker.recordUserMilestone(
                userId,
                approve ? JourneyTracker.BUSINESS_VERIFIED : JourneyTracker.BUSINESS_REJECTED,
                approve ? "SUCCESS" : "FAILURE",
                Map.of("reviewedBy", me.userId().toString(), "note", cleanNote == null ? "" : cleanNote));

        return view(userId, true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPermanentAddress(Map<String, Object> personalMap) {
        if (personalMap == null || personalMap.isEmpty()) {
            return Map.of();
        }
        Object permObj = personalMap.get("permanentAddress");
        if (permObj instanceof Map<?, ?> m && !m.isEmpty()) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        String city = text(personalMap.get("city"));
        String state = text(personalMap.get("state"));
        String pincode = text(personalMap.get("pincode"));
        String address = text(personalMap.get("address"));
        SurepassClient.AadhaarAddress fallback = new SurepassClient.AadhaarAddress(
                "", "", "", "", "", city, "", "", city, state, pincode, "India", address);
        return AddressFormatter.toPermanentAddressMap(
                fallback,
                text(personalMap.get("digilockerRef")),
                null);
    }

    private Map<String, Object> resolveBusinessAddress(
            String legacyAddress,
            AddressFormatter.BusinessAddressInput businessAddress,
            Boolean sameAsPersonalFlag,
            Map<String, Object> permanentAddress,
            Map<String, Object> personalMap,
            BigDecimal lat,
            BigDecimal lng) {
        boolean sameAsPersonal = Boolean.TRUE.equals(sameAsPersonalFlag)
                || (businessAddress != null && Boolean.TRUE.equals(businessAddress.sameAsPersonal()));
        if (sameAsPersonal && permanentAddress != null && !permanentAddress.isEmpty()) {
            return AddressFormatter.copyFromPermanentAddress(
                    permanentAddress,
                    text(personalMap.get("address")),
                    text(personalMap.get("city")),
                    text(personalMap.get("state")),
                    text(personalMap.get("pincode")),
                    null,
                    lat,
                    lng);
        }
        if (businessAddress != null && businessAddress.line1() != null && !businessAddress.line1().isBlank()) {
            return AddressFormatter.buildBusinessAddress(
                    businessAddress.line1(),
                    businessAddress.line2(),
                    businessAddress.area(),
                    businessAddress.landmark(),
                    businessAddress.city(),
                    businessAddress.district(),
                    businessAddress.state(),
                    businessAddress.pincode(),
                    null,
                    lat,
                    lng);
        }
        String rawLine = required(legacyAddress, "Business address is required");
        String fallbackCity = permanentAddress != null && !text(permanentAddress.get("vtc")).isBlank()
                ? text(permanentAddress.get("vtc"))
                : (personalMap != null && !text(personalMap.get("city")).isBlank()
                ? text(personalMap.get("city")) : "Mumbai");
        String fallbackDistrict = permanentAddress != null && !text(permanentAddress.get("district")).isBlank()
                ? text(permanentAddress.get("district")) : fallbackCity;
        String fallbackState = permanentAddress != null && !text(permanentAddress.get("state")).isBlank()
                ? text(permanentAddress.get("state"))
                : (personalMap != null && !text(personalMap.get("state")).isBlank()
                ? text(personalMap.get("state")) : "Maharashtra");
        String fallbackPincode = permanentAddress != null && text(permanentAddress.get("pincode")).matches("[1-9][0-9]{5}")
                ? text(permanentAddress.get("pincode"))
                : (personalMap != null && text(personalMap.get("pincode")).matches("[1-9][0-9]{5}")
                ? text(personalMap.get("pincode")) : "400001");
        return AddressFormatter.buildBusinessAddress(
                rawLine,
                "",
                "",
                fallbackCity,
                fallbackDistrict,
                fallbackState,
                fallbackPincode,
                null,
                lat,
                lng);
    }

    private void requireProof(UUID userId, String entityType) {
        Set<String> needed = REQUIRED_DOCS.getOrDefault(entityType, Set.of("ADDRESS_PROOF"));
        List<String> have = jdbc.queryForList(
                "SELECT DISTINCT doc_type FROM partner_business_documents WHERE user_id = ?", String.class, userId);
        boolean ok = have.stream().anyMatch(needed::contains);
        if (!ok) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_PROOF_REQUIRED",
                    "Upload the business proof for this entity before signing");
        }
    }

    private Map<String, Object> view(UUID userId, boolean includeAdminVault) {
        Map<String, Object> agreementRecord = agreements != null
                ? agreements.summaryForVerificationView(userId, includeAdminVault)
                : null;
        Map<String, Object> profile = profile(userId);
        Map<String, Object> identityMap = new LinkedHashMap<>(jsonMap(profile.get("identity_details")));
        Map<String, Object> personalMap = new LinkedHashMap<>(jsonMap(profile.get("personal_details")));
        if (personalMap.isEmpty() && !identityMap.isEmpty()) {
            personalMap = new LinkedHashMap<>(identityMap);
        }
        if (!personalMap.isEmpty() && !personalMap.containsKey("permanentAddress")) {
            personalMap.put("permanentAddress", extractPermanentAddress(personalMap));
        }
        if (!identityMap.isEmpty() && !identityMap.containsKey("permanentAddress")) {
            identityMap.put("permanentAddress", extractPermanentAddress(identityMap));
        }

        String selfieKey = (String) profile.get("selfie_key");
        String selfieUrl = null;
        if (selfieKey != null && !selfieKey.isBlank()) {
            try {
                selfieUrl = spaces.presignedGet(selfieKey);
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> faceReviewMap = new LinkedHashMap<>();
        faceReviewMap.put("status", profile.getOrDefault("face_review_status", "PENDING"));
        faceReviewMap.put("reviewedBy", profile.get("face_reviewed_by"));
        faceReviewMap.put("reviewedByName", profile.get("face_reviewed_by_name"));
        faceReviewMap.put("reviewedAt", profile.get("face_reviewed_at"));
        faceReviewMap.put("note", profile.get("face_review_note"));

        boolean personalIdentityVerified = Boolean.TRUE.equals(profile.get("personal_identity_verified"));
        boolean businessVerified = Boolean.TRUE.equals(profile.get("business_verified"))
                || "BUSINESS_VERIFIED".equals(profile.get("business_status"));
        boolean aadhaarPanLinked = Boolean.TRUE.equals(profile.get("aadhaar_pan_linked"))
                || Boolean.TRUE.equals(identityMap.get("aadhaarPanLinked"))
                || Boolean.TRUE.equals(identityMap.get("aadhaarLinked"));

        Map<String, Object> businessDetailsMap = jsonMap(profile.get("business_details"));
        String businessType = profile.get("business_type") != null
                ? String.valueOf(profile.get("business_type"))
                : (businessDetailsMap.get("businessType") != null ? String.valueOf(businessDetailsMap.get("businessType")) : null);

        Map<String, Object> workLocation = new LinkedHashMap<>();
        workLocation.put("latitude", profile.get("work_latitude"));
        workLocation.put("longitude", profile.get("work_longitude"));
        workLocation.put("source", profile.get("work_location_source"));

        Map<String, Object> signatoryView = null;
        List<Map<String, Object>> signatoryDocs = List.of();
        if (businessRegistration != null) {
            Map<String, Object> rawSig = businessRegistration.getActiveSignatoryView(userId, includeAdminVault);
            if (rawSig != null) {
                signatoryView = new LinkedHashMap<>(rawSig);
                UUID sigJourneyId = (UUID) signatoryView.remove("journeyId");
                String sigSelfieKey = (String) signatoryView.remove("selfieKey");
                String sigSelfieUrl = null;
                if (sigSelfieKey != null && !sigSelfieKey.isBlank()) {
                    try {
                        sigSelfieUrl = spaces.presignedGet(sigSelfieKey);
                    } catch (Exception ignored) {
                    }
                }
                signatoryView.put("selfieUrl", sigSelfieUrl);
                if (includeAdminVault && sigJourneyId != null) {
                    signatoryDocs = digilockerDocuments.listDocumentsForJourney(sigJourneyId);
                    signatoryView.put("digilockerDocuments", signatoryDocs);
                }
            }
        }

        String agreementStatus = String.valueOf(profile.getOrDefault("agreement_status", "NOT_SIGNED"));
        if (!includeAdminVault && "DRAFT".equals(agreementStatus)) {
            agreementStatus = "NOT_SIGNED";
        }
        boolean servicesUnlocked = businessVerified && "SIGNED".equals(agreementStatus);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("personalIdentityVerified", personalIdentityVerified);
        out.put("businessVerified", businessVerified);
        out.put("servicesUnlocked", servicesUnlocked);
        out.put("aadhaarPanLinked", aadhaarPanLinked);
        out.put("identityStatus", profile.get("identity_status"));
        out.put("identityVerifiedAt", profile.get("identity_verified_at"));
        out.put("bankVerificationStatus", profile.get("bank_verification_status"));
        out.put("personalStatus", personalIdentityVerified ? "PERSONAL_VERIFIED" : "NOT_VERIFIED");
        out.put("businessStatus", profile.get("business_status"));
        out.put("businessType", businessType);
        out.put("workLocation", workLocation);
        out.put("workAddress", jsonMap(profile.get("work_address")));
        out.put("signatory", signatoryView);
        out.put("agreementStatus", agreementStatus);
        out.put("agreementRecord", agreementRecord);
        out.put("aadhaarLast4", profile.get("aadhaar_last4"));
        out.put("hasFullAadhaar", Boolean.TRUE.equals(profile.get("has_full_aadhaar")));
        out.put("email", profile.get("user_email"));
        out.put("aadhaarVerifiedAt", profile.get("aadhaar_verified_at"));
        out.put("panLast4", profile.get("pan_last4"));
        out.put("panVerifiedAt", profile.get("pan_verified_at"));
        out.put("livenessScore", profile.get("liveness_score"));
        out.put("livenessPassedAt", profile.get("liveness_passed_at"));
        out.put("faceReviewStatus", profile.getOrDefault("face_review_status", "PENDING"));
        out.put("faceReview", faceReviewMap);
        out.put("shopName", profile.get("shop_name"));
        out.put("shopAddress", jsonMap(profile.get("shop_address")));
        out.put("gstin", profile.get("gstin"));
        out.put("selfieUrl", selfieUrl);
        out.put("businessReviewedBy", profile.get("business_reviewed_by"));
        out.put("businessReviewedAt", profile.get("business_reviewed_at"));
        out.put("businessReviewNote", profile.get("business_review_note"));
        out.put("identity", identityMap);
        out.put("personal", personalMap);
        out.put("business", businessDetailsMap);
        out.put("agreement", jsonMap(profile.get("agreement_details")));
        out.put("digilockerSummary", digilockerDocuments.summarizeForUser(userId));
        if (includeAdminVault) {
            out.put("digilockerDocuments", digilockerDocuments.listDocumentsForUser(userId));
            out.put("signatoryDocuments", signatoryDocs);
        }
        out.put("banks", jdbc.queryForList("""
                SELECT id, account_name, account_number, ifsc, bank_name,
                       COALESCE(account_type, 'SAVINGS') AS account_type,
                       branch_name, imps_ref_no, verification_remarks,
                       active, verification_status, verified_name, name_match_score, provider_ref,
                       verified_at, created_at
                  FROM retailer_bank_accounts
                 WHERE user_id = ?
                 ORDER BY active DESC, created_at DESC
                """, userId));
        out.put("documents", jdbc.queryForList("""
                SELECT id, doc_type, storage_key, filename, content_type, created_at
                  FROM partner_business_documents WHERE user_id = ? ORDER BY created_at DESC
                """, userId).stream().map(doc -> {
            Map<String, Object> d = new LinkedHashMap<>(doc);
            Object key = d.remove("storage_key");
            if (key != null) {
                try {
                    d.put("url", spaces.presignedGet(String.valueOf(key)));
                } catch (Exception ignored) {
                }
            }
            return d;
        }).toList());
        out.put("events", jdbc.queryForList("""
                SELECT step, latitude, longitude, provider_ref, created_at
                  FROM partner_verification_events WHERE user_id = ? ORDER BY created_at DESC
                """, userId));
        out.put("journeyEvents", journeyTracker.eventsForUser(userId));
        return out;
    }

    private Map<String, Object> profile(UUID userId) {
        try {
            return jdbc.queryForMap("""
                    SELECT p.identity_status, p.identity_verified_at, p.bank_verification_status,
                           p.personal_status, p.business_status, p.agreement_status,
                           COALESCE(p.personal_identity_verified, FALSE) AS personal_identity_verified,
                           COALESCE(p.business_verified, FALSE) AS business_verified,
                           COALESCE(p.aadhaar_pan_linked, FALSE) AS aadhaar_pan_linked,
                           (p.aadhaar_enc IS NOT NULL AND p.aadhaar_enc <> '') AS has_full_aadhaar,
                           u.email AS user_email,
                           p.aadhaar_last4, p.aadhaar_verified_at, p.pan_last4, p.pan_verified_at,
                           p.liveness_score, p.liveness_passed_at, p.shop_name, p.gstin, p.selfie_key,
                           p.face_review_status, p.face_reviewed_by, p.face_reviewed_at, p.face_review_note,
                           fr.full_name AS face_reviewed_by_name,
                           p.business_reviewed_by, p.business_reviewed_at, p.business_review_note,
                           p.business_type, p.work_latitude, p.work_longitude, p.work_location_source,
                           p.work_address::text AS work_address,
                           p.shop_address::text AS shop_address,
                           p.identity_details::text AS identity_details,
                           p.personal_details::text AS personal_details,
                           p.business_details::text AS business_details,
                           p.agreement_details::text AS agreement_details
                      FROM agent_kyc_profiles p
                      LEFT JOIN users u ON u.id = p.user_id
                      LEFT JOIN users fr ON fr.id = p.face_reviewed_by
                     WHERE p.user_id = ?
                    """, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND", "Verification profile was not found");
        }
    }

    private void ensureProfile(UUID userId) {
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, kyc_status)
                VALUES (?, 'PENDING')
                ON CONFLICT (user_id) DO NOTHING
                """, userId);
    }

    private void requireIdentityRow(UUID userId) {
        String status = jdbc.queryForObject(
                "SELECT identity_status FROM agent_kyc_profiles WHERE user_id = ?", String.class, userId);
        if (!"VERIFIED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_VERIFICATION_REQUIRED",
                    "Complete identity verification first");
        }
    }

    private void event(UUID userId, String step, BigDecimal lat, BigDecimal lng, String ref, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO partner_verification_events (user_id, step, latitude, longitude, provider_ref, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, userId, step, lat, lng, ref, json(payload));
    }

    private void requirePartner(AuthPrincipal me) {
        if (me == null || (!"RETAILER".equals(me.userType()) && !"MASTER_DISTRIBUTOR".equals(me.userType()))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a retailer or distributor can verify");
        }
    }

    private static String entity(String value) {
        String type = required(value, "Entity type is required").toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!ENTITY_TYPES.contains(type)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ENTITY", "Choose a valid entity type");
        }
        return type;
    }

    private static BigDecimal location(String raw, boolean latitude) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Allow location so this verification can be recorded");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Allow location so this verification can be recorded");
        }
        double n = value.doubleValue();
        if (latitude && (n < -90 || n > 90)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED", "Latitude is not valid");
        }
        if (!latitude && (n < -180 || n > 180)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED", "Longitude is not valid");
        }
        return value;
    }

    private static String required(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", message);
        }
        return value.trim();
    }

    private static String blank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store verification details", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(String.valueOf(raw), Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
