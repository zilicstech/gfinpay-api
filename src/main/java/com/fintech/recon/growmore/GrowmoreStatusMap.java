package com.fintech.recon.growmore;

import com.fintech.recon.FdPartnerLifecycle;
import java.util.Locale;
import java.util.Map;

/** Display status + current sub status, from the GrowMore SBM status workbook. */
final class GrowmoreStatusMap {

    private static final Map<String, String> REMARKS = Map.ofEntries(
            pair("FD Payment", "FD Payment failed. Customer to retry", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Phone Verification Done", "Incomplete Stage - FD Payment not done"),
            pair("KYC Stage - PAN", "PAN Verification Done", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Mobile Number to be Confirmed", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Mobile number OTP Incorrect. Customer to retry", "Incomplete Stage - FD Payment not done"),
            pair("Card Setup", "Card issued successfully", "Final Conversion Done"),
            pair("KYC Stage - PAN", "PAN Verification Error", "Incomplete Stage - FD Payment not done"),
            pair("FD Payment", "FD payment pending", "Incomplete Stage - FD Payment not done"),
            pair("KYC Stage - Aadhaar", "Aadhaar validation expired. Customer to retry", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Pay Visa Outstanding", "Incomplete Stage - FD Payment not done"),
            pair("FD Payment", "FD payment refund initiated", "Incomplete Stage - FD Payment not done"),
            pair("Application Rejected", "FD payment refund completed", "FD Payment Done - Refund Initiated"),
            pair("KYC Stage - Aadhaar", "Aadhaar Verification Pending", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Mobile Number Confirmed", "Incomplete Stage - FD Payment not done"),
            pair("Application Not Started", "Enter Email", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Phone Mismatch with bank records", "Incomplete Stage - FD Payment not done"),
            pair("Personal Details", "Customer Application Done", "FD Payment Done - Vkyc Pending"),
            pair("Application Incomplete", "Aadhaar Verification Failure. Customer To Retry", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Visa Migration To Start", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Email Mismatch with bank records", "Incomplete Stage - FD Payment not done"),
            pair("KYC Stage - PAN", "PAN Input Done", "Incomplete Stage - FD Payment not done"),
            pair("Application Not Started", "CARD_SELECTED", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Mobile number OTP expired. Customer to retry", "Incomplete Stage - FD Payment not done"),
            pair("Video KYC", "vKYC Call completed", "FD Payment Done - Vkyc Pending"),
            pair("Personal Details", "Customer to provide information", "FD Payment Done - Vkyc Pending"),
            pair("Application Rejected", "PAN not permitted", "Incomplete Stage - FD Payment not done"),
            pair("Card Setup", "Failed to modify customer", "Final Conversion Done"),
            pair("Application Rejected", "Bank policy", "Application Rejected"),
            pair("Video KYC", "vKYC incomplete. Customer to retry", "FD Payment Done - Vkyc Pending"),
            pair("Video KYC", "vKYC Pending", "FD Payment Done - Vkyc Pending"),
            pair("Video KYC", "vKYC incomplete", "FD Payment Done - Vkyc Pending"),
            pair("Registration - Email", "Email OTP incorrect, Customer to retry", "FD Payment Done - Application details pending"),
            pair("Application Incomplete", "Aadhaar Name Verified", "Incomplete Stage - FD Payment not done"),
            pair("Registration", "Phone and Email Mismatch with Bank records", "Incomplete Stage - FD Payment not done"),
            pair("Application Incomplete", "Mobile Number OTP Verified, Email Verification OTP Requested", "Incomplete Stage - FD Payment not done"),
            pair("Card Setup", "In progress at bank", "Final Conversion Done"),
            pair("FD Payment", "FD Payment Done", "FD Payment Done - Application details pending"),
            pair("Registration - Email", "Email Verification Done", "Incomplete Stage - FD Payment not done"));

    private GrowmoreStatusMap() {}

    static FdPartnerLifecycle lifecycle(String displayStatus, String subStatus) {
        String remark = REMARKS.get(key(displayStatus, subStatus));
        if (remark == null) {
            return FdPartnerLifecycle.NONE;
        }
        return switch (remark) {
            case "Final Conversion Done" -> FdPartnerLifecycle.ACTIVATED;
            case "Application Rejected", "FD Payment Done - Refund Initiated" -> FdPartnerLifecycle.FAILED;
            case "Incomplete Stage - FD Payment not done",
                    "FD Payment Done - Vkyc Pending",
                    "FD Payment Done - Application details pending" -> FdPartnerLifecycle.IN_PROGRESS;
            default -> FdPartnerLifecycle.NONE;
        };
    }

    static String partnerStatus(String displayStatus) {
        if (displayStatus == null || displayStatus.isBlank()) {
            return null;
        }
        String code = displayStatus.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return code.replaceAll("^_|_$", "");
    }

    private static Map.Entry<String, String> pair(String display, String sub, String remark) {
        return Map.entry(key(display, sub), remark);
    }

    private static String key(String display, String sub) {
        return norm(display) + "\n" + norm(sub);
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
