package com.fintech.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Assembles unified retailer KYC, business, geo-location, and bank fields required across
 * Emerald ({@code INSTANTPAY}) and multi-provider onboarding sheets (including 24-column CSV export).
 */
@Component
public class RetailerOnboardingProfileAssembler {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static final String VENDOR2_CSV_HEADER =
            "bcagentid,bcagentname,companyname,address,area,pincode,mobilenumber,shopname,shopaddress,"
                    + "shopstate,shopcity,shopdistrict,shoparea,shoppincode,pancard,email,AADHAAR,lat,long,"
                    + "apiusername,IFSC,A/C No,Bank name, A/C Type";

    public record RetailerProviderDossier(
            UUID userId,
            String bcAgentId,
            String userStatus,
            String fullName,
            String firstName,
            String lastName,
            String companyName,
            String shopName,
            String mobile,
            String email,
            String gender,
            String dateOfBirth,
            String pan,
            String panLast4,
            String aadhaar,
            String aadhaarLast4,
            String gstin,
            String homeAddress,
            String homeArea,
            String homeCity,
            String homeDistrict,
            String homeState,
            String homePincode,
            String shopAddress,
            String shopArea,
            String shopCity,
            String shopDistrict,
            String shopState,
            String shopPincode,
            String latitude,
            String longitude,
            UUID bankAccountId,
            String accountHolderName,
            String accountNumber,
            String ifsc,
            String bankName,
            String accountType,
            String bankVerificationStatus,
            boolean personalVerified,
            boolean businessVerified,
            boolean agreementSigned,
            List<String> missingFields
    ) {
        public boolean readyForEmerald() {
            return missingFields.isEmpty();
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FieldCipher fieldCipher;

    public RetailerOnboardingProfileAssembler(JdbcTemplate jdbc, ObjectMapper mapper, FieldCipher fieldCipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.fieldCipher = fieldCipher;
    }

    public RetailerProviderDossier assemble(UUID userId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT u.id, u.code, u.full_name, u.mobile, u.email, u.status AS user_status,
                           p.pan_enc, p.pan_last4, p.aadhaar_enc, p.aadhaar_last4, p.gstin,
                           p.shop_name, p.business_type,
                           COALESCE(p.personal_identity_verified, FALSE) AS personal_verified,
                           COALESCE(p.business_verified, FALSE) AS business_verified,
                           p.business_status, p.agreement_status,
                           p.work_latitude, p.work_longitude,
                           p.identity_details::text AS identity_details,
                           p.personal_details::text AS personal_details,
                           p.business_details::text AS business_details,
                           p.work_address::text AS work_address,
                           p.shop_address::text AS shop_address
                      FROM users u
                      LEFT JOIN agent_kyc_profiles p ON p.user_id = u.id
                     WHERE u.id = ?
                    """, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Retailer was not found");
        }

        Map<String, Object> identity = jsonMap(row.get("identity_details"));
        Map<String, Object> personal = jsonMap(row.get("personal_details"));
        if (personal.isEmpty()) {
            personal = identity;
        }
        Map<String, Object> permAddr = asMap(personal.get("permanentAddress"));
        Map<String, Object> business = jsonMap(row.get("business_details"));
        Map<String, Object> workAddr = jsonMap(row.get("work_address"));
        if (workAddr.isEmpty()) {
            workAddr = jsonMap(row.get("shop_address"));
        }

        String fullName = firstNonBlank(
                str(personal.get("fullName")),
                str(identity.get("fullName")),
                str(row.get("full_name")));
        String[] nameParts = splitName(fullName);

        String shopName = firstNonBlank(
                str(row.get("shop_name")),
                str(business.get("tradeName")),
                str(business.get("legalName")),
                fullName);
        String companyName = firstNonBlank(
                str(business.get("legalName")),
                shopName,
                fullName);

        String mobile = normalizeMobile(str(row.get("mobile")));
        String email = firstNonBlank(
                str(row.get("email")),
                str(business.get("email")),
                str(personal.get("email")));
        String gender = normalizeGender(firstNonBlank(
                str(personal.get("gender")),
                str(identity.get("gender"))));
        String dob = normalizeDob(firstNonBlank(
                str(personal.get("dob")),
                str(personal.get("dateOfBirth")),
                str(identity.get("dob")),
                str(identity.get("dateOfBirth"))));

        String pan = decryptSafe(str(row.get("pan_enc")));
        if (pan.isBlank()) {
            pan = firstNonBlank(str(personal.get("panNumber")), str(identity.get("panNumber"))).toUpperCase(Locale.ROOT);
        }
        String panLast4 = firstNonBlank(str(row.get("pan_last4")), last4(pan));

        String aadhaar = decryptSafe(str(row.get("aadhaar_enc")));
        String aadhaarLast4 = firstNonBlank(str(row.get("aadhaar_last4")), last4(aadhaar));
        String gstin = firstNonBlank(str(row.get("gstin")), str(business.get("gstin")));

        // Home / permanent address
        String homeCity = firstNonBlank(
                str(permAddr.get("vtc")),
                str(permAddr.get("city")),
                str(personal.get("city")));
        String homeDistrict = firstNonBlank(
                str(permAddr.get("district")),
                homeCity);
        String homeState = firstNonBlank(
                str(permAddr.get("state")),
                str(personal.get("state")));
        String homePincode = firstNonBlank(
                str(permAddr.get("pincode")),
                str(personal.get("pincode")));
        String homeArea = firstNonBlank(
                str(permAddr.get("locality")),
                str(permAddr.get("subdistrict")),
                str(permAddr.get("po")),
                homeCity);
        String homeAddress = firstNonBlank(
                str(permAddr.get("fullAddress")),
                str(personal.get("address")),
                joinNonBlank(", ", str(permAddr.get("house")), str(permAddr.get("street")), homeArea, homeCity, homeState, homePincode));

        // Shop / operating address
        String shopCity = firstNonBlank(
                str(workAddr.get("city")),
                str(workAddr.get("vtc")),
                homeCity);
        String shopDistrict = firstNonBlank(
                str(workAddr.get("district")),
                shopCity,
                homeDistrict);
        String shopState = firstNonBlank(
                str(workAddr.get("state")),
                homeState);
        String shopPincode = firstNonBlank(
                str(workAddr.get("pincode")),
                homePincode);
        String shopArea = firstNonBlank(
                str(workAddr.get("area")),
                str(workAddr.get("locality")),
                str(workAddr.get("landmark")),
                homeArea,
                shopCity);
        String shopAddressLine = firstNonBlank(
                 joinNonBlank(", ", str(workAddr.get("line1")), str(workAddr.get("line2"))),
                str(workAddr.get("fullAddress")),
                str(workAddr.get("address")),
                str(business.get("addressText")),
                homeAddress);

        String lat = resolveCoordinate(row.get("work_latitude"), workAddr.get("latitude"), userId, true);
        String lng = resolveCoordinate(row.get("work_longitude"), workAddr.get("longitude"), userId, false);

        // Primary active bank account (prefer VERIFIED, then newest active)
        List<Map<String, Object>> bankRows = jdbc.queryForList("""
                SELECT id, account_name, account_number, ifsc, bank_name,
                       COALESCE(account_type, 'SAVINGS') AS account_type,
                       verification_status
                  FROM retailer_bank_accounts
                 WHERE user_id = ? AND active = TRUE
                 ORDER BY (verification_status = 'VERIFIED') DESC, created_at DESC
                 LIMIT 1
                """, userId);

        UUID bankId = null;
        String accHolder = "";
        String accNumber = "";
        String ifsc = "";
        String bankName = "";
        String accType = "SAVINGS";
        String bankVerStatus = "NOT_ADDED";
        if (!bankRows.isEmpty()) {
            Map<String, Object> b = bankRows.get(0);
            bankId = (UUID) b.get("id");
            accHolder = str(b.get("account_name"));
            accNumber = str(b.get("account_number"));
            ifsc = str(b.get("ifsc"));
            bankName = str(b.get("bank_name"));
            accType = str(b.get("account_type"));
            bankVerStatus = str(b.get("verification_status"));
        }

        boolean personalVerified = Boolean.TRUE.equals(row.get("personal_verified"));
        boolean businessVerified = Boolean.TRUE.equals(row.get("business_verified"))
                || "BUSINESS_VERIFIED".equals(str(row.get("business_status")));
        boolean agreementSigned = "SIGNED".equalsIgnoreCase(str(row.get("agreement_status")));

        List<String> missing = new ArrayList<>();
        if (mobile.length() != 10) missing.add("10-digit mobile number");
        if (nameParts[0].isBlank()) missing.add("First name");
        if (!pan.matches("[A-Z]{5}[0-9]{4}[A-Z]")) missing.add("10-character PAN");
        if (!aadhaar.matches("[0-9]{12}")) missing.add("12-digit Aadhaar number");
        if (email.isBlank() || !email.contains("@")) missing.add("Email address");
        if (shopName.isBlank()) missing.add("Shop / trade name");
        if (shopAddressLine.isBlank() || shopCity.isBlank() || shopState.isBlank() || !shopPincode.matches("[1-9][0-9]{5}")) {
            missing.add("Complete shop address (line, city, state, 6-digit pincode)");
        }
        if (lat.isBlank() || lng.isBlank()) missing.add("Shop GPS coordinates (latitude & longitude)");
        if (bankId == null) {
            missing.add("Active settlement bank account");
        } else if (!"VERIFIED".equalsIgnoreCase(bankVerStatus)) {
            missing.add("Verified settlement bank account");
        }

        return new RetailerProviderDossier(
                userId,
                firstNonBlank(str(row.get("code")), userId.toString().substring(0, 8).toUpperCase(Locale.ROOT)),
                str(row.get("user_status")),
                fullName,
                nameParts[0],
                nameParts[1],
                companyName,
                shopName,
                mobile,
                email,
                gender,
                dob,
                pan,
                panLast4,
                aadhaar,
                aadhaarLast4,
                gstin,
                homeAddress,
                homeArea,
                homeCity,
                homeDistrict,
                homeState,
                homePincode,
                shopAddressLine,
                shopArea,
                shopCity,
                shopDistrict,
                shopState,
                shopPincode,
                lat,
                lng,
                bankId,
                accHolder,
                accNumber,
                ifsc,
                bankName,
                accType,
                bankVerStatus,
                personalVerified,
                businessVerified,
                agreementSigned,
                missing);
    }

    public String toVendor2Csv(RetailerProviderDossier d, String apiUsername) {
        List<String> cols = List.of(
                d.bcAgentId(),
                d.fullName(),
                d.companyName(),
                d.homeAddress(),
                d.homeArea(),
                d.homePincode(),
                d.mobile(),
                d.shopName(),
                d.shopAddress(),
                d.shopState(),
                d.shopCity(),
                d.shopDistrict(),
                d.shopArea(),
                d.shopPincode(),
                d.pan(),
                d.email(),
                d.aadhaar(),
                d.latitude(),
                d.longitude(),
                apiUsername == null ? "" : apiUsername,
                d.ifsc(),
                d.accountNumber(),
                d.bankName(),
                d.accountType());
        StringBuilder sb = new StringBuilder(VENDOR2_CSV_HEADER).append("\r\n");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(cols.get(i)));
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private String resolveCoordinate(Object primary, Object secondary, UUID userId, boolean latitude) {
        String val = firstNonBlank(str(primary), str(secondary));
        if (!val.isBlank()) {
            return val;
        }
        String col = latitude ? "latitude" : "longitude";
        List<BigDecimal> events = jdbc.queryForList(
                "SELECT " + col + " FROM partner_verification_events WHERE user_id = ? AND " + col + " IS NOT NULL ORDER BY created_at DESC LIMIT 1",
                BigDecimal.class, userId);
        if (!events.isEmpty() && events.get(0) != null) {
            return events.get(0).toPlainString();
        }
        return "";
    }

    private String decryptSafe(String enc) {
        if (enc == null || enc.isBlank() || fieldCipher == null) {
            return "";
        }
        try {
            String plain = fieldCipher.decrypt(enc);
            return plain == null ? "" : plain.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> jsonMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equals(s)) {
            return Map.of();
        }
        try {
            return mapper.readValue(s, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    private static String[] splitName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return new String[]{"", ""};
        }
        String trimmed = fullName.trim().replaceAll("\\s+", " ");
        int idx = trimmed.indexOf(' ');
        if (idx < 0) {
            return new String[]{trimmed, ""};
        }
        return new String[]{trimmed.substring(0, idx), trimmed.substring(idx + 1)};
    }

    private static String normalizeMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private static String normalizeGender(String raw) {
        if (raw == null || raw.isBlank()) {
            return "MALE";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("F")) return "FEMALE";
        if (u.startsWith("O") || u.startsWith("T")) return "OTHER";
        return "MALE";
    }

    private static String normalizeDob(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return s;
        }
        if (s.matches("\\d{2}[-/]\\d{2}[-/]\\d{4}")) {
            String[] p = s.split("[-/]");
            return p[2] + "-" + p[1] + "-" + p[0];
        }
        return s;
    }

    private static String last4(String s) {
        if (s == null || s.length() < 4) {
            return "";
        }
        return s.substring(s.length() - 4);
    }

    private static String joinNonBlank(String sep, String... items) {
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                out.add(item.trim());
            }
        }
        return String.join(sep, out);
    }

    private static String csvEscape(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}

