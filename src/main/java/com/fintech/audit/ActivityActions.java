package com.fintech.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Plain action names for known routes, with a human-readable fallback for any unmapped route. */
public final class ActivityActions {

    private static final Pattern UUID_PART = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Set<String> STRUCTURAL_SEGMENTS = Set.of(
            "api", "v1", "admin", "desk", "public", "me", "users", "session", "{id}");

    private static final Set<String> UPPERCASE_WORDS = Set.of(
            "kyc", "ekyc", "otp", "pan", "gstin", "cin", "upi", "dmt", "fd", "qr", "utr", "ifsc", "pin", "msa");

    private record Rule(String method, Pattern path, String action) {}

    private static Rule rule(String method, String regex, String action) {
        return new Rule(method, Pattern.compile(regex), action);
    }

    private static final List<Rule> RULES = List.of(
            // Authentication, OTP Sign-In & Credential Reset
            rule("POST", "/api/v1/auth/login", ActivityPhrases.USER_LOGIN),
            rule("POST", "/api/v1/auth/logout", ActivityPhrases.USER_LOGOUT),
            rule("POST", "/api/v1/auth/otp/send", "Login OTP Sent"),
            rule("POST", "/api/v1/auth/otp/verify", "User OTP Login"),
            rule("POST", "/api/v1/auth/reset/otp", "Password Reset OTP Sent"),
            rule("POST", "/api/v1/auth/reset/verify", "Password Reset OTP Verified"),
            rule("POST", "/api/v1/auth/reset/confirm", "Password Reset Completed"),

            // Admin Platform, Hubs, Services & Users
            rule("POST", "/api/v1/admin/hubs", "Hub Created"),
            rule("PATCH", "/api/v1/admin/hubs/[^/]+", "Hub Updated"),
            rule("POST", "/api/v1/admin/hubs/[^/]+/distributors/assign", "Distributor Assigned to Hub"),
            rule("POST", "/api/v1/admin/hubs/[^/]+/admins/assign", "Admin Assigned to Hub"),
            rule("PATCH", "/api/v1/admin/services/[^/]+", "Service Updated"),
            rule("PUT", "/api/v1/admin/services/[^/]+/commission", "Service Commission Updated"),
            rule("POST", "/api/v1/wallet/rewards/transfer", "Rewards Moved to Wallet"),
            rule("POST", "/api/v1/admin/dmt-banks/upload", "DMT Bank List Uploaded"),
            rule("POST", "/api/v1/admin/dmt-banks", "DMT Bank Added"),
            rule("PATCH", "/api/v1/admin/dmt-banks/[^/]+", "DMT Bank Updated"),
            rule("POST", "/api/v1/admin/users/[^/]+/paysprint-onboard/status", "eKYC Status Checked"),
            rule("POST", "/api/v1/admin/users/[^/]+/paysprint-onboard/refresh", "New eKYC Link"),
            rule("POST", "/api/v1/admin/users/[^/]+/paysprint-onboard", "eKYC Link Created"),
            rule("POST", "/api/v1/admin/users/distributors", "Distributor Created"),
            rule("POST", "/api/v1/admin/users/retailers", "Retailer Created"),
            rule("POST", "/api/v1/admin/users/admins", "Admin Created"),
            rule("POST", "/api/v1/admin/users/auditors", "Auditor Created"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/status", "Status Change"),
            rule("POST", "/api/v1/admin/users/[^/]+/require-reverification", "KYC Re-verification Requested"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/hub", "Hub Changed"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/distributor", "Distributor Changed"),
            rule("POST", "/api/v1/admin/users/[^/]+/password", "Password Reset"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/wallet/freeze", "Wallet Freeze Changed"),
            rule("POST", "/api/v1/admin/users/[^/]+/wallet/adjust", "Wallet Adjusted"),
            rule("POST", "/api/v1/admin/kyc/[^/]+/review", "KYC Reviewed"),
            rule("POST", "/api/v1/admin/transactions/[^/]+/verify-status", "Transaction Status Verified"),
            rule("POST", "/api/v1/admin/recon/reports", "Recon Report Uploaded"),
            rule("POST", "/api/v1/admin/recon/mismatches/[^/]+/resolve", "Recon Issue Resolved"),
            rule("POST", "/api/v1/admin/recon", "Recon Started"),
            rule("DELETE", "/api/v1/admin/recon/[^/]+", "Recon Removed"),
            rule("POST", "/api/v1/admin/banks/[^/]+/active", "Bank Status Changed"),
            rule("POST", "/api/v1/admin/banks/[^/]+", "Bank Updated"),
            rule("POST", "/api/v1/admin/banks", "Bank Added"),
            rule("POST", "/api/v1/admin/vendors", "Vendor Added"),
            rule("PATCH", "/api/v1/admin/vendors/[^/]+/status", "Vendor Status Changed"),
            rule("PATCH", "/api/v1/admin/catalog/items/[^/]+", "Catalog Updated"),
            rule("POST", "/api/v1/admin/inquiries/[^/]+/retire", "Inquiry Closed"),
            rule("POST", "/api/v1/admin/inquiries/[^/]+/not-interested", "Inquiry Marked Not Interested"),
            rule("POST", "/api/v1/admin/inquiries/[^/]+/assign-hub", "Inquiry Assigned to Hub"),
            rule("POST", "/api/v1/admin/inquiries/[^/]+/assign-distributor", "Inquiry Assigned to Distributor"),
            rule("POST", "/api/v1/admin/inquiries/[^/]+/convert-distributor", "Inquiry Converted to Distributor"),
            rule("PATCH", "/api/v1/admin/fd-cards/providers/[^/]+/config", "FD Provider Settings Updated"),
            rule("PATCH", "/api/v1/admin/fd-cards/providers/[^/]+", "FD Provider Updated"),
            rule("PATCH", "/api/v1/admin/fd-cards/cards/[^/]+", "FD Card Updated"),
            rule("POST", "/api/v1/admin/fd-cards/rules", "FD Rule Added"),
            rule("PATCH", "/api/v1/admin/fd-cards/rules/[^/]+", "FD Rule Updated"),
            rule("DELETE", "/api/v1/admin/fd-cards/rules/[^/]+", "FD Rule Removed"),
            rule("PUT", "/api/v1/admin/fd-cards/budget-bands", "FD Budget Updated"),
            rule("POST", "/api/v1/admin/fd-cards/commission-tiers", "FD Commission Added"),
            rule("PATCH", "/api/v1/admin/fd-cards/commission-tiers/[^/]+", "FD Commission Updated"),
            rule("DELETE", "/api/v1/admin/fd-cards/commission-tiers/[^/]+", "FD Commission Removed"),
            rule("PUT", "/api/v1/admin/fd-cards/providers/[^/]+/commission", "FD Commission Updated"),

            // Admin Provider Onboarding, Channel Controls & Retailer Bank Controls
            rule("PATCH", "/api/v1/admin/providers/channels/[^/]+", "Banking Channel Updated"),
            rule("PUT", "/api/v1/admin/providers/routing/[^/]+", "Service Routing Policy Updated"),
            rule("POST", "/api/v1/admin/users/[^/]+/providers/emerald/onboard", "Provider Onboarded"),
            rule("POST", "/api/v1/admin/users/[^/]+/providers/emerald/sync-bank", "Provider Settlement Bank Synced"),
            rule("POST", "/api/v1/admin/users/[^/]+/providers/emerald/refresh", "Provider Status Checked"),
            rule("POST", "/api/v1/admin/users/[^/]+/providers/[^/]+/onboard", "Provider Onboarded"),
            rule("POST", "/api/v1/admin/users/[^/]+/providers/[^/]+/sync-bank", "Provider Settlement Bank Synced"),
            rule("POST", "/api/v1/admin/users/[^/]+/providers/[^/]+/refresh", "Provider Status Checked"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/providers/channels/[^/]+", "Retailer Banking Channel Updated"),
            rule("POST", "/api/v1/admin/users/[^/]+/banks/[^/]+/verify", "Bank Account Verified"),
            rule("POST", "/api/v1/admin/users/[^/]+/banks/[^/]+/active", "Bank Status Changed"),

            // Distributor / Hub Desk
            rule("POST", "/api/v1/desk/outlets/[^/]+/paysprint-onboard/status", "eKYC Status Checked"),
            rule("POST", "/api/v1/desk/outlets/[^/]+/paysprint-onboard/refresh", "New eKYC Link"),
            rule("POST", "/api/v1/desk/outlets/[^/]+/paysprint-onboard", "eKYC Link Created"),
            rule("POST", "/api/v1/desk/outlets", "Retailer Created"),
            rule("PATCH", "/api/v1/desk/outlets/[^/]+/status", "Retailer Status Change"),
            rule("POST", "/api/v1/desk/outlets/[^/]+/password", "Retailer Password Reset"),
            rule("PATCH", "/api/v1/desk/outlets/[^/]+/wallet/freeze", "Retailer Wallet Freeze Changed"),
            rule("POST", "/api/v1/desk/outlets/[^/]+/wallet/adjust", "Retailer Wallet Adjusted"),
            rule("POST", "/api/v1/desk/transactions/[^/]+/verify-status", "Transaction Status Verified"),
            rule("POST", "/api/v1/desk/inquiries/[^/]+/invite-retailer", "Inquiry Onboarding Invite Sent"),
            rule("POST", "/api/v1/desk/inquiries/[^/]+/convert-retailer", "Inquiry Converted to Retailer"),

            // Finance & Auditor
            rule("POST", "/api/v1/finance/activation-requests", "Activation Payment Submitted"),
            rule("POST", "/api/v1/finance/wallet-requests", "Wallet Request Submitted"),
            rule("POST", "/api/v1/finance/requests/[^/]+/receive", "Payment Received"),
            rule("POST", "/api/v1/finance/requests/[^/]+/transfer", "Withdrawal Transferred"),
            rule("POST", "/api/v1/finance/requests/[^/]+/reject", "Request Rejected"),
            rule("POST", "/api/v1/finance/retailers/[^/]+/activate", "Retailer Activated"),
            rule("POST", "/api/v1/auditor/retailers/[^/]+/wallet/adjust", "Retailer Wallet Adjusted"),

            // Bills, DMT, Wallet Banks, Sales & Hospi Cash
            rule("POST", "/api/v1/bills/fetch", "Bill Fetched"),
            rule("POST", "/api/v1/bills/pay", "Bill Paid"),
            rule("POST", "/api/v1/dmt/senders/[^/]+/beneficiaries/otp", "Beneficiary OTP Sent"),
            rule("POST", "/api/v1/dmt/senders/[^/]+/otp/verify", "Money Transfer OTP Verified"),
            rule("POST", "/api/v1/dmt/senders/[^/]+/otp", "Money Transfer OTP Sent"),
            rule("PATCH", "/api/v1/dmt/senders/[^/]+/kyc", "Sender KYC Updated"),
            rule("POST", "/api/v1/dmt/senders/[^/]+/beneficiaries", "Beneficiary Added"),
            rule("POST", "/api/v1/dmt/beneficiaries/[^/]+/verify", "Beneficiary Verified"),
            rule("DELETE", "/api/v1/dmt/beneficiaries/[^/]+", "Beneficiary Deleted"),
            rule("POST", "/api/v1/dmt/senders", "Sender Added"),
            rule("POST", "/api/v1/dmt/transactions/otp", "Money Transfer OTP Sent"),
            rule("POST", "/api/v1/dmt/transactions/[^/]+/refund/otp", "Money Transfer Refund OTP Sent"),
            rule("POST", "/api/v1/dmt/transactions/[^/]+/refund", "Money Transfer Refund Claimed"),
            rule("POST", "/api/v1/dmt/transactions", "Money Transfer Sent"),
            rule("POST", "/api/v1/wallet/banks/[^/]+/verify", "Bank Account Verified"),
            rule("POST", "/api/v1/wallet/banks/[^/]+/active", "Bank Status Changed"),
            rule("POST", "/api/v1/wallet/banks/[^/]+", "Bank Updated"),
            rule("POST", "/api/v1/wallet/banks", "Bank Added"),
            rule("POST", "/api/v1/wallet/settle", "Wallet Request Submitted"),
            rule("POST", "/api/v1/customers/[^/]+/eligibility", "Customer Eligibility Checked"),
            rule("POST", "/api/v1/customers/[^/]+/leads", "Sales Lead Created"),
            rule("POST", "/api/v1/hospi-cash", "Hospi Cash Sold"),
            rule("POST", "/api/v1/public/affiliates/[^/]+/hospi-cash", "Hospi Cash Sold"),
            rule("PUT", "/api/v1/admin/hospi-cash/pricing", "Hospi Cash Price Updated"),
            rule("POST", "/api/v1/admin/hospi-cash/[^/]+/activation", "Hospi Cash Activation Marked"),
            rule("POST", "/api/v1/admin/hospi-cash/[^/]+/vendor-payment", "Hospi Cash Vendor Paid"),
            rule("POST", "/api/v1/admin/hospi-cash/recon", "Hospi Cash Recon Uploaded"),
            rule("POST", "/api/v1/admin/hospi-cash/earnings/[^/]+/settle", "Hospi Cash Vendor Settled"),
            rule("POST", "/api/v1/customers", "Customer Added"),
            rule("PATCH", "/api/v1/customers/[^/]+", "Customer Updated"),
            rule("POST", "/api/v1/sales/[^/]+/utm-status", "Sales Lead Status Checked"),

            // Cash Out, AePS, Payouts & Biometric eKYC
            rule("POST", "/api/v1/cashout/upi/generate-qr", "Cash Out QR Generated"),
            rule("POST", "/api/v1/cashout/upi/[^/]+/refresh", "Cash Out Status Checked"),
            rule("POST", "/api/v1/cashout/upi/[^/]+/dispense", "Cash Given"),
            rule("POST", "/api/v1/cashout/upi/token", "Cash Out Started"),
            rule("POST", "/api/v1/aeps/transact", "AePS Transaction Submitted"),
            rule("POST", "/api/v1/payouts", "Settlement Payout Initiated"),
            rule("POST", "/api/v1/me/biometric/device-event", "Biometric Scanner Checked"),
            rule("POST", "/api/v1/me/biometric/[^/]+/initiate", "Biometric eKYC Started"),
            rule("POST", "/api/v1/me/biometric/[^/]+/submit", "Biometric eKYC Submitted"),
            rule("POST", "/api/v1/me/ekyc/onboarding/refresh", "Onboarding Link Refreshed"),
            rule("POST", "/api/v1/me/ekyc/device-event", "eKYC Device Check"),
            rule("POST", "/api/v1/me/ekyc/[^/]+/activate", "Merchant eKYC"),
            rule("POST", "/api/v1/admin/users/[^/]+/ekyc/[^/]+/redo", "Bank eKYC Reopened"),

            // KYC Verification, Business Verification & Admin KYC Reviews
            rule("POST", "/api/v1/verification/aadhaar", "Aadhaar Verified"),
            rule("POST", "/api/v1/verification/personal", "Identity KYC Submitted"),
            rule("POST", "/api/v1/verification/business/gstin", "GSTIN Verified"),
            rule("POST", "/api/v1/verification/business/udyam", "Udyam Registration Verified"),
            rule("POST", "/api/v1/verification/business/cin", "Company CIN Verified"),
            rule("POST", "/api/v1/verification/business/company/lookup", "Company Details Fetched"),
            rule("POST", "/api/v1/verification/business/individual", "Individual Business KYC Submitted"),
            rule("POST", "/api/v1/verification/business/company", "Company Business KYC Submitted"),
            rule("POST", "/api/v1/verification/business/signatory/resend", "Signatory KYC Link Resent"),
            rule("POST", "/api/v1/verification/business/signatory/change", "Authorized Signatory Changed"),
            rule("POST", "/api/v1/verification/business/documents", "Business Document Uploaded"),
            rule("POST", "/api/v1/verification/business", "Business Details Saved"),
            rule("POST", "/api/v1/verification/agreement", "Master Agreement Accepted"),
            rule("POST", "/api/v1/verification/users/[^/]+/signatory/face-review", "Signatory Face Match Reviewed"),
            rule("POST", "/api/v1/verification/users/[^/]+/face-review", "Selfie Face Match Reviewed"),
            rule("POST", "/api/v1/verification/users/[^/]+/personal-review", "Identity KYC Reviewed"),
            rule("POST", "/api/v1/verification/users/[^/]+/business-review", "Business KYC Reviewed"),
            rule("POST", "/api/v1/verification/users/[^/]+/digilocker-documents/refetch", "DigiLocker Documents Refetched"),
            rule("POST", "/api/v1/verification/users/[^/]+/digilocker-documents/[^/]+/review", "DigiLocker Document Reviewed"),
            rule("POST", "/api/v1/verification/cash", "Cash Service Checked"),
            rule("POST", "/api/v1/verification/payout", "Payout Service Checked"),

            // Master Service Agreements & Aadhaar eSign
            rule("POST", "/api/v1/agreements/users/[^/]+/generate", "Agreement Draft Generated"),
            rule("POST", "/api/v1/agreements/me/esign/start", "Agreement eSign Started"),
            rule("POST", "/api/v1/agreements/me/esign/copied", "Agreement eSign Link Copied"),
            rule("POST", "/api/v1/agreements/me/esign/refresh", "Agreement eSign Status Verified"),
            rule("POST", "/api/v1/agreements/me/upload-signed", "Signed Agreement Uploaded"),
            rule("POST", "/api/v1/agreements/[^/]+/release", "Agreement Released for Signing"),
            rule("POST", "/api/v1/agreements/[^/]+/void", "Agreement Voided"),
            rule("POST", "/api/v1/agreements/[^/]+/review", "Signed Agreement Reviewed"),

            // Onboarding Invites
            rule("POST", "/api/v1/onboarding/invites/[^/]+/resend", "Onboarding Invite Resent"),
            rule("POST", "/api/v1/onboarding/invites/[^/]+/revoke", "Onboarding Invite Revoked"),
            rule("POST", "/api/v1/onboarding/invites/[^/]+/review", "Onboarding KYC Reviewed"),
            rule("POST", "/api/v1/onboarding/invites", "Onboarding Invite Sent"),

            // Self-Service KYC Onboarding
            rule("POST", "/api/v1/public/onboarding/session/location", "Onboarding Location Captured"),
            rule("POST", "/api/v1/public/onboarding/session/digilocker/start", "Onboarding DigiLocker Started"),
            rule("POST", "/api/v1/public/onboarding/session/digilocker/complete", "Onboarding Aadhaar Verified"),
            rule("POST", "/api/v1/public/onboarding/session/pan", "Onboarding PAN Verified"),
            rule("POST", "/api/v1/public/onboarding/session/selfie", "Onboarding Selfie Captured"),
            rule("POST", "/api/v1/public/onboarding/session/credentials", "KYC Onboarding Completed"),
            rule("POST", "/api/v1/public/onboarding/[^/]+/otp/verify", "Onboarding Mobile Verified"),
            rule("POST", "/api/v1/public/onboarding/[^/]+/otp", "Onboarding OTP Sent"),

            // Partner KYC Re-verification
            rule("POST", "/api/v1/reverify/otp/verify", "Re-verification Mobile Verified"),
            rule("POST", "/api/v1/reverify/otp", "Re-verification OTP Sent"),
            rule("POST", "/api/v1/reverify/location", "Re-verification Location Captured"),
            rule("POST", "/api/v1/reverify/digilocker/start", "Re-verification DigiLocker Started"),
            rule("POST", "/api/v1/reverify/digilocker/complete", "Re-verification Aadhaar Verified"),
            rule("POST", "/api/v1/reverify/pan", "Re-verification PAN Verified"),
            rule("POST", "/api/v1/reverify/selfie", "Re-verification Selfie Captured"),
            rule("POST", "/api/v1/reverify/pin", "KYC Re-verification Completed"),

            // Authorized Signatory KYC
            rule("POST", "/api/v1/public/signatory/session/location", "Signatory Location Captured"),
            rule("POST", "/api/v1/public/signatory/session/digilocker/start", "Signatory DigiLocker Started"),
            rule("POST", "/api/v1/public/signatory/session/digilocker/complete", "Signatory Aadhaar Verified"),
            rule("POST", "/api/v1/public/signatory/session/pan", "Signatory PAN Verified"),
            rule("POST", "/api/v1/public/signatory/session/selfie", "Signatory KYC Completed"),
            rule("POST", "/api/v1/public/signatory/[^/]+/otp/verify", "Signatory Mobile Verified"),
            rule("POST", "/api/v1/public/signatory/[^/]+/otp", "Signatory OTP Sent"),

            // Public Inquiries, Affiliates & eSign Mock
            rule("POST", "/api/v1/public/inquiries", "Partner Inquiry Submitted"),
            rule("POST", "/api/v1/public/apply/[^/]+/start", "Customer Application Started"),
            rule("POST", "/api/v1/public/vendors/[^/]+/affiliates", "Vendor Affiliate Registered"),
            rule("POST", "/api/v1/public/affiliates/[^/]+/generate-link", "Affiliate Link Generated"),
            rule("POST", "/api/v1/public/esign/mock/[^/]+/action", "Agreement eSign Completed"),

            // Self Profile & Security
            rule("PATCH", "/api/v1/me", "Profile Updated"),
            rule("POST", "/api/v1/me/password", "Password Changed"),
            rule("POST", "/api/v1/me/pin", "Security PIN Changed")
    );

    public static boolean isUserStatus(String method, String path) {
        String normalized = stripQuery(path);
        return ("PATCH".equals(method) && normalized.matches("/api/v1/admin/users/[^/]+/status"))
                || ("PATCH".equals(method) && normalized.matches("/api/v1/desk/outlets/[^/]+/status"));
    }

    private ActivityActions() {}

    public static String action(String method, String path) {
        String cleanMethod = method == null ? "POST" : method.trim().toUpperCase(Locale.ROOT);
        String normalized = normalizePath(path);
        for (Rule rule : RULES) {
            if (rule.method.equals(cleanMethod) && rule.path.matcher(normalized).matches()) {
                return rule.action;
            }
        }
        return humanizeFallback(cleanMethod, normalized);
    }

    public static String subjectType(String path) {
        String[] parts = stripQuery(path).split("/");
        for (int i = 0; i < parts.length; i++) {
            if (isUuid(parts[i]) && i > 0) {
                return parts[i - 1];
            }
        }
        return null;
    }

    public static String subjectId(String path) {
        for (String part : stripQuery(path).split("/")) {
            if (isUuid(part)) {
                return part;
            }
        }
        return null;
    }

    public static UUID uuidAfterSegment(String path, String segment) {
        if (path == null || segment == null) {
            return null;
        }
        String[] parts = stripQuery(path).split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (segment.equals(parts[i]) && isUuid(parts[i + 1])) {
                return UUID.fromString(parts[i + 1]);
            }
        }
        return null;
    }

    public static String segmentAfter(String path, String segment) {
        if (path == null || segment == null) {
            return null;
        }
        String[] parts = stripQuery(path).split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (segment.equals(parts[i]) && !parts[i + 1].isBlank()) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private static boolean isUuid(String value) {
        if (!UUID_PART.matcher(value).matches()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizePath(String path) {
        String stripped = stripQuery(path).trim();
        if (!stripped.startsWith("/")) {
            stripped = "/" + stripped;
        }
        return stripped;
    }

    private static String humanizeFallback(String method, String normalized) {
        List<String> words = new ArrayList<>();
        for (String rawSegment : normalized.split("/")) {
            if (rawSegment.isEmpty() || isUuid(rawSegment) || STRUCTURAL_SEGMENTS.contains(rawSegment.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String token : rawSegment.replace('_', '-').split("-")) {
                if (token.isBlank()) {
                    continue;
                }
                String lower = token.toLowerCase(Locale.ROOT);
                if (UPPERCASE_WORDS.contains(lower)) {
                    words.add(lower.equals("ekyc") ? "eKYC" : lower.toUpperCase(Locale.ROOT));
                } else {
                    words.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
                }
            }
        }
        if (words.isEmpty()) {
            return "Activity Recorded";
        }
        String phrase = String.join(" ", words);
        return switch (method) {
            case "DELETE" -> phrase + " Removed";
            case "PATCH", "PUT" -> phrase.endsWith("Updated") ? phrase : phrase + " Updated";
            default -> phrase;
        };
    }

    private static String stripQuery(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }
}
