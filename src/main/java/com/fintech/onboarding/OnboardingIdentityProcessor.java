package com.fintech.onboarding;

import com.fintech.kyc.surepass.SurepassClient;
import com.fintech.kyc.surepass.SurepassProperties;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.web.ApiException;
import com.fintech.storage.SpacesStorage;
import com.fintech.verification.AadhaarValidator;
import com.fintech.verification.AddressFormatter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent. CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class OnboardingIdentityProcessor {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final long MAX_SELFIE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PAN_DOC_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_SELFIE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp"
    );
    private static final Set<String> ALLOWED_PAN_DOC_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "application/pdf"
    );

    private final JdbcTemplate jdbc;
    private final JourneyTracker journeyTracker;
    private final SurepassClient surepass;
    private final SurepassProperties surepassProps;
    private final DigilockerDocumentFetcher documentFetcher;
    private final ApplicationEventPublisher events;
    private final FieldCipher fieldCipher;
    private final SpacesStorage spaces;
    private final Executor digilockerExecutor;
    private final byte[] stateSecret;
    private final ConcurrentHashMap<UUID, Object> journeyLocks = new ConcurrentHashMap<>();

    @Autowired
    public OnboardingIdentityProcessor(
            JdbcTemplate jdbc,
            JourneyTracker journeyTracker,
            SurepassClient surepass,
            SurepassProperties surepassProps,
            DigilockerDocumentFetcher documentFetcher,
            ApplicationEventPublisher events,
            FieldCipher fieldCipher,
            SpacesStorage spaces,
            @Qualifier("digilockerExecutor") Executor digilockerExecutor,
            @Value("${app.onboarding.otp-secret}") String secret) {
        this.jdbc = jdbc;
        this.journeyTracker = journeyTracker;
        this.surepass = surepass;
        this.surepassProps = surepassProps;
        this.documentFetcher = documentFetcher;
        this.events = events;
        this.fieldCipher = fieldCipher;
        this.spaces = spaces;
        this.digilockerExecutor = digilockerExecutor != null ? digilockerExecutor : ForkJoinPool.commonPool();
        this.stateSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public OnboardingIdentityProcessor(
            JdbcTemplate jdbc,
            JourneyTracker journeyTracker,
            SurepassClient surepass,
            SurepassProperties surepassProps,
            DigilockerDocumentFetcher documentFetcher,
            ApplicationEventPublisher events,
            FieldCipher fieldCipher,
            SpacesStorage spaces,
            String secret) {
        this(jdbc, journeyTracker, surepass, surepassProps, documentFetcher, events,
                fieldCipher, spaces, ForkJoinPool.commonPool(), secret);
    }

    public Map<String, Object> captureLocation(UUID journeyId, UUID userId, Double latitude, Double longitude,
                                               Double accuracy, String ip, String userAgent) {
        assertNotNeedsReview(journeyId);
        Map<String, Object> identity = journeyTracker.getIdentity(journeyId);
        if (!Boolean.TRUE.equals(identity.get("phoneVerified"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "STEP_ORDER_VIOLATION",
                    "Verify your mobile number via OTP first");
        }
        BigDecimal lat = validateCoordinate(latitude, true);
        BigDecimal lng = validateCoordinate(longitude, false);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("locationCaptured", true);
        patch.put("latitude", lat);
        patch.put("longitude", lng);
        if (accuracy != null) {
            patch.put("accuracy", accuracy);
        }
        journeyTracker.mergeIdentity(journeyId, patch);
        journeyTracker.record(
                journeyId, userId, JourneyTracker.LOCATION_CAPTURED, "SUCCESS",
                Map.of("accuracy", accuracy == null ? 0 : accuracy),
                lat, lng, ip, userAgent, true);
        return Map.of("currentStep", JourneyTracker.LOCATION_CAPTURED, "latitude", lat, "longitude", lng);
    }

    public Map<String, Object> startDigilocker(UUID journeyId, UUID userId,
                                               String defaultNameHint, String ip, String userAgent) {
        assertNotNeedsReview(journeyId);
        Map<String, Object> identity = journeyTracker.getIdentity(journeyId);
        if (!Boolean.TRUE.equals(identity.get("locationCaptured"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "STEP_ORDER_VIOLATION",
                    "Capture your current location before starting Aadhaar verification");
        }
        String state = signState(journeyId);

        // If Aadhaar is already verified or completion is currently in-flight for this journey,
        // do not clobber digilockerClientId with a brand-new session on page reload.
        if (Boolean.TRUE.equals(identity.get("aadhaarVerified"))
                || isCompletingRecently(identity.get("digilockerCompletingAt"))) {
            return Map.of(
                    "token", "",
                    "gateway", surepassProps.effectiveGateway(),
                    "expiresAt", Instant.now().plusSeconds(600).toString(),
                    "state", state,
                    "alreadyCompleted", true);
        }

        // In live mode, if an existing DigiLocker session on this journey was already authorized by the user
        // (e.g. page reloaded after DigiLocker redirect), preserve it and signal the frontend to complete it.
        String existingClientId = String.valueOf(identity.getOrDefault("digilockerClientId", ""));
        if (!existingClientId.isBlank() && !"mock".equalsIgnoreCase(surepassProps.mode())) {
            try {
                SurepassClient.DigilockerStatus existingStatus = surepass.digilockerStatus(existingClientId);
                if (existingStatus.completed() && !existingStatus.failed()) {
                    return Map.of(
                            "token", "",
                            "gateway", surepassProps.effectiveGateway(),
                            "expiresAt", Instant.now().plusSeconds(600).toString(),
                            "state", state,
                            "alreadyCompleted", true);
                }
            } catch (Exception ignored) {
                // Fall through and initialize a fresh DigiLocker session
            }
        }

        SurepassClient.DigilockerSession session = surepass.digilockerInitialize(defaultNameHint);
        long expirySec = session.expirySeconds() > 0 ? session.expirySeconds() : 600L;
        Instant expiresAt = Instant.now().plusSeconds(expirySec);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("digilockerClientId", session.clientId());
        patch.put("digilockerState", state);
        patch.put("digilockerExpiresAt", expiresAt.toString());
        journeyTracker.mergeIdentity(journeyId, patch);

        BigDecimal lat = toBigDecimal(identity.get("latitude"));
        BigDecimal lng = toBigDecimal(identity.get("longitude"));
        journeyTracker.record(
                journeyId, userId, JourneyTracker.AADHAAR_VERIFIED, "INFO",
                Map.of("phase", "STARTED", "clientId", session.clientId()),
                lat, lng, ip, userAgent, false);

        return Map.of(
                "token", session.sdkToken(),
                "gateway", session.gateway(),
                "expiresAt", expiresAt.toString(),
                "state", state,
                "alreadyCompleted", false);
    }

    public Map<String, Object> completeDigilocker(UUID journeyId, UUID inviteId, UUID userId,
                                                  String stateParam,
                                                  String expectedExistingName,
                                                  String ip, String userAgent) {
        Object lock = journeyLocks.computeIfAbsent(journeyId, k -> new Object());
        synchronized (lock) {
            return completeDigilockerLocked(journeyId, inviteId, userId, stateParam, expectedExistingName, ip, userAgent);
        }
    }

    private Map<String, Object> completeDigilockerLocked(UUID journeyId, UUID inviteId, UUID userId,
                                                         String stateParam,
                                                         String expectedExistingName,
                                                         String ip, String userAgent) {
        assertNotNeedsReview(journeyId);
        Map<String, Object> identity = journeyTracker.getIdentity(journeyId);
        if (!Boolean.TRUE.equals(identity.get("locationCaptured"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "STEP_ORDER_VIOLATION",
                    "Capture your current location first");
        }
        String expectedState = signState(journeyId);
        if (stateParam == null || !MessageDigest.isEqual(
                expectedState.getBytes(StandardCharsets.UTF_8),
                stateParam.trim().getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIGILOCKER_STATE",
                    "DigiLocker session state could not be verified. Please start Aadhaar verification again.");
        }

        // Idempotent short-circuit: if Aadhaar was already verified on this journey, return immediately
        if (Boolean.TRUE.equals(identity.get("aadhaarVerified"))) {
            boolean panAvail = Boolean.TRUE.equals(identity.get("digilockerPanAvailable"));
            String dlPan = String.valueOf(identity.getOrDefault("digilockerPanNumber", ""));
            Map<String, Object> existingRes = new LinkedHashMap<>();
            existingRes.put("currentStep", JourneyTracker.AADHAAR_VERIFIED);
            existingRes.put("aadhaarName", String.valueOf(identity.getOrDefault("aadhaarName", "")));
            existingRes.put("dob", String.valueOf(identity.getOrDefault("dob", "")));
            existingRes.put("gender", String.valueOf(identity.getOrDefault("gender", "")));
            existingRes.put("address", String.valueOf(identity.getOrDefault("address", "")));
            existingRes.put("permanentAddress", identity.getOrDefault("permanentAddress", Map.of()));
            existingRes.put("maskedAadhaar", String.valueOf(identity.getOrDefault("maskedAadhaar", "")));
            existingRes.put("aadhaarLinked", Boolean.TRUE.equals(identity.get("aadhaarLinked")));
            existingRes.put("panInDigilocker", panAvail);
            existingRes.put("digilockerPanAvailable", panAvail);
            existingRes.put("digilockerPanNumber", dlPan);
            existingRes.put("hasFullAadhaar", Boolean.TRUE.equals(identity.get("hasFullAadhaar")));
            return existingRes;
        }

        String clientId = String.valueOf(identity.getOrDefault("digilockerClientId", ""));
        if (clientId.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DIGILOCKER_NOT_STARTED",
                    "Start DigiLocker verification first");
        }

        journeyTracker.mergeIdentity(journeyId, Map.of("digilockerCompletingAt", Instant.now().toEpochMilli()));
        try {
            BigDecimal lat = toBigDecimal(identity.get("latitude"));
            BigDecimal lng = toBigDecimal(identity.get("longitude"));

            // 1. Check DigiLocker session status
            SurepassClient.DigilockerStatus status = surepass.digilockerStatus(clientId);
            if (status.failed()) {
                journeyTracker.mergeIdentity(journeyId, Map.of("digilockerClientId", ""));
                journeyTracker.record(
                        journeyId, userId, JourneyTracker.AADHAAR_VERIFIED, "FAILURE",
                        Map.of("clientId", clientId, "status", String.valueOf(status.status()),
                                "error", String.valueOf(status.errorDescription())),
                        lat, lng, ip, userAgent, false);
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DIGILOCKER_FAILED",
                        status.errorDescription() != null && !status.errorDescription().isBlank()
                                ? status.errorDescription()
                                : "DigiLocker authorization failed. Please try again.");
            }
            if (!status.completed()) {
                journeyTracker.record(
                        journeyId, userId, JourneyTracker.AADHAAR_VERIFIED, "FAILURE",
                        Map.of("clientId", clientId, "status", String.valueOf(status.status())),
                        lat, lng, ip, userAgent, false);
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DIGILOCKER_INCOMPLETE",
                        "DigiLocker consent has not been completed yet. Please complete DigiLocker authorization.");
            }

            // 2. Fetch document list and Aadhaar XML demographics concurrently to halve upstream latency
            CompletableFuture<List<SurepassClient.DigilockerDocument>> docsFuture =
                    CompletableFuture.supplyAsync(() -> surepass.digilockerListDocuments(clientId), digilockerExecutor);
            CompletableFuture<SurepassClient.AadhaarRecord> aadhaarFuture =
                    CompletableFuture.supplyAsync(() -> surepass.digilockerAadhaar(clientId), digilockerExecutor);

            List<SurepassClient.DigilockerDocument> docs = unwrapFuture(docsFuture);
            boolean hasAadhaar = docs.stream()
                    .anyMatch(d -> "ADHAR".equalsIgnoreCase(d.docType()) && d.downloaded());
            boolean hasPan = docs.stream()
                    .anyMatch(d -> "PANCR".equalsIgnoreCase(d.docType()) && d.downloaded());
            if (!hasAadhaar) {
                if (!"mock".equalsIgnoreCase(surepassProps.mode())) {
                    journeyTracker.mergeIdentity(journeyId, Map.of("digilockerClientId", ""));
                }
                journeyTracker.record(
                        journeyId, userId, JourneyTracker.AADHAAR_VERIFIED, "FAILURE",
                        Map.of("clientId", clientId, "reason", "AADHAAR_DOC_MISSING", "docCount", docs.size()),
                        lat, lng, ip, userAgent, false);
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DIGILOCKER_AADHAAR_MISSING",
                        "Aadhaar Card was not shared from DigiLocker. Please retry and ensure Aadhaar is selected.");
            }

            // 3. Persist latest digilockerRef and upsert listed documents (purging any superseded client_ids)
            journeyTracker.mergeIdentity(journeyId, Map.of("digilockerRef", clientId));
            documentFetcher.upsertListedDocuments(
                    journeyId, userId, clientId, docs, surepassProps.effectiveMandatoryDocTypes());

            // 4. Join structured Aadhaar demographics + photo
            SurepassClient.AadhaarRecord aadhaar = unwrapFuture(aadhaarFuture);
            String masked = aadhaar.maskedAadhaar() == null ? "" : aadhaar.maskedAadhaar().replaceAll("\\s+", "");
            String last4 = masked.length() >= 4 ? masked.substring(masked.length() - 4) : "0000";
            String resolvedAadhaarEnc = resolveDigilockerOrStoredAadhaarEnc(masked, last4, userId);

            Map<String, Object> permanentAddress = AddressFormatter.toPermanentAddressMap(
                    aadhaar.structuredAddress(),
                    clientId,
                    Instant.now());

            // Store Aadhaar photograph encrypted in Spaces for manual face review
            documentFetcher.storeAadhaarPhoto(journeyId, userId, clientId, aadhaar.photoBytes());

            // If PANCR is present in DigiLocker, extract the 10-character PAN number from PANCR XML / metadata
            String extractedPan = hasPan
                    ? documentFetcher.fetchAndExtractPanNumber(journeyId, clientId)
                    : null;
            boolean digilockerPanAvailable = hasPan && extractedPan != null && !extractedPan.isBlank();
            String digilockerPanNumber = digilockerPanAvailable ? extractedPan : "";

            // In mock/test mode, fetch mandatory PDFs synchronously (<5ms).
            // In live mode, fetch all PDFs + XMLs in parallel in the background on digilockerExecutor
            // so the user immediately advances to Step 4 without waiting an extra 25s.
            if ("mock".equalsIgnoreCase(surepassProps.mode())) {
                documentFetcher.fetchMandatoryPdfsSynchronously(journeyId, clientId);
            }
            events.publishEvent(new DigilockerDocumentsRequested(journeyId, userId, clientId));

            String resolvedCity = String.valueOf(permanentAddress.getOrDefault("vtc", aadhaar.city()));
            if (resolvedCity.isBlank()) {
                resolvedCity = aadhaar.city();
            }
            String resolvedState = String.valueOf(permanentAddress.getOrDefault("state", aadhaar.state()));
            String resolvedPincode = String.valueOf(permanentAddress.getOrDefault("pincode", aadhaar.pincode()));
            String resolvedFullAddress = String.valueOf(permanentAddress.getOrDefault("fullAddress", aadhaar.address()));

            // For re-verification of an existing partner, bind Aadhaar name to existing account holder name
            if (expectedExistingName != null && !expectedExistingName.isBlank()) {
                int nameScore = NameMatcher.score(aadhaar.fullName(), expectedExistingName);
                if (nameScore < NameMatcher.DEFAULT_THRESHOLD) {
                    Map<String, Object> patch = new LinkedHashMap<>();
                    patch.put("aadhaarPendingReview", true);
                    patch.put("aadhaarName", aadhaar.fullName());
                    patch.put("expectedAccountName", expectedExistingName);
                    patch.put("aadhaarNameMatchScore", nameScore);
                    patch.put("dob", aadhaar.dob());
                    patch.put("gender", aadhaar.gender());
                    patch.put("permanentAddress", permanentAddress);
                    patch.put("address", resolvedFullAddress);
                    patch.put("city", resolvedCity);
                    patch.put("state", resolvedState);
                    patch.put("pincode", resolvedPincode);
                    patch.put("maskedAadhaar", masked);
                    patch.put("aadhaarLast4", last4);
                    patch.put("digilockerRef", clientId);
                    patch.put("aadhaarLinked", status.aadhaarLinked());
                    patch.put("digilockerPanAvailable", digilockerPanAvailable);
                    patch.put("digilockerPanNumber", digilockerPanNumber);
                    patch.put("digilockerCompletingAt", 0L);
                    journeyTracker.mergeIdentity(journeyId, patch);

                    jdbc.update("UPDATE onboarding_journeys SET status = 'NEEDS_REVIEW', last_activity_at = now() WHERE id = ?", journeyId);
                    journeyTracker.record(
                            journeyId, userId, JourneyTracker.AADHAAR_VERIFIED, "FAILURE",
                            Map.of("reason", "ACCOUNT_NAME_MISMATCH", "aadhaarName", aadhaar.fullName(),
                                    "accountName", expectedExistingName, "score", nameScore),
                            lat, lng, ip, userAgent, false);
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NEEDS_REVIEW",
                            "Aadhaar name (" + aadhaar.fullName() + ") does not match registered account name ("
                                    + expectedExistingName + "). Flagged for admin review.");
                }
            }

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("aadhaarVerified", true);
            patch.put("aadhaarPendingReview", false);
            patch.put("aadhaarName", aadhaar.fullName());
            patch.put("dob", aadhaar.dob());
            patch.put("gender", aadhaar.gender());
            patch.put("permanentAddress", permanentAddress);
            patch.put("address", resolvedFullAddress);
            patch.put("city", resolvedCity);
            patch.put("state", resolvedState);
            patch.put("pincode", resolvedPincode);
            patch.put("maskedAadhaar", masked);
            patch.put("aadhaarLast4", last4);
            patch.put("digilockerRef", clientId);
            patch.put("aadhaarLinked", status.aadhaarLinked());
            patch.put("digilockerPanAvailable", digilockerPanAvailable);
            patch.put("digilockerPanNumber", digilockerPanNumber);
            if (resolvedAadhaarEnc != null && !resolvedAadhaarEnc.isBlank()) {
                patch.put("aadhaarEncrypted", resolvedAadhaarEnc);
                patch.put("hasFullAadhaar", true);
            } else {
                patch.put("hasFullAadhaar", false);
            }
            patch.put("digilockerCompletingAt", 0L);
            journeyTracker.mergeIdentity(journeyId, patch);

            journeyTracker.record(
                    journeyId, userId, JourneyTracker.AADHAAR_VERIFIED, "SUCCESS",
                    Map.of("aadhaarName", aadhaar.fullName(), "maskedAadhaar", masked,
                            "digilockerRef", clientId, "documentsCount", docs.size(),
                            "panInDigilocker", digilockerPanAvailable),
                    lat, lng, ip, userAgent, true);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("currentStep", JourneyTracker.AADHAAR_VERIFIED);
            out.put("aadhaarName", aadhaar.fullName());
            out.put("dob", aadhaar.dob());
            out.put("gender", aadhaar.gender());
            out.put("address", resolvedFullAddress);
            out.put("permanentAddress", permanentAddress);
            out.put("maskedAadhaar", masked);
            out.put("aadhaarLinked", status.aadhaarLinked());
            out.put("panInDigilocker", digilockerPanAvailable);
            out.put("digilockerPanAvailable", digilockerPanAvailable);
            out.put("digilockerPanNumber", digilockerPanNumber);
            out.put("hasFullAadhaar", Boolean.TRUE.equals(patch.get("hasFullAadhaar")));
            return out;
        } finally {
            try {
                journeyTracker.mergeIdentity(journeyId, Map.of("digilockerCompletingAt", 0L));
            } catch (Exception ignored) {
            }
        }
    }

    private static <T> T unwrapFuture(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Async DigiLocker operation failed", e.getCause());
        }
    }

    private static boolean isCompletingRecently(Object val) {
        if (val instanceof Number n) {
            long ts = n.longValue();
            return ts > 0 && (Instant.now().toEpochMilli() - ts) < 60_000L;
        }
        return false;
    }

    public Map<String, Object> verifyPan(UUID journeyId, UUID inviteId, UUID userId,
                                         String panRaw, String ip, String userAgent) {
        return verifyPan(journeyId, inviteId, userId, panRaw, null, null, false, ip, userAgent);
    }

    public Map<String, Object> verifyPan(UUID journeyId, UUID inviteId, UUID userId,
                                         String panRaw, MultipartFile panFile,
                                         String ip, String userAgent) {
        return verifyPan(journeyId, inviteId, userId, panRaw, null, panFile, false, ip, userAgent);
    }

    public Map<String, Object> verifyPan(UUID journeyId, UUID inviteId, UUID userId,
                                         String panRaw, String aadhaarRaw, MultipartFile panFile,
                                         boolean requireFullAadhaar,
                                         String ip, String userAgent) {
        assertNotNeedsReview(journeyId);
        Map<String, Object> identity = journeyTracker.getIdentity(journeyId);
        if (!Boolean.TRUE.equals(identity.get("aadhaarVerified"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "STEP_ORDER_VIOLATION",
                    "Complete Aadhaar DigiLocker verification before verifying PAN");
        }

        String aadhaarLast4 = extractLast4Digits(String.valueOf(identity.getOrDefault("aadhaarLast4", "")));
        String existingAadhaarEnc = identity.get("aadhaarEncrypted") instanceof String s ? s : null;
        boolean alreadyHasFullAadhaar = Boolean.TRUE.equals(identity.get("hasFullAadhaar"))
                && existingAadhaarEnc != null
                && !existingAadhaarEnc.isBlank();
        String aadhaarEncToSave = existingAadhaarEnc;

        if (aadhaarRaw != null && !aadhaarRaw.isBlank()) {
            String normalizedAadhaar = AadhaarValidator.validateAndNormalize(
                    aadhaarRaw,
                    "0000".equals(aadhaarLast4) ? null : aadhaarLast4);
            aadhaarEncToSave = fieldCipher.encrypt(normalizedAadhaar);
            alreadyHasFullAadhaar = true;
        } else if (requireFullAadhaar && !alreadyHasFullAadhaar) {
            String suffixHint = (!aadhaarLast4.isBlank() && !"0000".equals(aadhaarLast4))
                    ? " ending in " + aadhaarLast4
                    : "";
            throw ApiException.of(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "AADHAAR_REQUIRED",
                    "Enter your complete 12-digit Aadhaar number" + suffixHint + " to continue");
        }

        boolean digilockerPanAvailable = Boolean.TRUE.equals(identity.get("digilockerPanAvailable"));
        String digilockerPanNumber = String.valueOf(identity.getOrDefault("digilockerPanNumber", ""))
                .trim().toUpperCase(Locale.ROOT);

        String candidatePan = (panRaw != null && !panRaw.isBlank())
                ? panRaw
                : (digilockerPanAvailable ? digilockerPanNumber : "");
        String pan = validatePan(candidatePan);

        byte[] manualPanBytes = null;
        String manualPanMime = null;
        String manualPanExt = null;
        if (!digilockerPanAvailable) {
            if (panFile == null || panFile.isEmpty()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAN_IMAGE_REQUIRED",
                        "PAN card was not found in your DigiLocker. Please upload your PAN card image/PDF or capture it with your camera.");
            }
            String contentType = panFile.getContentType() == null
                    ? ""
                    : panFile.getContentType().toLowerCase(Locale.ROOT).trim();
            if (!ALLOWED_PAN_DOC_TYPES.contains(contentType)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAN_FILE_FORMAT",
                        "PAN card file must be a JPEG, PNG, WebP image or PDF");
            }
            if (panFile.getSize() > MAX_PAN_DOC_BYTES) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAN_FILE_TOO_LARGE",
                        "PAN card file must not exceed 10 MB");
            }
            try {
                manualPanBytes = panFile.getBytes();
                if (manualPanBytes.length == 0) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAN_IMAGE_REQUIRED",
                            "Uploaded PAN card file is empty");
                }
            } catch (IOException e) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAN_FILE_READ_ERROR",
                        "Could not read uploaded PAN card file");
            }
            manualPanMime = "image/jpg".equals(contentType) ? "image/jpeg" : contentType;
            manualPanExt = manualPanMime.contains("pdf") ? "pdf"
                    : (manualPanMime.contains("png") ? "png"
                    : (manualPanMime.contains("webp") ? "webp" : "jpg"));
        }

        String aadhaarName = String.valueOf(identity.getOrDefault("aadhaarName", ""));
        BigDecimal lat = toBigDecimal(identity.get("latitude"));
        BigDecimal lng = toBigDecimal(identity.get("longitude"));

        SurepassClient.PanRecord rec = surepass.panComprehensive(pan, aadhaarName);
        int matchScore = NameMatcher.score(aadhaarName, rec.fullName());
        String panLast4 = pan.substring(6);
        String panEnc = fieldCipher.encrypt(pan);

        String panMaskedAadhaar = rec.maskedAadhaar() == null ? "" : rec.maskedAadhaar().trim();
        String panAadhaarLast4 = extractLast4Digits(panMaskedAadhaar);
        boolean last4DigitsMatched = !aadhaarLast4.isBlank()
                && !panAadhaarLast4.isBlank()
                && aadhaarLast4.equals(panAadhaarLast4);
        boolean aadhaarPanLinked = rec.aadhaarSeeded()
                && (!panAadhaarLast4.isBlank() ? last4DigitsMatched : !aadhaarLast4.isBlank());
        String panSource = digilockerPanAvailable ? "DIGILOCKER" : "MANUAL_UPLOAD";

        if (!rec.valid() || matchScore < NameMatcher.DEFAULT_THRESHOLD) {
            int failures = journeyTracker.recordFailureAndCheckReview(journeyId, inviteId, JourneyTracker.PAN_VERIFIED);
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("panLast4", panLast4);
            patch.put("panEncrypted", panEnc);
            patch.put("panName", rec.fullName());
            patch.put("panNameMatchScore", matchScore);
            patch.put("panProviderRef", rec.providerRef());
            patch.put("panMaskedAadhaar", panMaskedAadhaar);
            patch.put("panAadhaarLast4", panAadhaarLast4);
            patch.put("panAadhaarLast4Match", last4DigitsMatched);
            patch.put("aadhaarLinked", rec.aadhaarSeeded());
            patch.put("aadhaarPanLinked", aadhaarPanLinked);
            if (aadhaarEncToSave != null && !aadhaarEncToSave.isBlank()) {
                patch.put("aadhaarEncrypted", aadhaarEncToSave);
                patch.put("hasFullAadhaar", true);
            }
            patch.put("panPendingReview", failures >= 3);
            journeyTracker.mergeIdentity(journeyId, patch);

            journeyTracker.record(
                    journeyId, userId, JourneyTracker.PAN_VERIFIED, "FAILURE",
                    Map.of("panLast4", panLast4, "panName", rec.fullName(), "aadhaarName", aadhaarName,
                            "score", matchScore, "seeded", rec.aadhaarSeeded(),
                            "aadhaarPanLinked", aadhaarPanLinked, "failures", failures),
                    lat, lng, ip, userAgent, false);

            if (failures >= 3) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NEEDS_REVIEW",
                        "PAN verification failed 3 times (name match score " + matchScore
                                + "). Your application is now under admin review.");
            }
            if (!rec.valid()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAN",
                        "PAN number could not be validated by Income Tax records");
            }
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAN_NAME_MISMATCH",
                    "PAN name (" + rec.fullName() + ") did not match Aadhaar name (" + aadhaarName
                            + "). " + (3 - failures) + " attempt(s) remaining.");
        }

        if (manualPanBytes != null) {
            String clientId = String.valueOf(identity.getOrDefault(
                    "digilockerRef", identity.getOrDefault("digilockerClientId", "")));
            documentFetcher.storeManualPanDocument(
                    journeyId, userId, clientId, manualPanBytes, manualPanMime, manualPanExt);
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("panVerified", true);
        patch.put("panPendingReview", false);
        patch.put("panLast4", panLast4);
        patch.put("panEncrypted", panEnc);
        patch.put("panName", rec.fullName());
        patch.put("panNameMatchScore", matchScore);
        patch.put("panProviderRef", rec.providerRef());
        patch.put("panSource", panSource);
        patch.put("panMaskedAadhaar", panMaskedAadhaar);
        patch.put("panAadhaarLast4", panAadhaarLast4);
        patch.put("panAadhaarLast4Match", last4DigitsMatched);
        patch.put("aadhaarLinked", rec.aadhaarSeeded());
        patch.put("aadhaarPanLinked", aadhaarPanLinked);
        if (aadhaarEncToSave != null && !aadhaarEncToSave.isBlank()) {
            patch.put("aadhaarEncrypted", aadhaarEncToSave);
            patch.put("hasFullAadhaar", true);
        }
        journeyTracker.mergeIdentity(journeyId, patch);

        journeyTracker.record(
                journeyId, userId, JourneyTracker.PAN_VERIFIED, "SUCCESS",
                Map.of("panLast4", panLast4, "panName", rec.fullName(), "score", matchScore,
                        "providerRef", rec.providerRef(), "panSource", panSource,
                        "aadhaarPanLinked", aadhaarPanLinked, "last4Matched", last4DigitsMatched),
                lat, lng, ip, userAgent, true);

        return Map.of(
                "currentStep", JourneyTracker.PAN_VERIFIED,
                "panLast4", panLast4,
                "panName", rec.fullName(),
                "nameMatchScore", matchScore,
                "panSource", panSource,
                "aadhaarPanLinked", aadhaarPanLinked,
                "panAadhaarLast4Match", last4DigitsMatched,
                "hasFullAadhaar", alreadyHasFullAadhaar);
    }

    private String resolveDigilockerOrStoredAadhaarEnc(String maskedFromDigilocker, String last4, UUID userId) {
        if (maskedFromDigilocker != null && maskedFromDigilocker.matches("[2-9]\\d{11}")) {
            try {
                String valid = AadhaarValidator.validateAndNormalize(
                        maskedFromDigilocker,
                        "0000".equals(last4) ? null : last4);
                return fieldCipher.encrypt(valid);
            } catch (Exception ignored) {
                // Masked or non-Verhoeff placeholder — fall through
            }
        }
        if (userId != null) {
            try {
                List<String> stored = jdbc.queryForList(
                        "SELECT aadhaar_enc FROM agent_kyc_profiles WHERE user_id = ? AND aadhaar_enc IS NOT NULL AND aadhaar_enc <> ''",
                        String.class, userId);
                if (!stored.isEmpty() && stored.get(0) != null && !stored.get(0).isBlank()) {
                    String decrypted = fieldCipher.decrypt(stored.get(0));
                    if (decrypted != null && decrypted.matches("[2-9]\\d{11}")
                            && (last4 == null || last4.isBlank() || "0000".equals(last4) || decrypted.endsWith(last4))) {
                        return stored.get(0);
                    }
                }
            } catch (Exception ignored) {
                // Ciphertext unreadable or not present — require fresh 12-digit entry
            }
        }
        return null;
    }

    private static String extractLast4Digits(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String digits = raw.replaceAll("\\D+", "");
        if (digits.length() < 4) {
            return "";
        }
        return digits.substring(digits.length() - 4);
    }

    public Map<String, Object> verifySelfie(UUID journeyId, UUID inviteId, UUID userId,
                                            MultipartFile file, String ip, String userAgent) {
        assertNotNeedsReview(journeyId);
        Map<String, Object> identity = journeyTracker.getIdentity(journeyId);
        if (!Boolean.TRUE.equals(identity.get("panVerified"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "STEP_ORDER_VIOLATION",
                    "Complete PAN verification before capturing your live selfie");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SELFIE_REQUIRED",
                    "Capture a live selfie using your camera");
        }
        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().toLowerCase(Locale.ROOT).trim();
        if (!ALLOWED_SELFIE_TYPES.contains(contentType)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SELFIE_FORMAT",
                    "Selfie must be a JPEG, PNG, or WebP image");
        }
        if (file.getSize() > MAX_SELFIE_BYTES) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SELFIE_TOO_LARGE",
                    "Selfie image must not exceed 5 MB");
        }
        try {
            byte[] selfieBytes = file.getBytes();
            if (selfieBytes.length == 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SELFIE_REQUIRED",
                        "Capture a live selfie using your camera");
            }
        } catch (IOException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SELFIE_READ_ERROR",
                    "Could not read selfie image");
        }

        BigDecimal lat = toBigDecimal(identity.get("latitude"));
        BigDecimal lng = toBigDecimal(identity.get("longitude"));

        SpacesStorage.StoredObject stored = spaces.upload("kyc-selfie", file);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("selfieVerified", true);
        patch.put("selfieCaptured", true);
        patch.put("faceReviewStatus", "PENDING");
        patch.put("selfieKey", stored.key());
        journeyTracker.mergeIdentity(journeyId, patch);

        journeyTracker.record(
                journeyId, userId, JourneyTracker.SELFIE_VERIFIED, "SUCCESS",
                Map.of("selfieKey", stored.key(), "faceReviewStatus", "PENDING"),
                lat, lng, ip, userAgent, true);

        return Map.of(
                "currentStep", JourneyTracker.SELFIE_VERIFIED,
                "selfieCaptured", true,
                "faceReviewStatus", "PENDING");
    }

    public void bindLatestDigilockerDocumentsToUser(UUID journeyId, UUID userId, String digilockerRef) {
        documentFetcher.bindLatestClientToUser(journeyId, userId, digilockerRef);
    }

    public String signState(UUID journeyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stateSecret, "HmacSHA256"));
            byte[] raw = mac.doFinal(("DIGILOCKER:" + journeyId).getBytes(StandardCharsets.UTF_8));
            return B64.encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private void assertNotNeedsReview(UUID journeyId) {
        String status = jdbc.queryForObject(
                "SELECT status FROM onboarding_journeys WHERE id = ?", String.class, journeyId);
        if ("NEEDS_REVIEW".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NEEDS_REVIEW",
                    "Your verification is queued for admin review. You can resume once an admin approves it.");
        }
        if ("COMPLETED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "JOURNEY_COMPLETED",
                    "This onboarding journey is already completed.");
        }
        if ("EXPIRED".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "JOURNEY_EXPIRED",
                    "This onboarding link has expired or been revoked.");
        }
    }

    private static String validatePan(String raw) {
        String pan = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (!pan.matches("[A-Z]{5}[0-9]{4}[A-Z]")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAN",
                    "Enter a valid 10-character PAN (e.g. ABCDE1234F)");
        }
        return pan;
    }

    private static BigDecimal validateCoordinate(Double raw, boolean isLatitude) {
        if (raw == null || Double.isNaN(raw) || Double.isInfinite(raw)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Allow location permission to continue verification");
        }
        if (isLatitude && (raw < -90.0 || raw > 90.0)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED", "Invalid latitude");
        }
        if (!isLatitude && (raw < -180.0 || raw > 180.0)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED", "Invalid longitude");
        }
        return BigDecimal.valueOf(raw);
    }

    private static BigDecimal toBigDecimal(Object val) {
        if (val instanceof BigDecimal bd) {
            return bd;
        }
        if (val instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (val != null) {
            try {
                return new BigDecimal(String.valueOf(val));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
