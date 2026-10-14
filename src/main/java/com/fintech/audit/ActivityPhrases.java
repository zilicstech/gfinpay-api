package com.fintech.audit;

import java.util.Locale;
import java.util.Map;

/** Plain-language names stored on new activity rows, and used to read older code names. */
public final class ActivityPhrases {

    public static final String USER_LOGIN = "User Login";
    public static final String USER_LOGOUT = "User Logout";

    private static final Map<String, String> LEGACY = Map.ofEntries(
            Map.entry("auth.login", USER_LOGIN),
            Map.entry("auth.logout", USER_LOGOUT),
            Map.entry("admin.hub.create", "Hub Created"),
            Map.entry("admin.hub.update", "Hub Updated"),
            Map.entry("admin.hub.assign-distributor", "Distributor Assigned to Hub"),
            Map.entry("admin.hub.assign-admin", "Admin Assigned to Hub"),
            Map.entry("admin.service.update", "Service Updated"),
            Map.entry("admin.ekyc.status", "eKYC Status Checked"),
            Map.entry("admin.ekyc.refresh", "New eKYC Link"),
            Map.entry("admin.ekyc.issue", "eKYC Link Created"),
            Map.entry("admin.distributor.create", "Distributor Created"),
            Map.entry("admin.retailer.create", "Retailer Created"),
            Map.entry("admin.admin.create", "Admin Created"),
            Map.entry("admin.auditor.create", "Auditor Created"),
            Map.entry("admin.user.hub", "Hub Changed"),
            Map.entry("admin.user.distributor", "Distributor Changed"),
            Map.entry("admin.user.password", "Password Reset"),
            Map.entry("admin.user.wallet-freeze", "Wallet Freeze Changed"),
            Map.entry("admin.user.wallet-adjust", "Wallet Adjusted"),
            Map.entry("admin.kyc.review", "KYC Reviewed"),
            Map.entry("admin.recon.upload", "Recon Report Uploaded"),
            Map.entry("admin.recon.resolve", "Recon Issue Resolved"),
            Map.entry("admin.recon.create", "Recon Started"),
            Map.entry("admin.recon.delete", "Recon Removed"),
            Map.entry("admin.bank.active", "Bank Status Changed"),
            Map.entry("admin.bank.update", "Bank Updated"),
            Map.entry("admin.bank.create", "Bank Added"),
            Map.entry("admin.vendor.create", "Vendor Added"),
            Map.entry("admin.vendor.status", "Vendor Status Changed"),
            Map.entry("admin.catalog.update", "Catalog Updated"),
            Map.entry("admin.inquiry.retire", "Inquiry Closed"),
            Map.entry("admin.inquiry.not-interested", "Inquiry Marked Not Interested"),
            Map.entry("admin.inquiry.assign-hub", "Inquiry Assigned to Hub"),
            Map.entry("admin.inquiry.assign-distributor", "Inquiry Assigned to Distributor"),
            Map.entry("admin.inquiry.convert-distributor", "Inquiry Converted to Distributor"),
            Map.entry("admin.fd.provider-config", "FD Provider Settings Updated"),
            Map.entry("admin.fd.provider", "FD Provider Updated"),
            Map.entry("admin.fd.card", "FD Card Updated"),
            Map.entry("admin.fd.rule-create", "FD Rule Added"),
            Map.entry("admin.fd.rule-update", "FD Rule Updated"),
            Map.entry("admin.fd.rule-delete", "FD Rule Removed"),
            Map.entry("admin.fd.budget-bands", "FD Budget Updated"),
            Map.entry("admin.fd.tier-create", "FD Commission Added"),
            Map.entry("admin.fd.tier-update", "FD Commission Updated"),
            Map.entry("admin.fd.tier-delete", "FD Commission Removed"),
            Map.entry("admin.fd.commission", "FD Commission Updated"),
            Map.entry("distributor.ekyc.status", "eKYC Status Checked"),
            Map.entry("distributor.ekyc.refresh", "New eKYC Link"),
            Map.entry("distributor.ekyc.issue", "eKYC Link Created"),
            Map.entry("distributor.retailer.create", "Retailer Created"),
            Map.entry("distributor.retailer.status", "Retailer Status Change"),
            Map.entry("distributor.retailer.password", "Retailer Password Reset"),
            Map.entry("distributor.retailer.wallet-freeze", "Retailer Wallet Freeze Changed"),
            Map.entry("distributor.retailer.wallet-adjust", "Retailer Wallet Adjusted"),
            Map.entry("distributor.inquiry.convert", "Inquiry Converted to Retailer"),
            Map.entry("finance.activation.submit", "Activation Payment Submitted"),
            Map.entry("finance.wallet.submit", "Wallet Request Submitted"),
            Map.entry("finance.request.receive", "Payment Received"),
            Map.entry("finance.request.transfer", "Withdrawal Transferred"),
            Map.entry("finance.request.reject", "Request Rejected"),
            Map.entry("finance.retailer.activate", "Retailer Activated"),
            Map.entry("auditor.wallet.adjust", "Retailer Wallet Adjusted"),
            Map.entry("retailer.bill.fetch", "Bill Fetched"),
            Map.entry("retailer.bill.pay", "Bill Paid"),
            Map.entry("retailer.dmt.otp-verify", "Money Transfer OTP Verified"),
            Map.entry("retailer.dmt.otp", "Money Transfer OTP Sent"),
            Map.entry("retailer.dmt.sender-kyc", "Sender KYC Updated"),
            Map.entry("retailer.dmt.beneficiary", "Beneficiary Added"),
            Map.entry("retailer.dmt.sender-create", "Sender Added"),
            Map.entry("retailer.dmt.transfer", "Money Sent"),
            Map.entry("retailer.bank.active", "Bank Status Changed"),
            Map.entry("retailer.bank.update", "Bank Updated"),
            Map.entry("retailer.bank.create", "Bank Added"),
            Map.entry("retailer.wallet.settle", "Wallet Request Submitted"),
            Map.entry("retailer.customer.eligibility", "Customer Eligibility Checked"),
            Map.entry("retailer.lead.create", "Sales Lead Created"),
            Map.entry("retailer.customer.create", "Customer Added"),
            Map.entry("retailer.customer.update", "Customer Updated"),
            Map.entry("retailer.lead.status", "Sales Lead Status Checked"),
            Map.entry("retailer.cashout.paid", "Cash Out Paid"),
            Map.entry("retailer.cashout.dispense", "Cash Given"),
            Map.entry("retailer.cashout.start", "Cash Out Started"),
            Map.entry("profile.update", "Profile Updated"),
            Map.entry("profile.password", "Password Changed")
    );

    private ActivityPhrases() {}

    public static String display(String stored, String subjectUserType) {
        if (stored == null || stored.isBlank()) {
            return "Activity";
        }
        if ("admin.user.status".equals(stored) || "Status Change".equals(stored)) {
            return statusChange(subjectUserType);
        }
        return LEGACY.getOrDefault(stored, stored);
    }

    public static String statusChange(String userType) {
        return switch (userType == null ? "" : userType) {
            case "RETAILER" -> "Retailer Status Change";
            case "MASTER_DISTRIBUTOR" -> "Distributor Status Change";
            case "HUBADMIN" -> "Hub Admin Status Change";
            case "ADMIN" -> "Admin Status Change";
            case "AUDITOR" -> "Auditor Status Change";
            default -> "Status Change";
        };
    }

    public static String status(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "Active";
            case "INACTIVE" -> "Inactive";
            case "SUSPENDED" -> "Suspended";
            case "TERMINATED" -> "Closed";
            case "PENDING" -> "Pending";
            case "SUBMITTED" -> "Submitted";
            case "APPROVED" -> "Approved";
            case "REJECTED" -> "Rejected";
            case "PENDING_KYC" -> "KYC pending";
            case "PENDING_ACTIVATION" -> "Activation pending";
            default -> raw.trim();
        };
    }

    public static String role(String userType) {
        if (userType == null || userType.isBlank()) {
            return "";
        }
        return switch (userType) {
            case "SUPER_ADMIN" -> "Super Admin";
            case "ADMIN" -> "Admin";
            case "HUBADMIN" -> "Hub Admin";
            case "MASTER_DISTRIBUTOR" -> "Distributor";
            case "RETAILER" -> "Retailer";
            case "AUDITOR" -> "Auditor";
            default -> userType;
        };
    }

    public static boolean isLogin(String action) {
        return USER_LOGIN.equals(action) || "auth.login".equals(action);
    }
}
