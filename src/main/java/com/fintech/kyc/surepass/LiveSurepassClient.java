package com.fintech.kyc.surepass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "app.surepass.mode", havingValue = "live", matchIfMissing = true)
@EnableConfigurationProperties(SurepassProperties.class)
public class LiveSurepassClient implements SurepassClient {

    private static final Logger log = LoggerFactory.getLogger(LiveSurepassClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SAME_ORIGIN_REDIRECTS = 3;

    private final RestClient rest;
    private final SurepassProperties props;
    private final String baseUrl;
    private final Set<String> loggedShapePaths = ConcurrentHashMap.newKeySet();

    @Autowired
    public LiveSurepassClient(SurepassProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        rf.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        String resolvedBase = normalizeBaseUrl(props != null ? props.baseUrl() : null);
        String token = props != null && props.apiToken() != null ? props.apiToken().trim() : "";
        this.baseUrl = resolvedBase;
        this.rest = RestClient.builder()
                .baseUrl(resolvedBase)
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.props = props;
    }

    LiveSurepassClient(RestClient rest, SurepassProperties props) {
        this.rest = rest;
        this.props = props;
        this.baseUrl = normalizeBaseUrl(props != null ? props.baseUrl() : null);
    }

    private static String normalizeBaseUrl(String raw) {
        String candidate = StringUtils.hasText(raw) ? raw.trim() : "https://kyc-api.surepass.app";
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    @Override
    public DigilockerSession digilockerInitialize(String defaultNameHint) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("signup_flow", true);
        inner.put("skip_main_screen", props.effectiveSkipMainScreen());
        if (StringUtils.hasText(props.digilockerLogoUrl())) {
            inner.put("logo_url", props.digilockerLogoUrl().trim());
        }
        Map<String, Object> data = post("/api/v1/digilocker/initialize", Map.of("data", inner));
        String clientId = str(data.get("client_id"));
        String token = str(data.get("token"));
        long expirySeconds = (long) num(data.getOrDefault("expiry_seconds", 600));
        if (expirySeconds <= 0) {
            expirySeconds = 600;
        }
        log.info("SUREPASS digilockerInitialize clientId={} expirySeconds={}", clientId, expirySeconds);
        return new DigilockerSession(clientId, token, expirySeconds, props.effectiveGateway());
    }

    @Override
    public DigilockerStatus digilockerStatus(String clientId) {
        Map<String, Object> data = get("/api/v1/digilocker/status/" + enc(clientId));
        String status = str(data.get("status"));
        boolean completed = Boolean.TRUE.equals(data.get("completed"))
                || "COMPLETED".equalsIgnoreCase(status)
                || "SUCCESS".equalsIgnoreCase(status);
        boolean failed = Boolean.TRUE.equals(data.get("failed"))
                || "FAILED".equalsIgnoreCase(status);
        String errorDescription = data.get("error_description") == null ? null : str(data.get("error_description"));
        boolean aadhaarLinked = Boolean.TRUE.equals(data.get("aadhaar_linked"));
        log.info("SUREPASS digilockerStatus clientId={} status={} completed={} failed={} aadhaarLinked={}",
                clientId, status, completed, failed, aadhaarLinked);
        return new DigilockerStatus(clientId, completed, failed, status, errorDescription, aadhaarLinked);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DigilockerDocument> digilockerListDocuments(String clientId) {
        Map<String, Object> data = get("/api/v1/digilocker/list-documents/" + enc(clientId));
        Object rawDocs = data.get("documents");
        List<DigilockerDocument> out = new ArrayList<>();
        if (rawDocs instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> doc = (Map<String, Object>) m;
                    out.add(new DigilockerDocument(
                            str(doc.get("file_id")),
                            str(doc.get("name")),
                            str(doc.get("doc_type")).toUpperCase(Locale.ROOT),
                            Boolean.TRUE.equals(doc.get("downloaded")),
                            str(doc.get("issuer")),
                            str(doc.get("description")),
                            str(doc.get("file_type")).toLowerCase(Locale.ROOT)
                    ));
                }
            }
        }
        log.info("SUREPASS digilockerListDocuments clientId={} count={}", clientId, out.size());
        return out;
    }

    @Override
    public DigilockerDownloadLink digilockerDownloadDocument(String clientId, String fileId) {
        Map<String, Object> data = get("/api/v1/digilocker/download-document/" + enc(clientId) + "/" + enc(fileId));
        String downloadUrl = str(data.get("download_url"));
        String mimeType = str(data.getOrDefault("mime_type", "application/octet-stream"));
        log.info("SUREPASS digilockerDownloadDocument clientId={} fileId={} mimeType={}", clientId, fileId, mimeType);
        return new DigilockerDownloadLink(downloadUrl, mimeType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public AadhaarRecord digilockerAadhaar(String clientId) {
        Map<String, Object> data = get("/api/v1/digilocker/download-aadhaar/" + enc(clientId));
        Map<String, Object> aadhaar = data.get("aadhaar_xml_data") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : data;
        Map<String, Object> meta = data.get("digilocker_metadata") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        String fullName = firstNonBlank(str(aadhaar.get("full_name")), str(meta.get("name")));
        String dob = firstNonBlank(str(aadhaar.get("dob")), str(meta.get("dob")));
        String gender = firstNonBlank(str(aadhaar.get("gender")), str(meta.get("gender")));
        String maskedAadhaar = str(aadhaar.getOrDefault("masked_aadhaar", aadhaar.get("aadhaar_number")));

        Map<String, Object> addrMap = aadhaar.get("address") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String careOf = firstNonBlank(str(aadhaar.get("care_of")), str(aadhaar.get("father_name")));
        String house = str(addrMap.get("house"));
        String street = str(addrMap.get("street"));
        String landmark = str(addrMap.get("landmark"));
        String locality = str(addrMap.get("loc"));
        String vtc = str(addrMap.get("vtc"));
        String postOffice = str(addrMap.get("po"));
        String subDistrict = str(addrMap.get("subdist"));
        String district = str(addrMap.get("dist"));
        String state = str(addrMap.get("state"));
        String country = firstNonBlank(str(addrMap.get("country")), "India");
        String rawZip = firstNonBlank(str(aadhaar.get("zip")), str(addrMap.get("zip")));
        String pincode = rawZip.matches("^\\d{6}$") ? rawZip : "";

        String city = firstNonBlank(vtc, district, subDistrict, locality);
        String fullAddress = firstNonBlank(
                str(aadhaar.get("full_address")),
                str(addrMap.get("full")),
                joinAddressParts(house, street, landmark, locality, vtc, postOffice, district, state, pincode, country));

        AadhaarAddress structured = new AadhaarAddress(
                careOf, house, street, landmark, locality, vtc, postOffice,
                subDistrict, district, state, pincode, country, fullAddress);

        String photoB64 = str(aadhaar.getOrDefault("profile_image", ""));
        byte[] photo = decodeBase64Safe(photoB64);
        log.info("SUREPASS digilockerAadhaar clientId={} hasPhoto={} hasAddress={}",
                clientId, photo.length > 0, !fullAddress.isBlank());
        return new AadhaarRecord(
                clientId, fullName, dob, gender, fullAddress, city, state, pincode,
                maskedAadhaar, photo, structured);
    }

    @Override
    public PanRecord panComprehensive(String pan, String defaultNameHint) {
        Map<String, Object> data = post("/api/v1/pan/pan-comprehensive", Map.of(
                "id_number", pan,
                "masked_aadhaar_variant", "v1"
        ));
        logShapeOnce("/api/v1/pan/pan-comprehensive", data);
        String fullName = str(data.get("full_name"));
        String status = str(data.getOrDefault("status", "VALID"));
        boolean seeded = !Boolean.FALSE.equals(data.get("aadhaar_linked"));
        String maskedAadhaar = str(data.get("masked_aadhaar"));
        String dob = str(data.get("dob"));
        String gender = str(data.get("gender"));
        String clientId = str(data.get("client_id"));
        boolean valid = !"INVALID".equalsIgnoreCase(status) && !fullName.isBlank();
        return new PanRecord(pan, fullName, status, valid, seeded, maskedAadhaar, dob, gender, clientId);
    }

    @Override
    public BankRecord bankVerification(String accountNumber, String ifsc, String defaultNameHint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id_number", accountNumber);
        payload.put("ifsc", ifsc);
        payload.put("ifsc_details", true);

        Map<String, Object> data = postAllowingStructuredFailure("/api/v1/bank-verification/", payload);
        logShapeOnce("/api/v1/bank-verification/", data);
        BankRecord record = parseBankRecord(ifsc, data);
        String maskedAcc = accountNumber != null && accountNumber.length() > 4
                ? "****" + accountNumber.substring(accountNumber.length() - 4)
                : "****";
        log.info("SUREPASS bankVerification clientId={} acc={} ifsc={} exists={} status={} remarks={} bank={} branch={} impsRef={}",
                record.providerRef(),
                maskedAcc,
                ifsc,
                record.accountExists(),
                record.status(),
                record.remarks(),
                record.bankName(),
                record.ifscDetails() != null ? record.ifscDetails().branch() : "",
                record.impsRefNo());
        return record;
    }

    @SuppressWarnings("unchecked")
    public static BankRecord parseBankRecord(String requestedIfsc, Map<String, Object> data) {
        if (data == null) {
            return new BankRecord(false, "", "", "", "", "", "failed", null, null);
        }
        String clientId = str(data.get("client_id"));
        String status = firstNonBlank(str(data.get("status")), "success");
        String lowerStatus = status.toLowerCase(Locale.ROOT);
        boolean exists = Boolean.TRUE.equals(data.get("account_exists"))
                && !lowerStatus.equals("failed")
                && !lowerStatus.startsWith("invalid");
        String fullName = str(data.get("full_name"));
        String impsRefNo = str(data.get("imps_ref_no"));
        String remarks = str(data.get("remarks"));
        String upiRaw = str(data.get("upi_id"));
        String upiId = upiRaw.isBlank() ? null : upiRaw;

        IfscDetails ifscDetails = null;
        if (data.get("ifsc_details") instanceof Map<?, ?> rawIfsc) {
            Map<String, Object> m = (Map<String, Object>) rawIfsc;
            // Intentionally do NOT map `contact` from ifsc_details per product policy.
            String swiftRaw = str(m.get("swift"));
            ifscDetails = new IfscDetails(
                    firstNonBlank(str(m.get("ifsc")), requestedIfsc),
                    str(m.get("micr")),
                    str(m.get("iso3166")),
                    swiftRaw.isBlank() ? null : swiftRaw,
                    str(m.get("bank")),
                    str(m.get("bank_code")),
                    firstNonBlank(str(m.get("bank_name")), str(m.get("bank"))),
                    str(m.get("branch")),
                    str(m.get("centre")),
                    str(m.get("district")),
                    str(m.get("state")),
                    str(m.get("city")),
                    str(m.get("address")),
                    !Boolean.FALSE.equals(m.get("imps")),
                    !Boolean.FALSE.equals(m.get("rtgs")),
                    !Boolean.FALSE.equals(m.get("neft")),
                    !Boolean.FALSE.equals(m.get("upi")),
                    Boolean.TRUE.equals(m.get("micr_check"))
            );
        }

        String bankName = firstNonBlank(
                ifscDetails != null ? ifscDetails.bankName() : "",
                ifscDetails != null ? ifscDetails.bank() : "",
                str(data.get("bank_name")),
                requestedIfsc != null && requestedIfsc.length() >= 4
                        ? requestedIfsc.substring(0, 4).toUpperCase(Locale.ROOT) + " Bank"
                        : "");

        return new BankRecord(
                exists,
                fullName,
                bankName,
                clientId,
                impsRefNo,
                remarks,
                status,
                upiId,
                ifscDetails
        );
    }

    @Override
    public GstinRecord gstin(String gstin) {
        Map<String, Object> data = post("/api/v1/corporate/gstin", Map.of("id_number", gstin));
        logShapeOnce("/api/v1/corporate/gstin", data);
        String legalName = str(data.get("legal_name"));
        String tradeName = str(data.getOrDefault("trade_name", legalName));
        String address = str(data.get("address"));
        String status = str(data.getOrDefault("gstin_status", "ACTIVE"));
        return new GstinRecord(gstin, legalName, tradeName, address, status, !legalName.isBlank(), str(data.get("client_id")));
    }

    @Override
    public UdyamRecord udyam(String udyam) {
        Map<String, Object> data = post("/api/v1/corporate/udyam", Map.of("id_number", udyam));
        logShapeOnce("/api/v1/corporate/udyam", data);
        String name = str(data.getOrDefault("enterprise_name", data.get("name_of_enterprise")));
        String type = str(data.getOrDefault("enterprise_type", "MSME"));
        String address = str(data.getOrDefault("address", ""));
        return new UdyamRecord(udyam, name, type, address, !name.isBlank(), str(data.get("client_id")));
    }

    @Override
    public CompanyRecord company(String cin) {
        Map<String, Object> data = post("/api/v1/corporate/company-details", Map.of("id_number", cin));
        logShapeOnce("/api/v1/corporate/company-details", data);
        return parseCompanyRecord(cin, data);
    }

    @SuppressWarnings("unchecked")
    public static CompanyRecord parseCompanyRecord(String cin, Map<String, Object> data) {
        if (data == null) {
            return new CompanyRecord(cin, "", "", "", false, "");
        }
        String name = str(data.getOrDefault("company_name", ""));
        String companyType = str(data.getOrDefault("company_type", "company"));
        String clientId = str(data.get("client_id"));
        String canonicalCin = str(data.getOrDefault("company_id", cin));
        if (canonicalCin.isBlank()) {
            canonicalCin = cin;
        }

        Map<String, Object> details = data.get("details") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        Map<String, Object> info = details.get("company_info") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        String address = str(info.getOrDefault("registered_address", data.getOrDefault("registered_address", "")));
        String status = str(info.getOrDefault("company_status", data.getOrDefault("company_status", "Active")));
        String classOfCompany = str(info.getOrDefault("class_of_company", ""));
        String dateOfIncorporation = str(info.getOrDefault("date_of_incorporation", ""));
        String rocCode = str(info.getOrDefault("roc_code", ""));
        String pincode = extractTrailingPincode(address);

        List<Director> directors = new ArrayList<>();
        Object rawDirectors = details.get("directors");
        if (rawDirectors instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> dm) {
                    String dirName = str(dm.get("director_name"));
                    String din = str(dm.get("din_number"));
                    if (din.isBlank()) {
                        din = str(dm.get("din_or_pan"));
                    }
                    if (din.isBlank()) {
                        din = str(dm.get("din"));
                    }
                    String startDate = str(dm.get("start_date"));
                    String endDateRaw = str(dm.get("end_date"));
                    String surrenderedDin = str(dm.get("surrendered_din"));
                    boolean active = (endDateRaw.isBlank() || "1800-01-01".equals(endDateRaw) || "-".equals(endDateRaw))
                            && surrenderedDin.isBlank();
                    String normalizedEndDate = "1800-01-01".equals(endDateRaw) ? "" : endDateRaw;
                    if (!dirName.isBlank()) {
                        directors.add(new Director(dirName, din, startDate, normalizedEndDate, active));
                    }
                }
            }
        }

        return new CompanyRecord(
                canonicalCin,
                name,
                address,
                status,
                !name.isBlank(),
                clientId,
                companyType,
                classOfCompany,
                dateOfIncorporation,
                rocCode,
                pincode,
                List.copyOf(directors)
        );
    }

    private static String extractTrailingPincode(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([1-9][0-9]{5})\\b").matcher(address);
        String found = "";
        while (m.find()) {
            found = m.group(1);
        }
        return found;
    }

    private String requireBearerHeader() {
        String token = props != null && props.apiToken() != null ? props.apiToken().trim() : "";
        if (!StringUtils.hasText(token)) {
            log.error("SUREPASS_AUTH_ERROR APP_SUREPASS_TOKEN is not configured");
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "KYC_PROVIDER_AUTH",
                    "Verification service is temporarily unavailable.");
        }
        return "Bearer " + token;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        String authHeader = requireBearerHeader();
        try {
            Map<String, Object> res = executePostJson(path, body, authHeader);
            return unwrapOrThrow(path, 200, res);
        } catch (ApiException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw translateHttpError(path, e);
        } catch (Exception e) {
            log.warn("SUREPASS POST failed path={} err={}", path, e.getMessage());
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "KYC_PROVIDER_UNAVAILABLE",
                    "Verification provider could not be reached. Please try again.");
        }
    }

    /**
     * Executes a POST request where Surepass may return HTTP 422 (or success=false) with a structured
     * {@code data} object (e.g., {@code account_exists=false}, {@code remarks="Invalid Account Number"},
     * {@code ifsc_details={...}}) that the caller wants to inspect and persist rather than discard.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postAllowingStructuredFailure(String path, Map<String, Object> body) {
        String authHeader = requireBearerHeader();
        try {
            Map<String, Object> res = executePostJson(path, body, authHeader);
            if (res != null && Boolean.FALSE.equals(res.get("success")) && res.get("data") instanceof Map<?, ?> m && !m.isEmpty()) {
                return (Map<String, Object>) m;
            }
            return unwrapOrThrow(path, 200, res);
        } catch (ApiException e) {
            throw e;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 422 || status == 400) {
                Map<String, Object> structured = tryExtractStructuredData(e.getResponseBodyAsString());
                if (structured != null) {
                    return structured;
                }
            }
            throw translateHttpError(path, e);
        } catch (Exception e) {
            log.warn("SUREPASS POST failed path={} err={}", path, e.getMessage());
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "KYC_PROVIDER_UNAVAILABLE",
                    "Verification provider could not be reached. Please try again.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        String authHeader = requireBearerHeader();
        try {
            Map<String, Object> res = executeGetJson(path, authHeader);
            return unwrapOrThrow(path, 200, res);
        } catch (ApiException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw translateHttpError(path, e);
        } catch (Exception e) {
            log.warn("SUREPASS GET failed path={} err={}", path, e.getMessage());
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "KYC_PROVIDER_UNAVAILABLE",
                    "Verification provider could not be reached. Please try again.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executePostJson(String path, Map<String, Object> body, String authHeader) throws Exception {
        String currentPath = path;
        for (int hop = 0; hop <= MAX_SAME_ORIGIN_REDIRECTS; hop++) {
            ResponseEntity<String> entity = rest.post()
                    .uri(currentPath)
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);

            int code = entity.getStatusCode().value();
            if (isRedirectStatus(code) && hop < MAX_SAME_ORIGIN_REDIRECTS) {
                String nextPath = resolveSameOriginRedirectPath(entity.getHeaders().getLocation());
                if (nextPath != null) {
                    log.debug("SUREPASS following {} redirect {} -> {}", code, currentPath, nextPath);
                    currentPath = nextPath;
                    continue;
                }
            }
            return parseJsonObject(currentPath, code, entity.getBody());
        }
        throw new IllegalStateException("Too many redirects from Surepass on " + path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeGetJson(String path, String authHeader) throws Exception {
        String currentPath = path;
        for (int hop = 0; hop <= MAX_SAME_ORIGIN_REDIRECTS; hop++) {
            ResponseEntity<String> entity = rest.get()
                    .uri(currentPath)
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toEntity(String.class);

            int code = entity.getStatusCode().value();
            if (isRedirectStatus(code) && hop < MAX_SAME_ORIGIN_REDIRECTS) {
                String nextPath = resolveSameOriginRedirectPath(entity.getHeaders().getLocation());
                if (nextPath != null) {
                    log.debug("SUREPASS following {} redirect {} -> {}", code, currentPath, nextPath);
                    currentPath = nextPath;
                    continue;
                }
            }
            return parseJsonObject(currentPath, code, entity.getBody());
        }
        throw new IllegalStateException("Too many redirects from Surepass on " + path);
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 307 || status == 308;
    }

    private String resolveSameOriginRedirectPath(URI location) {
        if (location == null) {
            return null;
        }
        try {
            URI base = URI.create(this.baseUrl);
            URI target = base.resolve(location);
            if (!isSameOrigin(base, target)) {
                log.warn("SUREPASS refusing cross-origin redirect base={} target={}", base, target);
                return null;
            }
            String rawPath = StringUtils.hasText(target.getRawPath()) ? target.getRawPath() : "/";
            return target.getRawQuery() != null ? rawPath + "?" + target.getRawQuery() : rawPath;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSameOrigin(URI a, URI b) {
        if (a == null || b == null || a.getScheme() == null || b.getScheme() == null
                || a.getHost() == null || b.getHost() == null) {
            return false;
        }
        if (!a.getScheme().equalsIgnoreCase(b.getScheme()) || !a.getHost().equalsIgnoreCase(b.getHost())) {
            return false;
        }
        return effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String path, int status, String rawBody) throws Exception {
        if (!StringUtils.hasText(rawBody)) {
            return Map.of();
        }
        return MAPPER.readValue(rawBody, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tryExtractStructuredData(String rawBody) {
        if (!StringUtils.hasText(rawBody)) {
            return null;
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(rawBody, Map.class);
            if (parsed.get("data") instanceof Map<?, ?> m && !m.isEmpty()) {
                return (Map<String, Object>) m;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapOrThrow(String path, int httpStatus, Map<String, Object> res) {
        if (res == null || Boolean.FALSE.equals(res.get("success"))) {
            String msg = res != null && res.get("message") != null
                    ? String.valueOf(res.get("message"))
                    : "Verification provider rejected request";
            String msgCode = res != null && res.get("message_code") != null
                    ? String.valueOf(res.get("message_code"))
                    : "";
            throw mapProviderError(path, httpStatus, msg, msgCode);
        }
        Object data = res.get("data");
        return data instanceof Map<?, ?> m ? (Map<String, Object>) m : res;
    }

    @SuppressWarnings("unchecked")
    private ApiException translateHttpError(String path, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String msg = "Verification provider returned an error";
        String msgCode = "";
        try {
            String raw = e.getResponseBodyAsString();
            if (StringUtils.hasText(raw)) {
                Map<String, Object> parsed = MAPPER.readValue(raw, Map.class);
                if (parsed.get("message") != null) {
                    msg = String.valueOf(parsed.get("message"));
                }
                if (parsed.get("message_code") != null) {
                    msgCode = String.valueOf(parsed.get("message_code"));
                }
                if (parsed.get("data") instanceof Map<?, ?> dataMap) {
                    String detail = firstNonBlank(
                            str(dataMap.get("remarks")),
                            str(dataMap.get("error_description")));
                    if (!detail.isBlank() && !msg.toLowerCase(Locale.ROOT).contains(detail.toLowerCase(Locale.ROOT))) {
                        msg = msg.endsWith(".")
                                ? msg.substring(0, msg.length() - 1) + ": " + detail
                                : msg + ": " + detail;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return mapProviderError(path, status, msg, msgCode);
    }

    private ApiException mapProviderError(String path, int status, String msg, String msgCode) {
        String lowerMsg = (msg == null ? "" : msg).toLowerCase(Locale.ROOT);
        String lowerCode = (msgCode == null ? "" : msgCode).toLowerCase(Locale.ROOT);

        if (status == 401 || lowerCode.contains("invalid_token") || lowerCode.contains("unauthorized")) {
            log.error("SUREPASS_AUTH_ERROR path={} status={} code={} message={}", path, status, msgCode, msg);
            return ApiException.of(HttpStatus.BAD_GATEWAY, "KYC_PROVIDER_AUTH",
                    "Verification service is temporarily unavailable.");
        }

        if ((status == 404 || status == 400)
                && (lowerMsg.contains("client") && lowerMsg.contains("not found")
                || lowerCode.contains("client_not_found")
                || lowerCode.contains("digilocker_client_not_found"))) {
            log.warn("SUREPASS_CLIENT_NOT_FOUND path={} status={} msg={}", path, status, msg);
            return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DIGILOCKER_SESSION_NOT_FOUND",
                    "DigiLocker session expired or was not found. Please start again.");
        }

        if (status == 404 && (lowerMsg.contains("document not found") || lowerCode.contains("document_not_found"))) {
            log.warn("SUREPASS_DOC_NOT_FOUND path={} status={} msg={}", path, status, msg);
            return ApiException.of(HttpStatus.NOT_FOUND, "DIGILOCKER_DOCUMENT_NOT_FOUND",
                    "Requested DigiLocker document was not found.");
        }

        if (status >= 500) {
            log.warn("SUREPASS_SERVER_ERROR path={} status={} msg={}", path, status, msg);
            return ApiException.of(HttpStatus.BAD_GATEWAY, "KYC_PROVIDER_UNAVAILABLE",
                    "Verification provider could not be reached. Please try again.");
        }

        log.warn("SUREPASS_PROVIDER_ERROR path={} status={} code={} msg={}", path, status, msgCode, msg);
        return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "KYC_PROVIDER_ERROR", msg);
    }

    private void logShapeOnce(String path, Map<String, Object> data) {
        if (data != null && loggedShapePaths.add(path)) {
            log.info("SUREPASS response keys path={} keys={}", path, data.keySet());
        }
    }

    private static String enc(String val) {
        return URLEncoder.encode(val == null ? "" : val.trim(), StandardCharsets.UTF_8);
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String joinAddressParts(String... parts) {
        List<String> nonEmpty = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                nonEmpty.add(p.trim());
            }
        }
        return String.join(", ", nonEmpty);
    }

    private static byte[] decodeBase64Safe(String raw) {
        if (raw == null || raw.isBlank()) {
            return new byte[0];
        }
        String clean = raw.trim();
        int comma = clean.indexOf(',');
        if (comma >= 0) {
            clean = clean.substring(comma + 1).trim();
        }
        try {
            return Base64.getMimeDecoder().decode(clean);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static double num(Object val) {
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return val == null ? 0.0 : Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
