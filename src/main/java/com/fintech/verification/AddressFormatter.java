package com.fintech.verification;

import com.fintech.kyc.surepass.SurepassClient;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

public final class AddressFormatter {

    public record BusinessAddressInput(
            String line1,
            String line2,
            String area,
            String landmark,
            String city,
            String district,
            String state,
            String pincode,
            Boolean sameAsPersonal
    ) {
        public BusinessAddressInput(
                String line1,
                String line2,
                String landmark,
                String city,
                String district,
                String state,
                String pincode,
                Boolean sameAsPersonal
        ) {
            this(line1, line2, null, landmark, city, district, state, pincode, sameAsPersonal);
        }
    }

    public static final Map<String, String> INDIAN_STATES = createStatesMap();

    private AddressFormatter() {}

    public static Map<String, Object> toPermanentAddressMap(
            SurepassClient.AadhaarAddress addr,
            String digilockerRef,
            Instant verifiedAt
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (addr == null) {
            return out;
        }
        String careOf = safe(addr.careOf());
        String house = safe(addr.house());
        String street = safe(addr.street());
        String landmark = safe(addr.landmark());
        String locality = safe(addr.locality());
        String vtc = safe(addr.vtc());
        String postOffice = safe(addr.postOffice());
        String subDistrict = safe(addr.subDistrict());
        String district = safe(addr.district());
        String state = normalizeStateOrKeep(addr.state());
        String pincode = safe(addr.pincode());
        String country = safe(addr.country()).isEmpty() ? "India" : safe(addr.country());
        String fullAddress = safe(addr.fullAddress());
        if (fullAddress.isEmpty()) {
            fullAddress = joinNonEmpty(house, street, landmark, locality, vtc, postOffice, district, state, pincode, country);
        }

        out.put("careOf", careOf);
        out.put("house", house);
        out.put("street", street);
        out.put("landmark", landmark);
        out.put("locality", locality);
        out.put("vtc", vtc);
        out.put("postOffice", postOffice);
        out.put("subDistrict", subDistrict);
        out.put("district", district);
        out.put("state", state);
        out.put("pincode", pincode);
        out.put("country", country);
        out.put("fullAddress", fullAddress);
        out.put("source", "DIGILOCKER_AADHAAR");
        out.put("verified", true);
        out.put("verifiedAt", (verifiedAt == null ? Instant.now() : verifiedAt).toString());
        out.put("digilockerRef", safe(digilockerRef));
        return out;
    }

    public static Map<String, Object> copyFromPermanentAddress(
            Map<String, Object> permanentAddress,
            String fallbackFlatAddress,
            String fallbackCity,
            String fallbackState,
            String fallbackPincode,
            String registryAddress,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        String house = str(permanentAddress, "house");
        String street = str(permanentAddress, "street");
        String landmark = str(permanentAddress, "landmark");
        String locality = str(permanentAddress, "locality");
        String vtc = str(permanentAddress, "vtc");
        String district = str(permanentAddress, "district");
        String state = firstNonBlank(str(permanentAddress, "state"), fallbackState);
        String pincode = firstNonBlank(str(permanentAddress, "pincode"), fallbackPincode);
        String full = firstNonBlank(str(permanentAddress, "fullAddress"), fallbackFlatAddress);

        String line1 = joinNonEmpty(house, street);
        if (line1.isEmpty()) {
            line1 = firstNonBlank(locality, full, "As per Aadhaar address");
        }
        String line2 = joinNonEmpty(locality, vtc);
        String area = firstNonBlank(locality, str(permanentAddress, "postOffice"), vtc, str(permanentAddress, "subDistrict"));
        String city = firstNonBlank(vtc, district, fallbackCity, locality);
        String resolvedDistrict = firstNonBlank(district, str(permanentAddress, "subDistrict"), city);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("line1", line1);
        out.put("line2", line2);
        out.put("area", area);
        out.put("landmark", landmark);
        out.put("city", city);
        out.put("district", resolvedDistrict);
        out.put("state", normalizeStateOrKeep(state));
        out.put("pincode", pincode);
        out.put("country", "India");
        out.put("fullAddress", full.isEmpty() ? joinNonEmpty(line1, line2, landmark, city, state, pincode) : full);
        out.put("sameAsPersonal", true);
        out.put("source", "COPIED_FROM_AADHAAR");
        if (registryAddress != null && !registryAddress.isBlank()) {
            out.put("registryAddress", registryAddress.trim());
        }
        if (latitude != null) {
            out.put("latitude", latitude);
        }
        if (longitude != null) {
            out.put("longitude", longitude);
        }
        out.put("capturedAt", Instant.now().toString());
        return out;
    }

    public static Map<String, Object> buildBusinessAddress(
            String line1,
            String line2,
            String landmark,
            String city,
            String district,
            String state,
            String pincode,
            String registryAddress,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        return buildBusinessAddress(line1, line2, null, landmark, city, district, state, pincode,
                registryAddress, latitude, longitude);
    }

    public static Map<String, Object> buildBusinessAddress(
            String line1,
            String line2,
            String area,
            String landmark,
            String city,
            String district,
            String state,
            String pincode,
            String registryAddress,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        String cleanLine1 = safe(line1);
        if (cleanLine1.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_ADDRESS_REQUIRED",
                    "Business address line 1 is required");
        }
        String cleanCity = safe(city);
        if (cleanCity.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_CITY_REQUIRED",
                    "Business city is required");
        }
        String cleanState = normalizeState(state);
        String cleanPincode = validatePincode(pincode);
        String cleanLine2 = safe(line2);
        String cleanArea = firstNonBlank(safe(area), cleanLine2, cleanCity);
        String cleanLandmark = safe(landmark);
        String cleanDistrict = firstNonBlank(safe(district), cleanCity);

        String fullAddress = joinNonEmpty(cleanLine1, cleanLine2,
                cleanArea.equalsIgnoreCase(cleanLine2) || cleanArea.equalsIgnoreCase(cleanCity) ? "" : cleanArea,
                cleanLandmark, cleanCity, cleanDistrict.equalsIgnoreCase(cleanCity) ? "" : cleanDistrict,
                cleanState + " - " + cleanPincode);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("line1", cleanLine1);
        out.put("line2", cleanLine2);
        out.put("area", cleanArea);
        out.put("landmark", cleanLandmark);
        out.put("city", cleanCity);
        out.put("district", cleanDistrict);
        out.put("state", cleanState);
        out.put("pincode", cleanPincode);
        out.put("country", "India");
        out.put("fullAddress", fullAddress);
        out.put("sameAsPersonal", false);
        out.put("source", "PARTNER_ENTERED");
        if (registryAddress != null && !registryAddress.isBlank()) {
            out.put("registryAddress", registryAddress.trim());
        }
        if (latitude != null) {
            out.put("latitude", latitude);
        }
        if (longitude != null) {
            out.put("longitude", longitude);
        }
        out.put("capturedAt", Instant.now().toString());
        return out;
    }

    public static boolean differsFromPersonal(Map<String, Object> businessAddr, Map<String, Object> permanentAddr,
                                              String fallbackPersonalPincode, String fallbackPersonalState) {
        if (businessAddr == null || Boolean.TRUE.equals(businessAddr.get("sameAsPersonal"))) {
            return false;
        }
        String bizPin = safe(String.valueOf(businessAddr.getOrDefault("pincode", "")));
        String bizState = normalizeStateOrKeep(String.valueOf(businessAddr.getOrDefault("state", "")));
        String perPin = firstNonBlank(str(permanentAddr, "pincode"), fallbackPersonalPincode);
        String perState = normalizeStateOrKeep(firstNonBlank(str(permanentAddr, "state"), fallbackPersonalState));

        if (!bizPin.isEmpty() && !perPin.isEmpty() && !bizPin.equals(perPin)) {
            return true;
        }
        if (!bizState.isEmpty() && !perState.isEmpty() && !bizState.equalsIgnoreCase(perState)) {
            return true;
        }
        String bizFull = safe(String.valueOf(businessAddr.getOrDefault("fullAddress", "")));
        String perFull = str(permanentAddr, "fullAddress");
        return !bizFull.isEmpty() && !perFull.isEmpty() && !bizFull.equalsIgnoreCase(perFull);
    }

    public static String validatePincode(String pincode) {
        String clean = safe(pincode).replaceAll("\\s+", "");
        if (!clean.matches("^[1-9]\\d{5}$")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PINCODE",
                    "Enter a valid 6-digit Indian pincode");
        }
        return clean;
    }

    public static String normalizeState(String rawState) {
        String clean = safe(rawState);
        if (clean.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_STATE_REQUIRED",
                    "Business state is required");
        }
        String canonical = INDIAN_STATES.get(clean.toUpperCase(Locale.ROOT));
        if (canonical == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE",
                    "Select a valid Indian State or Union Territory");
        }
        return canonical;
    }

    public static String normalizeStateOrKeep(String rawState) {
        String clean = safe(rawState);
        if (clean.isEmpty()) {
            return "";
        }
        String canonical = INDIAN_STATES.get(clean.toUpperCase(Locale.ROOT));
        return canonical != null ? canonical : clean;
    }

    public static String joinNonEmpty(String... parts) {
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String s = safe(p);
            if (!s.isEmpty()) {
                list.add(s);
            }
        }
        return String.join(", ", list);
    }

    private static String str(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String safe(String val) {
        return val == null ? "" : val.trim();
    }

    private static Map<String, String> createStatesMap() {
        Map<String, String> m = new LinkedHashMap<>();
        String[][] entries = {
                {"AN", "Andaman and Nicobar Islands"},
                {"AP", "Andhra Pradesh"},
                {"AR", "Arunachal Pradesh"},
                {"AS", "Assam"},
                {"BR", "Bihar"},
                {"CH", "Chandigarh"},
                {"CT", "Chhattisgarh"}, {"CG", "Chhattisgarh"},
                {"DN", "Dadra and Nagar Haveli and Daman and Diu"}, {"DD", "Dadra and Nagar Haveli and Daman and Diu"},
                {"DL", "Delhi"}, {"NCT OF DELHI", "Delhi"}, {"NEW DELHI", "Delhi"},
                {"GA", "Goa"},
                {"GJ", "Gujarat"},
                {"HR", "Haryana"},
                {"HP", "Himachal Pradesh"},
                {"JK", "Jammu and Kashmir"},
                {"JH", "Jharkhand"},
                {"KA", "Karnataka"},
                {"KL", "Kerala"},
                {"LA", "Ladakh"},
                {"LD", "Lakshadweep"},
                {"MP", "Madhya Pradesh"},
                {"MH", "Maharashtra"},
                {"MN", "Manipur"},
                {"ML", "Meghalaya"},
                {"MZ", "Mizoram"},
                {"NL", "Nagaland"},
                {"OD", "Odisha"}, {"OR", "Odisha"}, {"ORISSA", "Odisha"},
                {"PY", "Puducherry"}, {"PONDICHERRY", "Puducherry"},
                {"PB", "Punjab"},
                {"RJ", "Rajasthan"},
                {"SK", "Sikkim"},
                {"TN", "Tamil Nadu"},
                {"TG", "Telangana"}, {"TS", "Telangana"},
                {"TR", "Tripura"},
                {"UP", "Uttar Pradesh"},
                {"UK", "Uttarakhand"}, {"UT", "Uttarakhand"}, {"UTTARANCHAL", "Uttarakhand"},
                {"WB", "West Bengal"}
        };
        for (String[] pair : entries) {
            m.put(pair[0].toUpperCase(Locale.ROOT), pair[1]);
            m.put(pair[1].toUpperCase(Locale.ROOT), pair[1]);
        }
        return Map.copyOf(m);
    }
}
