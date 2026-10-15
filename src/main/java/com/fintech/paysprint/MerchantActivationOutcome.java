package com.fintech.paysprint;

/** PaySprint activate_merchant response codes, in words a retailer can read. */
public record MerchantActivationOutcome(String status, String message, boolean activated) {

    public static MerchantActivationOutcome from(String responseCode) {
        String code = responseCode == null ? "" : responseCode.trim();
        return switch (code) {
            case "1" -> new MerchantActivationOutcome("SUCCESS", "Merchant eKYC is complete.", true);
            case "2" -> new MerchantActivationOutcome("FAILED",
                    "The fingerprint was not accepted. Please scan again.", false);
            case "3" -> new MerchantActivationOutcome("IN PROGRESS",
                    "The bank is still checking your details. Try again after some time.", false);
            case "4" -> new MerchantActivationOutcome("FAILED", "The bank did not approve this eKYC.", false);
            case "5" -> new MerchantActivationOutcome("FAILED", "Finish your eKYC link first.", false);
            case "6" -> new MerchantActivationOutcome("FAILED", "The bank could not find this merchant.", false);
            case "7" -> new MerchantActivationOutcome("FAILED",
                    "This mobile number is not allowed for this service.", false);
            case "8" -> new MerchantActivationOutcome("FAILED",
                    "Some details were not accepted. Check them and try again.", false);
            case "9" -> new MerchantActivationOutcome("FAILED", "This service is not active on the account.", false);
            case "10" -> new MerchantActivationOutcome("FAILED",
                    "The bank could not process the request. Try again.", false);
            case "11" -> new MerchantActivationOutcome("FAILED",
                    "The bank rejected the sign-in. Try again later.", false);
            case "12" -> new MerchantActivationOutcome("FAILED",
                    "The bank could not confirm the account.", false);
            case "0" -> new MerchantActivationOutcome("FAILED", "Something went wrong. Try again later.", false);
            default -> new MerchantActivationOutcome("FAILED", "The bank could not complete eKYC. Try again.", false);
        };
    }
}
