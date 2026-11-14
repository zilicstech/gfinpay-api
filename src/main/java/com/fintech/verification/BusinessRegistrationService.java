package com.fintech.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.AdminAccess;
import com.fintech.kyc.surepass.SurepassClient;
import com.fintech.onboarding.JourneyTracker;
import com.fintech.onboarding.NameMatcher;
import com.fintech.onboarding.OtpService;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PublicAppUrl;
import com.fintech.sms.SmsSender;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(BusinessRegistrationService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SurepassClient surepass;
    private final JourneyTracker journeyTracker;
    private final SmsSender smsSender;
    private final AdminAccess access;
    private final FieldCipher fieldCipher;
    private final String publicAppUrl;
    private final int inviteTtlDays;

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessRegistrationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            SurepassClient surepass,
            JourneyTracker journeyTracker,
            SmsSender smsSender,
            AdminAccess access,
            FieldCipher fieldCipher,
            @Value("${app.public-app-url:http://localhost:3001}") String publicAppUrl,
            @Value("${app.onboarding.invite-ttl-days:7}") int inviteTtlDays) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.surepass = surepass;
        this.journeyTracker = journeyTracker;
        this.smsSender = smsSender;
        this.access = access;
        this.fieldCipher = fieldCipher;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
        this.inviteTtlDays = inviteTtlDays;
    }

    public BusinessRegistrationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            SurepassClient surepass,
            JourneyTracker journeyTracker,
            SmsSender smsSender,
            AdminAccess access,
            String publicAppUrl,
            int inviteTtlDays) {
        this(jdbc, mapper, surepass, journeyTracker, smsSender, access, null, publicAppUrl, inviteTtlDays);
    }

    public Map<String, Object> getKycLocation(AuthPrincipal me) {
        requirePartner(me);
        UUID userId = me.userId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT latitude, longitude, created_at
                  FROM partner_verification_events
                 WHERE user_id = ? AND step = 'PERSONAL'
                 ORDER BY created_at DESC
                 LIMIT 1
                """, userId);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", true);
            out.put("latitude", row.get("latitude"));
            out.put("longitude", row.get("longitude"));
            out.put("capturedAt", row.get("created_at"));
            return out;
        }
        // Fallback: check onboarding_journeys identity JSON
        List<String> idRows = jdbc.queryForList("""
                SELECT identity::text
                  FROM onboarding_journeys
                 WHERE user_id = ?
                 ORDER BY started_at DESC
                 LIMIT 1
                """, String.class, userId);
        if (!idRows.isEmpty()) {
            Map<String, Object> idMap = jsonMap(idRows.get(0));
            BigDecimal lat = toBigDecimal(idMap.get("latitude"));
            BigDecimal lng = toBigDecimal(idMap.get("longitude"));
            if (lat != null && lng != null) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("available", true);
                out.put("latitude", lat);
                out.put("longitude", lng);
                out.put("capturedAt", null);
                return out;
            }
        }
        return Map.of("available", false);
    }

    @Transactional
    public void submitIndividual(
            AuthPrincipal me,
            String locationSourceRaw,
            String latitudeRaw,
            String longitudeRaw,
            String shopNameRaw,
            AddressFormatter.BusinessAddressInput customAddress) {
        submitIndividual(me, locationSourceRaw, latitudeRaw, longitudeRaw, shopNameRaw, customAddress, null, null, null);
    }

    @Transactional
    public void submitIndividual(
            AuthPrincipal me,
            String locationSourceRaw,
            String latitudeRaw,
            String longitudeRaw,
            String shopNameRaw,
            AddressFormatter.BusinessAddressInput customAddress,
            String emailRaw,
            String aadhaarRaw,
            String gstinRaw) {
        requirePartner(me);
        UUID userId = me.userId();
        ensureProfileAndRequireIdentity(userId);

        String locationSource = (locationSourceRaw == null || locationSourceRaw.isBlank())
                ? "CURRENT_DEVICE"
                : locationSourceRaw.trim().toUpperCase(Locale.ROOT);
        if (!"KYC_LOCATION".equals(locationSource) && !"CURRENT_DEVICE".equals(locationSource)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LOCATION_SOURCE",
                    "Location source must be KYC_LOCATION or CURRENT_DEVICE");
        }

        BigDecimal lat;
        BigDecimal lng;
        if ("KYC_LOCATION".equals(locationSource)) {
            Map<String, Object> kycLoc = getKycLocation(me);
            if (!Boolean.TRUE.equals(kycLoc.get("available"))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "KYC_LOCATION_UNAVAILABLE",
                        "No location was recorded during your personal KYC. Please capture your current location.");
            }
            lat = toBigDecimal(kycLoc.get("latitude"));
            lng = toBigDecimal(kycLoc.get("longitude"));
        } else {
            lat = validateCoordinate(latitudeRaw, true);
            lng = validateCoordinate(longitudeRaw, false);
        }

        Map<String, Object> profile = loadProfile(userId);
        Map<String, Object> userRow = loadUser(userId);
        Map<String, Object> personalMap = jsonMap(profile.get("personal_details"));
        if (personalMap.isEmpty()) {
            personalMap = jsonMap(profile.get("identity_details"));
        }
        Map<String, Object> permanentAddress = extractPermanentAddress(personalMap);

        String verifiedName = firstNonBlank(
                text(personalMap.get("fullName")),
                text(userRow.get("full_name")));
        String shopName = required(shopNameRaw, "Shop or trade name is required");
        String resolvedEmail = resolveAndPersistEmail(userId, emailRaw, text(userRow.get("email")));
        String aadhaarEnc = resolveEncryptedAadhaar(
                aadhaarRaw, text(profile.get("aadhaar_last4")), text(profile.get("aadhaar_enc")));
        String cleanGstin = normalizeOptionalGstin(gstinRaw, text(profile.get("gstin")));

        Map<String, Object> workAddress;
        boolean sameAsPersonal = customAddress == null
                || Boolean.TRUE.equals(customAddress.sameAsPersonal())
                || customAddress.line1() == null
                || customAddress.line1().isBlank();
        if (sameAsPersonal) {
            workAddress = AddressFormatter.copyFromPermanentAddress(
                    permanentAddress,
                    text(personalMap.get("address")),
                    text(personalMap.get("city")),
                    text(personalMap.get("state")),
                    text(personalMap.get("pincode")),
                    null,
                    lat,
                    lng);
        } else {
            workAddress = AddressFormatter.buildBusinessAddress(
                    customAddress.line1(),
                    customAddress.line2(),
                    customAddress.area(),
                    customAddress.landmark(),
                    customAddress.city(),
                    customAddress.district(),
                    customAddress.state(),
                    customAddress.pincode(),
                    null,
                    lat,
                    lng);
        }

        String fullAddressText = text(workAddress.get("fullAddress"));
        boolean differsFromPersonal = AddressFormatter.differsFromPersonal(
                workAddress,
                permanentAddress,
                text(personalMap.get("pincode")),
                text(personalMap.get("state")));

        // Deactivate any active company signatory if switching from Company to Individual
        jdbc.update("""
                UPDATE business_signatories
                   SET active = FALSE,
                       status = CASE WHEN status IN ('LINK_SENT', 'IN_PROGRESS') THEN 'REVOKED' ELSE status END,
                       updated_at = now()
                 WHERE user_id = ? AND active = TRUE
                """, userId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("businessType", "INDIVIDUAL");
        details.put("entityType", "INDIVIDUAL");
        details.put("legalName", verifiedName);
        details.put("tradeName", shopName);
        if (!resolvedEmail.isEmpty()) {
            details.put("email", resolvedEmail);
        }
        if (cleanGstin != null) {
            details.put("gstin", cleanGstin);
        }
        details.put("address", workAddress);
        details.put("addressText", fullAddressText);
        details.put("addressDiffersFromPersonal", differsFromPersonal);
        details.put("workLocationSource", locationSource);
        details.put("workLatitude", lat);
        details.put("workLongitude", lng);
        details.put("signatoryName", verifiedName);
        details.put("isSelfSignatory", true);
        details.put("signatory", Map.of(
                "name", verifiedName,
                "isSelf", true,
                "status", "SELF_VERIFIED"
        ));
        details.put("submittedAt", Instant.now().toString());

        Map<String, Object> shopAddress = new LinkedHashMap<>(workAddress);
        shopAddress.put("address", fullAddressText);

        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET business_type = 'INDIVIDUAL',
                       business_status = 'UNDER_REVIEW',
                       business_verified = FALSE,
                       business_review_note = NULL,
                       work_latitude = ?,
                       work_longitude = ?,
                       work_location_source = ?,
                       work_address = ?::jsonb,
                       business_details = ?::jsonb,
                       shop_address = ?::jsonb,
                       shop_name = ?,
                       gstin = COALESCE(?, gstin),
                       aadhaar_enc = COALESCE(?, aadhaar_enc),
                       updated_at = now()
                 WHERE user_id = ?
                """,
                lat, lng, locationSource, json(workAddress), json(details), json(shopAddress),
                shopName, cleanGstin, aadhaarEnc, userId);

        recordBusinessEvent(userId, lat, lng, null, details);
        journeyTracker.recordUserMilestone(
                userId, JourneyTracker.BUSINESS_SUBMITTED, "SUCCESS",
                Map.of("businessType", "INDIVIDUAL", "locationSource", locationSource, "signatoryName", verifiedName));
    }

    public Map<String, Object> lookupCompany(AuthPrincipal me, String cinRaw) {
        requirePartner(me);
        UUID userId = me.userId();
        ensureProfileAndRequireIdentity(userId);

        String cin = validateCin(cinRaw);
        SurepassClient.CompanyRecord rec = surepass.company(cin);
        if (!rec.valid() || rec.companyName() == null || rec.companyName().isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CIN",
                    "Company registration could not be verified for CIN/LLPIN " + cin);
        }
        if (!isCompanyActive(rec.status())) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "COMPANY_NOT_ACTIVE",
                    "Company status in MCA is '" + rec.status() + "'. Only Active companies can be registered.");
        }

        String partnerVerifiedName = resolvePartnerVerifiedName(userId);
        List<Map<String, Object>> activeDirectors = new ArrayList<>();
        String suggestedDin = null;
        int bestSelfScore = -1;

        for (SurepassClient.Director dir : rec.directors()) {
            if (!dir.active()) {
                continue;
            }
            int score = NameMatcher.score(dir.name(), partnerVerifiedName);
            boolean isSelf = score >= NameMatcher.DEFAULT_THRESHOLD;
            if (isSelf && score > bestSelfScore) {
                bestSelfScore = score;
                suggestedDin = dir.din();
            }
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("name", dir.name());
            dm.put("din", dir.din());
            dm.put("startDate", dir.startDate());
            dm.put("endDate", dir.endDate());
            dm.put("active", true);
            dm.put("isSelf", isSelf);
            dm.put("matchScore", score);
            activeDirectors.add(dm);
        }

        if (activeDirectors.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NO_ACTIVE_DIRECTORS",
                    "No active directors were found in MCA records for this company");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cin", rec.cin());
        out.put("companyName", rec.companyName());
        out.put("companyType", rec.companyType());
        out.put("classOfCompany", rec.classOfCompany());
        out.put("status", rec.status());
        out.put("dateOfIncorporation", rec.dateOfIncorporation());
        out.put("rocCode", rec.rocCode());
        out.put("registeredAddress", rec.registeredAddress());
        out.put("pincode", rec.pincode());
        out.put("providerRef", rec.providerRef());
        out.put("partnerVerifiedName", partnerVerifiedName);
        out.put("suggestedDin", suggestedDin);
        out.put("directors", activeDirectors);
        return out;
    }

    @Transactional
    public void submitCompany(
            AuthPrincipal me,
            String cinRaw,
            String dinRaw,
            String shopNameRaw,
            String signatoryMobileRaw,
            String latitudeRaw,
            String longitudeRaw) {
        submitCompany(me, cinRaw, dinRaw, shopNameRaw, signatoryMobileRaw, latitudeRaw, longitudeRaw,
                null, null, null, null);
    }

    @Transactional
    public void submitCompany(
            AuthPrincipal me,
            String cinRaw,
            String dinRaw,
            String shopNameRaw,
            String signatoryMobileRaw,
            String latitudeRaw,
            String longitudeRaw,
            AddressFormatter.BusinessAddressInput operatingAddress,
            String emailRaw,
            String aadhaarRaw,
            String gstinRaw) {
        requirePartner(me);
        UUID userId = me.userId();
        ensureProfileAndRequireIdentity(userId);

        String shopName = required(shopNameRaw, "Shop or trade name is required");
        BigDecimal lat = validateCoordinate(latitudeRaw, true);
        BigDecimal lng = validateCoordinate(longitudeRaw, false);
        String cin = validateCin(cinRaw);
        String din = required(dinRaw, "Select an authorized signatory from the board of directors");

        SurepassClient.CompanyRecord rec = surepass.company(cin);
        if (!rec.valid() || rec.companyName() == null || rec.companyName().isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CIN",
                    "Company registration could not be verified for CIN/LLPIN " + cin);
        }
        if (!isCompanyActive(rec.status())) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "COMPANY_NOT_ACTIVE",
                    "Company status in MCA is '" + rec.status() + "'. Only Active companies can be registered.");
        }

        SurepassClient.Director chosenDirector = rec.directors().stream()
                .filter(SurepassClient.Director::active)
                .filter(d -> din.equalsIgnoreCase(d.din()) || din.equalsIgnoreCase(d.name()))
                .findFirst()
                .orElseThrow(() -> ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIRECTOR",
                        "Selected director is not an active director of " + rec.companyName()));

        Map<String, Object> userRow = loadUser(userId);
        String partnerVerifiedName = resolvePartnerVerifiedName(userId);
        int selfMatchScore = NameMatcher.score(chosenDirector.name(), partnerVerifiedName);
        boolean isSelf = selfMatchScore >= NameMatcher.DEFAULT_THRESHOLD;

        // Deactivate any previous active signatory for this partner
        jdbc.update("""
                UPDATE business_signatories
                   SET active = FALSE,
                       status = CASE WHEN status IN ('LINK_SENT', 'IN_PROGRESS') THEN 'REVOKED' ELSE status END,
                       updated_at = now()
                 WHERE user_id = ? AND active = TRUE
                """, userId);

        UUID signatoryId = UUID.randomUUID();
        String nextBusinessStatus;
        String signatoryUrl = null;
        String maskedSignatoryMobile = null;
        Instant expiresAt = null;

        if (isSelf) {
            jdbc.update("""
                    INSERT INTO business_signatories
                        (id, user_id, cin, director_name, din, is_self, status,
                         verified_name, name_match_score, face_review_status, active, completed_at)
                    VALUES (?, ?, ?, ?, ?, TRUE, 'SELF_VERIFIED', ?, ?, 'MATCHED', TRUE, now())
                    """,
                    signatoryId, userId, rec.cin(), chosenDirector.name(), chosenDirector.din(),
                    partnerVerifiedName, selfMatchScore);
            nextBusinessStatus = "UNDER_REVIEW";
        } else {
            String signatoryMobile = validateMobile(signatoryMobileRaw);
            String partnerMobile = validateMobile(String.valueOf(userRow.get("mobile")));
            if (signatoryMobile.equals(partnerMobile)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_MOBILE_CONFLICT",
                        "Enter the authorized signatory's own mobile number (must be different from your registered mobile number)");
            }

            UUID journeyId = journeyTracker.createJourney(null, null, "SIGNATORY", JourneyTracker.LINK_SENT);
            String rawToken = newOpaqueToken();
            String tokenHash = sha256(rawToken);
            expiresAt = Instant.now().plus(Duration.ofDays(inviteTtlDays));
            signatoryUrl = buildSignatoryLink(rawToken);
            maskedSignatoryMobile = OtpService.maskMobile(signatoryMobile);

            Map<String, Object> initIdentity = new LinkedHashMap<>();
            initIdentity.put("signatoryUrl", signatoryUrl);
            initIdentity.put("companyName", rec.companyName());
            initIdentity.put("cin", rec.cin());
            initIdentity.put("directorName", chosenDirector.name());
            initIdentity.put("din", chosenDirector.din());
            journeyTracker.mergeIdentity(journeyId, initIdentity);

            jdbc.update("""
                    INSERT INTO business_signatories
                        (id, user_id, cin, director_name, din, is_self, mobile,
                         token_hash, journey_id, status, face_review_status,
                         identity_details, active, expires_at)
                    VALUES (?, ?, ?, ?, ?, FALSE, ?, ?, ?, 'LINK_SENT', 'PENDING', ?::jsonb, TRUE, ?)
                    """,
                    signatoryId, userId, rec.cin(), chosenDirector.name(), chosenDirector.din(),
                    signatoryMobile, tokenHash, journeyId, json(Map.of("signatoryUrl", signatoryUrl)),
                    Timestamp.from(expiresAt));

            journeyTracker.record(
                    journeyId, null, JourneyTracker.LINK_SENT, "SUCCESS",
                    Map.of("companyName", rec.companyName(), "directorName", chosenDirector.name(),
                            "mobile", maskedSignatoryMobile),
                    lat, lng, null, null, true);

            smsSender.send(signatoryMobile, "SIGNATORY_KYC_INVITE", Map.of(
                    "name", chosenDirector.name(),
                    "company", rec.companyName(),
                    "link", signatoryUrl
            ));
            nextBusinessStatus = "AWAITING_SIGNATORY_KYC";
        }

        List<Map<String, Object>> directorSnapshots = rec.directors().stream()
                .filter(SurepassClient.Director::active)
                .map(d -> {
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("name", d.name());
                    dm.put("din", d.din());
                    dm.put("startDate", d.startDate());
                    dm.put("active", d.active());
                    dm.put("selected", d.din().equalsIgnoreCase(chosenDirector.din()));
                    return dm;
                })
                .toList();

        Map<String, Object> profile = loadProfile(userId);
        String resolvedEmail = resolveAndPersistEmail(userId, emailRaw, text(userRow.get("email")));
        String aadhaarEnc = resolveEncryptedAadhaar(
                aadhaarRaw, text(profile.get("aadhaar_last4")), text(profile.get("aadhaar_enc")));
        String cleanGstin = normalizeOptionalGstin(gstinRaw, text(profile.get("gstin")));

        Map<String, Object> workAddress;
        if (operatingAddress != null && operatingAddress.line1() != null && !operatingAddress.line1().isBlank()) {
            workAddress = AddressFormatter.buildBusinessAddress(
                    operatingAddress.line1(),
                    operatingAddress.line2(),
                    operatingAddress.area(),
                    operatingAddress.landmark(),
                    operatingAddress.city(),
                    operatingAddress.district(),
                    operatingAddress.state(),
                    firstNonBlank(operatingAddress.pincode(), rec.pincode()),
                    rec.registeredAddress(),
                    lat,
                    lng);
        } else {
            workAddress = new LinkedHashMap<>();
            workAddress.put("line1", rec.registeredAddress());
            workAddress.put("fullAddress", rec.registeredAddress());
            workAddress.put("pincode", rec.pincode());
            workAddress.put("sameAsPersonal", false);
            workAddress.put("latitude", lat);
            workAddress.put("longitude", lng);
        }
        String addressText = firstNonBlank(text(workAddress.get("fullAddress")), rec.registeredAddress());

        Map<String, Object> companyMap = new LinkedHashMap<>();
        companyMap.put("cin", rec.cin());
        companyMap.put("companyName", rec.companyName());
        companyMap.put("companyType", rec.companyType());
        companyMap.put("classOfCompany", rec.classOfCompany());
        companyMap.put("status", rec.status());
        companyMap.put("dateOfIncorporation", rec.dateOfIncorporation());
        companyMap.put("rocCode", rec.rocCode());
        companyMap.put("registeredAddress", rec.registeredAddress());
        companyMap.put("pincode", rec.pincode());
        companyMap.put("providerRef", rec.providerRef());
        companyMap.put("directors", directorSnapshots);
        companyMap.put("fetchedAt", Instant.now().toString());

        Map<String, Object> signatorySnapshot = new LinkedHashMap<>();
        signatorySnapshot.put("id", signatoryId);
        signatorySnapshot.put("name", chosenDirector.name());
        signatorySnapshot.put("din", chosenDirector.din());
        signatorySnapshot.put("isSelf", isSelf);
        signatorySnapshot.put("status", isSelf ? "SELF_VERIFIED" : "LINK_SENT");
        if (maskedSignatoryMobile != null) {
            signatorySnapshot.put("maskedMobile", maskedSignatoryMobile);
        }
        if (signatoryUrl != null) {
            signatorySnapshot.put("signatoryUrl", signatoryUrl);
        }
        if (expiresAt != null) {
            signatorySnapshot.put("expiresAt", expiresAt.toString());
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("businessType", "COMPANY");
        details.put("entityType", "PRIVATE_LIMITED");
        details.put("legalName", rec.companyName());
        details.put("tradeName", shopName);
        details.put("cin", rec.cin());
        if (!resolvedEmail.isEmpty()) {
            details.put("email", resolvedEmail);
        }
        if (cleanGstin != null) {
            details.put("gstin", cleanGstin);
        }
        details.put("address", workAddress);
        details.put("addressText", addressText);
        details.put("workLocationSource", "CURRENT_DEVICE");
        details.put("workLatitude", lat);
        details.put("workLongitude", lng);
        details.put("signatoryName", chosenDirector.name());
        details.put("company", companyMap);
        details.put("signatory", signatorySnapshot);
        details.put("submittedAt", Instant.now().toString());

        Map<String, Object> shopAddress = new LinkedHashMap<>(workAddress);
        shopAddress.put("address", addressText);

        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET business_type = 'COMPANY',
                       business_status = ?,
                       business_verified = FALSE,
                       business_review_note = NULL,
                       work_latitude = ?,
                       work_longitude = ?,
                       work_location_source = 'CURRENT_DEVICE',
                       work_address = ?::jsonb,
                       business_details = ?::jsonb,
                       shop_address = ?::jsonb,
                       shop_name = ?,
                       gstin = COALESCE(?, gstin),
                       aadhaar_enc = COALESCE(?, aadhaar_enc),
                       updated_at = now()
                 WHERE user_id = ?
                """,
                nextBusinessStatus, lat, lng, json(workAddress), json(details), json(shopAddress),
                shopName, cleanGstin, aadhaarEnc, userId);

        recordBusinessEvent(userId, lat, lng, rec.providerRef(), details);
        journeyTracker.recordUserMilestone(
                userId, JourneyTracker.BUSINESS_SUBMITTED, "SUCCESS",
                Map.of("businessType", "COMPANY", "cin", rec.cin(), "companyName", rec.companyName(),
                        "signatoryName", chosenDirector.name(), "isSelf", isSelf));
    }

    @Transactional
    public Map<String, Object> resendSignatoryLink(AuthPrincipal me) {
        requirePartner(me);
        UUID userId = me.userId();
        Map<String, Object> sig = loadActiveSignatoryOrThrow(userId);
        if (Boolean.TRUE.equals(sig.get("is_self"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_IS_SELF",
                    "You are already the verified authorized signatory");
        }
        String status = String.valueOf(sig.get("status"));
        if ("KYC_COMPLETED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_ALREADY_COMPLETED",
                    "Authorized signatory has already completed KYC");
        }

        String rawToken = newOpaqueToken();
        String tokenHash = sha256(rawToken);
        Instant expiresAt = Instant.now().plus(Duration.ofDays(inviteTtlDays));
        String signatoryUrl = buildSignatoryLink(rawToken);
        UUID sigId = (UUID) sig.get("id");
        UUID journeyId = (UUID) sig.get("journey_id");
        String mobile = String.valueOf(sig.get("mobile"));
        String directorName = String.valueOf(sig.get("director_name"));

        Map<String, Object> idDetails = new LinkedHashMap<>(jsonMap(sig.get("identity_details")));
        idDetails.put("signatoryUrl", signatoryUrl);

        jdbc.update("""
                UPDATE business_signatories
                   SET token_hash = ?,
                       session_hash = NULL,
                       session_expires_at = NULL,
                       status = CASE WHEN status = 'EXPIRED' THEN 'LINK_SENT' ELSE status END,
                       identity_details = ?::jsonb,
                       expires_at = ?,
                       updated_at = now()
                 WHERE id = ?
                """, tokenHash, json(idDetails), Timestamp.from(expiresAt), sigId);

        if (journeyId != null) {
            journeyTracker.mergeIdentity(journeyId, Map.of("signatoryUrl", signatoryUrl));
            journeyTracker.record(
                    journeyId, null, JourneyTracker.LINK_SENT, "INFO",
                    Map.of("action", "RESENT", "mobile", OtpService.maskMobile(mobile)),
                    null, null, null, null, false);
        }

        smsSender.send(mobile, "SIGNATORY_KYC_INVITE_RESEND", Map.of(
                "name", directorName,
                "link", signatoryUrl
        ));

        return Map.of(
                "signatoryUrl", signatoryUrl,
                "expiresAt", expiresAt.toString(),
                "maskedMobile", OtpService.maskMobile(mobile));
    }

    @Transactional
    public void changeSignatory(AuthPrincipal me) {
        requirePartner(me);
        UUID userId = me.userId();
        Map<String, Object> profile = loadProfile(userId);
        String businessStatus = String.valueOf(profile.get("business_status"));
        if ("BUSINESS_VERIFIED".equals(businessStatus)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_ALREADY_VERIFIED",
                    "Business is already verified and cannot be modified");
        }
        jdbc.update("""
                UPDATE business_signatories
                   SET active = FALSE,
                       status = 'REVOKED',
                       updated_at = now()
                 WHERE user_id = ? AND active = TRUE
                """, userId);
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET business_status = 'DETAILS_CAPTURED',
                       updated_at = now()
                 WHERE user_id = ?
                """, userId);
    }

    @Transactional
    public void reviewSignatoryFace(AuthPrincipal me, UUID userId, boolean matched, String note) {
        if (me == null || !me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only platform administrators can review signatory face verification");
        }
        access.assertNetworkUser(userId);
        Map<String, Object> sig = loadActiveSignatoryOrThrow(userId);
        String cleanNote = (note == null || note.isBlank()) ? null : note.trim();
        if (!matched && cleanNote == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOTE_REQUIRED",
                    "Provide a note explaining why the signatory's selfie did not match");
        }
        String nextStatus = matched ? "MATCHED" : "MISMATCH";
        jdbc.update("""
                UPDATE business_signatories
                   SET face_review_status = ?,
                       face_reviewed_by = ?,
                       face_reviewed_at = now(),
                       face_review_note = ?,
                       updated_at = now()
                 WHERE id = ?
                """, nextStatus, me.userId(), cleanNote, sig.get("id"));
    }

    public Map<String, Object> getActiveSignatoryView(UUID userId, boolean includeAdminFields) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.*, u.full_name AS face_reviewed_by_name,
                       j.current_step AS journey_step
                  FROM business_signatories s
                  LEFT JOIN users u ON u.id = s.face_reviewed_by
                  LEFT JOIN onboarding_journeys j ON j.id = s.journey_id
                 WHERE s.user_id = ? AND s.active = TRUE
                 LIMIT 1
                """, userId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> s = rows.get(0);
        Map<String, Object> idDetails = jsonMap(s.get("identity_details"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.get("id"));
        out.put("cin", s.get("cin"));
        out.put("directorName", s.get("director_name"));
        out.put("din", s.get("din"));
        out.put("isSelf", s.get("is_self"));
        out.put("status", s.get("status"));
        out.put("currentStep", s.get("journey_step"));
        if (s.get("mobile") != null) {
            out.put("maskedMobile", OtpService.maskMobile(String.valueOf(s.get("mobile"))));
        }
        out.put("verifiedName", s.get("verified_name"));
        out.put("nameMatchScore", s.get("name_match_score"));
        out.put("aadhaarLast4", s.get("aadhaar_last4"));
        out.put("panLast4", s.get("pan_last4"));
        out.put("aadhaarPanLinked", Boolean.TRUE.equals(s.get("aadhaar_pan_linked")));
        out.put("faceReviewStatus", s.get("face_review_status"));
        out.put("faceReviewedBy", s.get("face_reviewed_by"));
        out.put("faceReviewedByName", s.get("face_reviewed_by_name"));
        out.put("faceReviewedAt", s.get("face_reviewed_at"));
        out.put("faceReviewNote", s.get("face_review_note"));
        out.put("selfieKey", s.get("selfie_key"));
        out.put("journeyId", s.get("journey_id"));
        out.put("expiresAt", s.get("expires_at"));
        out.put("completedAt", s.get("completed_at"));
        if (idDetails.get("signatoryUrl") != null) {
            out.put("signatoryUrl", idDetails.get("signatoryUrl"));
        }
        if (includeAdminFields) {
            out.put("identity", idDetails);
        }
        return out;
    }

    private Map<String, Object> loadActiveSignatoryOrThrow(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, user_id, cin, director_name, din, is_self, mobile, journey_id,
                       status, identity_details::text AS identity_details
                  FROM business_signatories
                 WHERE user_id = ? AND active = TRUE
                 LIMIT 1
                """, userId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SIGNATORY_NOT_FOUND",
                    "No active authorized signatory found for this business");
        }
        return rows.get(0);
    }

    private String resolvePartnerVerifiedName(UUID userId) {
        Map<String, Object> profile = loadProfile(userId);
        Map<String, Object> personalMap = jsonMap(profile.get("personal_details"));
        if (personalMap.isEmpty()) {
            personalMap = jsonMap(profile.get("identity_details"));
        }
        String fromKyc = text(personalMap.get("fullName"));
        if (!fromKyc.isBlank()) {
            return fromKyc;
        }
        Map<String, Object> userRow = loadUser(userId);
        return text(userRow.get("full_name"));
    }

    private void ensureProfileAndRequireIdentity(UUID userId) {
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, kyc_status)
                VALUES (?, 'PENDING')
                ON CONFLICT (user_id) DO NOTHING
                """, userId);
        String status = jdbc.queryForObject(
                "SELECT identity_status FROM agent_kyc_profiles WHERE user_id = ?", String.class, userId);
        if (!"VERIFIED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_VERIFICATION_REQUIRED",
                    "Complete personal identity verification before registering your business");
        }
    }

    private Map<String, Object> loadProfile(UUID userId) {
        try {
            return jdbc.queryForMap("""
                    SELECT business_status, aadhaar_last4, aadhaar_enc, gstin,
                           personal_details::text AS personal_details,
                           identity_details::text AS identity_details
                      FROM agent_kyc_profiles
                     WHERE user_id = ?
                    """, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND", "Verification profile not found");
        }
    }

    private String resolveAndPersistEmail(UUID userId, String emailRaw, String existingEmail) {
        String candidate = firstNonBlank(emailRaw, existingEmail).toLowerCase(Locale.ROOT);
        if (candidate.isEmpty()) {
            return "";
        }
        if (!candidate.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL",
                    "Enter a valid email address");
        }
        if (emailRaw != null && !emailRaw.isBlank() && !candidate.equalsIgnoreCase(existingEmail)) {
            jdbc.update("UPDATE users SET email = ? WHERE id = ?", candidate, userId);
        }
        return candidate;
    }

    private String resolveEncryptedAadhaar(String aadhaarRaw, String aadhaarLast4, String existingEnc) {
        if (aadhaarRaw == null || aadhaarRaw.isBlank()) {
            return (existingEnc != null && !existingEnc.isBlank()) ? existingEnc : null;
        }
        String digits = AadhaarValidator.normalizeAndValidate(aadhaarRaw, aadhaarLast4);
        if (fieldCipher == null) {
            return existingEnc;
        }
        return fieldCipher.encrypt(digits);
    }

    private static String normalizeOptionalGstin(String gstinRaw, String existingGstin) {
        if (gstinRaw == null || gstinRaw.isBlank()) {
            return (existingGstin != null && !existingGstin.isBlank()) ? existingGstin : null;
        }
        String clean = gstinRaw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (!clean.matches("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GSTIN",
                    "Enter a valid 15-character GSTIN");
        }
        return clean;
    }

    private Map<String, Object> loadUser(UUID userId) {
        return jdbc.queryForMap("SELECT id, full_name, mobile, email FROM users WHERE id = ?", userId);
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

    private void recordBusinessEvent(UUID userId, BigDecimal lat, BigDecimal lng, String ref, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO partner_verification_events (user_id, step, latitude, longitude, provider_ref, payload)
                VALUES (?, 'BUSINESS', ?, ?, ?, ?::jsonb)
                """, userId, lat, lng, ref, json(payload));
    }

    private String buildSignatoryLink(String token) {
        String base = (publicAppUrl == null || publicAppUrl.isBlank()) ? "http://localhost:3001" : publicAppUrl;
        return base + "/signatory/" + token;
    }

    private static boolean isCompanyActive(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return "ACTIVE".equals(normalized) || normalized.startsWith("ACTIVE");
    }

    private static String validateCin(String raw) {
        String clean = required(raw, "Enter a CIN or LLPIN").toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (clean.length() < 7 || clean.length() > 25 || !clean.matches("[A-Z0-9-]+")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CIN",
                    "Enter a valid MCA CIN (21 characters) or LLPIN");
        }
        return clean;
    }

    private static String validateMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                    "Enter a valid 10-digit mobile number for the authorized signatory");
        }
        return digits;
    }

    private static void requirePartner(AuthPrincipal me) {
        if (me == null || (!"RETAILER".equals(me.userType()) && !"MASTER_DISTRIBUTOR".equals(me.userType()))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a retailer or distributor can register a business");
        }
    }

    private static BigDecimal validateCoordinate(String raw, boolean latitude) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Allow location access so your work location can be recorded");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Allow location access so your work location can be recorded");
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

    private static BigDecimal toBigDecimal(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof BigDecimal bd) {
            return bd;
        }
        if (val instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(val));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", message);
        }
        return value.trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize business registration JSON", e);
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

    public static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return B64_URL.encodeToString(bytes);
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
