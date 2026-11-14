package com.fintech.agreement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgreementPartyBuilder {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a 'IST'");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgreementProperties props;

    public AgreementPartyBuilder(JdbcTemplate jdbc, ObjectMapper mapper, AgreementProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    public record BuiltParty(
            AgreementType agreementType,
            UUID signatoryId,
            String signatoryName,
            String signatoryMobile,
            boolean signatoryIsSelf,
            Map<String, Object> partySnapshot,
            Map<String, String> templateValues
    ) {}

    public BuiltParty build(UUID userId, String agreementNo, BigDecimal onboardingFee, String releaseStamp) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT u.id, u.code, u.user_type::text AS user_type, u.full_name, u.mobile, u.email,
                           k.business_status, COALESCE(k.business_verified, FALSE) AS business_verified,
                           k.business_type, k.shop_name, k.gstin, k.pan_last4, k.aadhaar_last4,
                           k.identity_details::text AS identity_details,
                           k.personal_details::text AS personal_details,
                           k.business_details::text AS business_details,
                           k.work_address::text AS work_address
                      FROM users u
                      JOIN agent_kyc_profiles k ON k.user_id = u.id
                     WHERE u.id = ?
                    """, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "PARTNER_NOT_FOUND", "Partner profile not found");
        }

        String userType = String.valueOf(row.get("user_type"));
        if (!"RETAILER".equals(userType) && !"MASTER_DISTRIBUTOR".equals(userType)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARTNER_TYPE",
                    "Agreements can only be generated for Retailers and Distributors");
        }

        boolean businessVerified = Boolean.TRUE.equals(row.get("business_verified"))
                || "BUSINESS_VERIFIED".equals(String.valueOf(row.get("business_status")));
        if (!businessVerified) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_VERIFICATION_REQUIRED",
                    "Business registration must be verified by an admin before generating the agreement");
        }

        AgreementType agreementType = AgreementType.forUserType(userType);
        Map<String, Object> identity = jsonMap(row.get("identity_details"));
        Map<String, Object> personal = jsonMap(row.get("personal_details"));
        Map<String, Object> business = jsonMap(row.get("business_details"));
        Map<String, Object> workAddr = jsonMap(row.get("work_address"));

        String bizType = text(row.get("business_type"));
        if (bizType == null) {
            bizType = text(business.get("businessType"));
        }
        if (bizType == null) {
            bizType = "INDIVIDUAL";
        }

        String partnerCode = text(row.get("code"));
        String userMobile = text(row.get("mobile"));
        String userEmail = firstNonBlank(text(row.get("email")), text(business.get("companyEmail")), "—");
        String verifiedPersonName = firstNonBlank(
                text(identity.get("fullName")),
                text(personal.get("fullName")),
                text(row.get("full_name")),
                "Partner"
        );
        String tradeName = firstNonBlank(
                text(row.get("shop_name")),
                text(business.get("tradeName")),
                text(business.get("shopName")),
                verifiedPersonName
        );
        String panLast4 = firstNonBlank(text(row.get("pan_last4")), text(identity.get("panLast4")));
        String maskedPan = panLast4 != null ? "XXXXXX" + panLast4 : "—";

        UUID signatoryId = null;
        String signatoryName;
        String signatoryMobile;
        boolean signatoryIsSelf;
        String signatoryAadhaarLast4;
        String signatoryDesignation;
        String partyName;
        String partyLegalName;
        String partyKind;
        String partyAddress;
        String actingThrough;
        String cinNumber;
        String businessTypeLabel;

        if ("COMPANY".equalsIgnoreCase(bizType)) {
            List<Map<String, Object>> sigs = jdbc.queryForList("""
                    SELECT id, cin, director_name, din, is_self, mobile, status, verified_name, aadhaar_last4
                      FROM business_signatories
                     WHERE user_id = ? AND active = TRUE
                     LIMIT 1
                    """, userId);
            if (sigs.isEmpty()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_NOT_VERIFIED",
                        "Active authorized signatory record is missing for this company");
            }
            Map<String, Object> sig = sigs.get(0);
            String sigStatus = String.valueOf(sig.get("status"));
            if (!"SELF_VERIFIED".equals(sigStatus) && !"KYC_COMPLETED".equals(sigStatus)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATORY_NOT_VERIFIED",
                        "Authorized signatory must complete KYC before generating the agreement");
            }
            signatoryId = (UUID) sig.get("id");
            signatoryIsSelf = Boolean.TRUE.equals(sig.get("is_self"));
            signatoryName = firstNonBlank(text(sig.get("verified_name")), text(sig.get("director_name")), verifiedPersonName);
            signatoryMobile = firstNonBlank(text(sig.get("mobile")), userMobile);
            signatoryAadhaarLast4 = firstNonBlank(text(sig.get("aadhaar_last4")), text(row.get("aadhaar_last4")));
            String din = text(sig.get("din"));
            signatoryDesignation = din != null ? "Director / Authorised Signatory (DIN: " + din + ")" : "Director / Authorised Signatory";

            cinNumber = firstNonBlank(text(sig.get("cin")), text(business.get("cin")), "—");
            String companyName = firstNonBlank(text(business.get("companyName")), text(business.get("legalName")), tradeName);
            partyLegalName = companyName;
            partyName = companyName + (!"—".equals(cinNumber) ? " (CIN: " + cinNumber + ")" : "");
            partyKind = "a company incorporated under the Companies Act";
            partyAddress = firstNonBlank(
                    text(business.get("registeredAddress")),
                    formatAddressMap(workAddr),
                    text(business.get("addressText")),
                    formatAddressMap(jsonMap(business.get("address")))
            );
            actingThrough = signatoryName + (din != null ? " (Director, DIN: " + din + ")" : " (Authorised Signatory)");
            businessTypeLabel = "Company (" + firstNonBlank(text(business.get("companyType")), "Corporate Entity") + ")";
        } else {
            // Check if there is an active self signatory row
            List<Map<String, Object>> sigs = jdbc.queryForList("""
                    SELECT id FROM business_signatories WHERE user_id = ? AND active = TRUE LIMIT 1
                    """, userId);
            if (!sigs.isEmpty()) {
                signatoryId = (UUID) sigs.get(0).get("id");
            }
            signatoryIsSelf = true;
            signatoryName = firstNonBlank(text(business.get("signatoryName")), verifiedPersonName);
            signatoryMobile = userMobile;
            signatoryAadhaarLast4 = firstNonBlank(text(row.get("aadhaar_last4")), text(identity.get("aadhaarLast4")));
            signatoryDesignation = "Proprietor / Individual";
            cinNumber = "N/A (Individual / Proprietorship)";
            partyLegalName = verifiedPersonName;
            if (tradeName != null && !tradeName.equalsIgnoreCase(verifiedPersonName)) {
                partyName = verifiedPersonName + " (Proprietor of M/s. " + tradeName + ")";
            } else {
                partyName = verifiedPersonName;
            }
            partyKind = "an individual / sole proprietorship concern";
            partyAddress = firstNonBlank(
                    formatAddressMap(workAddr),
                    text(business.get("addressText")),
                    formatAddressMap(jsonMap(business.get("address"))),
                    extractPermanentAddressText(identity, personal)
            );
            actingThrough = signatoryName + " (Self / Proprietor)";
            businessTypeLabel = "Individual / Sole Proprietorship";
        }

        if (partyAddress == null || partyAddress.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PARTY_ADDRESS_MISSING",
                    "Partner business or registered address is missing");
        }

        String feeFormatted = formatOnboardingFee(onboardingFee);
        String maskedAadhaar = signatoryAadhaarLast4 != null ? "XXXX XXXX " + signatoryAadhaarLast4 : "—";
        String generatedDate = ZonedDateTime.now(IST).format(DATE_FMT);

        AgreementProperties.CompanyProperties comp = props.company();

        Map<String, String> tv = new LinkedHashMap<>();
        tv.put("agreementNo", agreementNo);
        tv.put("partnerCode", partnerCode);
        tv.put("templateVersion", agreementType.templateVersion());
        tv.put("generatedDate", generatedDate);
        tv.put("companyName", comp.name());
        tv.put("companyAddress", comp.address());
        tv.put("companySignatory", comp.signatory());
        tv.put("companyBoardResolutionDate", comp.boardResolutionDate());
        tv.put("companyPlace", comp.place());
        tv.put("partyName", partyName);
        tv.put("partyLegalName", partyLegalName);
        tv.put("partyKind", partyKind);
        tv.put("partyAddress", partyAddress);
        tv.put("actingThrough", actingThrough);
        tv.put("onboardingFee", feeFormatted);
        tv.put("businessTypeLabel", businessTypeLabel);
        tv.put("tradeName", tradeName);
        tv.put("cinNumber", cinNumber);
        tv.put("maskedPan", maskedPan);
        tv.put("signatoryName", signatoryName);
        tv.put("signatoryDesignation", signatoryDesignation);
        tv.put("signatoryMaskedAadhaar", maskedAadhaar);
        tv.put("signatoryMobile", signatoryMobile != null ? "+91 " + signatoryMobile : "—");
        tv.put("partnerEmail", userEmail);
        tv.put("releaseStamp", releaseStamp != null ? releaseStamp : "Generated (Pending Admin Release)");

        Map<String, Object> snapshot = new LinkedHashMap<>(tv);
        snapshot.put("businessType", bizType.toUpperCase());
        snapshot.put("userType", userType);
        snapshot.put("signatoryIsSelf", signatoryIsSelf);
        snapshot.put("rawSignatoryMobile", signatoryMobile);
        snapshot.put("onboardingFeeAmount", onboardingFee);

        return new BuiltParty(
                agreementType,
                signatoryId,
                signatoryName,
                signatoryMobile,
                signatoryIsSelf,
                snapshot,
                tv
        );
    }

    public static String formatOnboardingFee(BigDecimal fee) {
        if (fee == null || fee.compareTo(BigDecimal.ZERO) <= 0) {
            return "NIL";
        }
        long rupees = fee.setScale(0, RoundingMode.HALF_UP).longValue();
        return "Rs. " + String.format("%,d", rupees) + " (Rupees " + numberToWords(rupees) + " Only) plus applicable taxes";
    }

    private static String numberToWords(long n) {
        if (n <= 0) return "Zero";
        String[] units = {
                "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
                "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"
        };
        String[] tens = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};
        StringBuilder sb = new StringBuilder();
        if (n >= 10_000_000) {
            sb.append(numberToWords(n / 10_000_000)).append(" Crore ");
            n %= 10_000_000;
        }
        if (n >= 100_000) {
            sb.append(numberToWords(n / 100_000)).append(" Lakh ");
            n %= 100_000;
        }
        if (n >= 1_000) {
            sb.append(numberToWords(n / 1_000)).append(" Thousand ");
            n %= 1_000;
        }
        if (n >= 100) {
            sb.append(units[(int) (n / 100)]).append(" Hundred ");
            n %= 100;
        }
        if (n > 0) {
            if (n < 20) {
                sb.append(units[(int) n]);
            } else {
                sb.append(tens[(int) (n / 10)]);
                if (n % 10 > 0) {
                    sb.append(" ").append(units[(int) (n % 10)]);
                }
            }
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private String extractPermanentAddressText(Map<String, Object> identity, Map<String, Object> personal) {
        Object perm = identity.get("permanentAddress");
        if (perm instanceof Map<?, ?> m) {
            String formatted = formatAddressMap((Map<String, Object>) m);
            if (formatted != null) return formatted;
        }
        Object perm2 = personal.get("permanentAddress");
        if (perm2 instanceof Map<?, ?> m) {
            String formatted = formatAddressMap((Map<String, Object>) m);
            if (formatted != null) return formatted;
        }
        return firstNonBlank(text(identity.get("address")), text(personal.get("address")));
    }

    private String formatAddressMap(Map<String, Object> addr) {
        if (addr == null || addr.isEmpty()) return null;
        String full = firstNonBlank(text(addr.get("fullAddress")), text(addr.get("formatted")));
        String line1 = firstNonBlank(text(addr.get("line1")), text(addr.get("line")), text(addr.get("street")));
        String city = firstNonBlank(text(addr.get("city")), text(addr.get("vtc")), text(addr.get("locality")));
        String district = text(addr.get("district"));
        String state = text(addr.get("state"));
        String pincode = firstNonBlank(text(addr.get("pincode")), text(addr.get("zip")));

        List<String> parts = new java.util.ArrayList<>();
        if (line1 != null) parts.add(line1);
        else if (full != null) parts.add(full);
        if (city != null && (line1 == null || !line1.toLowerCase().contains(city.toLowerCase()))) parts.add(city);
        if (district != null && !district.equalsIgnoreCase(city)) parts.add(district);
        if (state != null) parts.add(state);
        if (pincode != null) parts.add(pincode);
        String joined = String.join(", ", parts).trim();
        return joined.isEmpty() ? full : joined;
    }

    private Map<String, Object> jsonMap(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        String str = String.valueOf(raw);
        if (str.isBlank() || "{}".equals(str)) return Map.of();
        try {
            return mapper.readValue(str, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String text(Object val) {
        if (val == null) return null;
        String s = String.valueOf(val).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return null;
    }
}

