package com.fintech.audit;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Plain action names for known routes. Unknown mutating routes keep method and path. */
public final class ActivityActions {

    private static final Pattern UUID_PART = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private record Rule(String method, Pattern path, String action) {}

    private static Rule rule(String method, String regex, String action) {
        return new Rule(method, Pattern.compile(regex), action);
    }

    private static final List<Rule> RULES = List.of(
            rule("POST", "/api/v1/auth/login", ActivityPhrases.USER_LOGIN),
            rule("POST", "/api/v1/auth/logout", ActivityPhrases.USER_LOGOUT),

            rule("POST", "/api/v1/admin/hubs", "Hub Created"),
            rule("PATCH", "/api/v1/admin/hubs/[^/]+", "Hub Updated"),
            rule("POST", "/api/v1/admin/hubs/[^/]+/distributors/assign", "Distributor Assigned to Hub"),
            rule("POST", "/api/v1/admin/hubs/[^/]+/admins/assign", "Admin Assigned to Hub"),
            rule("PATCH", "/api/v1/admin/services/[^/]+", "Service Updated"),
            rule("POST", "/api/v1/admin/users/[^/]+/paysprint-onboard/status", "eKYC Status Checked"),
            rule("POST", "/api/v1/admin/users/[^/]+/paysprint-onboard/refresh", "New eKYC Link"),
            rule("POST", "/api/v1/admin/users/[^/]+/paysprint-onboard", "eKYC Link Created"),
            rule("POST", "/api/v1/admin/users/distributors", "Distributor Created"),
            rule("POST", "/api/v1/admin/users/retailers", "Retailer Created"),
            rule("POST", "/api/v1/admin/users/admins", "Admin Created"),
            rule("POST", "/api/v1/admin/users/auditors", "Auditor Created"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/status", "Status Change"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/hub", "Hub Changed"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/distributor", "Distributor Changed"),
            rule("POST", "/api/v1/admin/users/[^/]+/password", "Password Reset"),
            rule("PATCH", "/api/v1/admin/users/[^/]+/wallet/freeze", "Wallet Freeze Changed"),
            rule("POST", "/api/v1/admin/users/[^/]+/wallet/adjust", "Wallet Adjusted"),
            rule("POST", "/api/v1/admin/kyc/[^/]+/review", "KYC Reviewed"),
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

            rule("POST", "/api/v1/desk/outlets/[^/]+/paysprint-onboard/status", "eKYC Status Checked"),
            rule("POST", "/api/v1/desk/outlets/[^/]+/paysprint-onboard/refresh", "New eKYC Link"),
            rule("POST", "/api/v1/desk/outlets/[^/]+/paysprint-onboard", "eKYC Link Created"),
            rule("POST", "/api/v1/desk/outlets", "Retailer Created"),
            rule("PATCH", "/api/v1/desk/outlets/[^/]+/status", "Retailer Status Change"),
            rule("POST", "/api/v1/desk/outlets/[^/]+/password", "Retailer Password Reset"),
            rule("PATCH", "/api/v1/desk/outlets/[^/]+/wallet/freeze", "Retailer Wallet Freeze Changed"),
            rule("POST", "/api/v1/desk/outlets/[^/]+/wallet/adjust", "Retailer Wallet Adjusted"),
            rule("POST", "/api/v1/desk/inquiries/[^/]+/convert-retailer", "Inquiry Converted to Retailer"),

            rule("POST", "/api/v1/finance/activation-requests", "Activation Payment Submitted"),
            rule("POST", "/api/v1/finance/wallet-requests", "Wallet Request Submitted"),
            rule("POST", "/api/v1/finance/requests/[^/]+/receive", "Payment Received"),
            rule("POST", "/api/v1/finance/requests/[^/]+/transfer", "Withdrawal Transferred"),
            rule("POST", "/api/v1/finance/requests/[^/]+/reject", "Request Rejected"),
            rule("POST", "/api/v1/finance/retailers/[^/]+/activate", "Retailer Activated"),
            rule("POST", "/api/v1/auditor/retailers/[^/]+/wallet/adjust", "Retailer Wallet Adjusted"),

            rule("POST", "/api/v1/bills/fetch", "Bill Fetched"),
            rule("POST", "/api/v1/bills/pay", "Bill Paid"),
            rule("POST", "/api/v1/dmt/senders/[^/]+/otp/verify", "Money Transfer OTP Verified"),
            rule("POST", "/api/v1/dmt/senders/[^/]+/otp", "Money Transfer OTP Sent"),
            rule("PATCH", "/api/v1/dmt/senders/[^/]+/kyc", "Sender KYC Updated"),
            rule("POST", "/api/v1/dmt/senders/[^/]+/beneficiaries", "Beneficiary Added"),
            rule("POST", "/api/v1/dmt/senders", "Sender Added"),
            rule("POST", "/api/v1/dmt/transactions", "Money Sent"),
            rule("POST", "/api/v1/wallet/banks/[^/]+/active", "Bank Status Changed"),
            rule("POST", "/api/v1/wallet/banks/[^/]+", "Bank Updated"),
            rule("POST", "/api/v1/wallet/banks", "Bank Added"),
            rule("POST", "/api/v1/wallet/settle", "Wallet Request Submitted"),
            rule("POST", "/api/v1/customers/[^/]+/eligibility", "Customer Eligibility Checked"),
            rule("POST", "/api/v1/customers/[^/]+/leads", "Sales Lead Created"),
            rule("POST", "/api/v1/customers", "Customer Added"),
            rule("PATCH", "/api/v1/customers/[^/]+", "Customer Updated"),
            rule("POST", "/api/v1/sales/[^/]+/utm-status", "Sales Lead Status Checked"),
            rule("POST", "/api/v1/cashout/upi/[^/]+/paid", "Cash Out Paid"),
            rule("POST", "/api/v1/cashout/upi/[^/]+/dispense", "Cash Given"),
            rule("POST", "/api/v1/cashout/upi", "Cash Out Started"),
            rule("PATCH", "/api/v1/me", "Profile Updated"),
            rule("POST", "/api/v1/me/password", "Password Changed")
    );

    public static boolean isUserStatus(String method, String path) {
        String normalized = stripQuery(path);
        return ("PATCH".equals(method) && normalized.matches("/api/v1/admin/users/[^/]+/status"))
                || ("PATCH".equals(method) && normalized.matches("/api/v1/desk/outlets/[^/]+/status"));
    }

    private ActivityActions() {}

    public static String action(String method, String path) {
        String normalized = stripQuery(path);
        for (Rule rule : RULES) {
            if (rule.method.equals(method) && rule.path.matcher(normalized).matches()) {
                return rule.action;
            }
        }
        return method + " " + template(normalized);
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

    private static String template(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(isUuid(part) ? "{id}" : part);
        }
        return out.toString();
    }

    private static String stripQuery(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }
}
