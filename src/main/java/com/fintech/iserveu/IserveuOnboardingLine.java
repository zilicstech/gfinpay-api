package com.fintech.iserveu;

/** The one-line onboarding note iServeU sends for a retailer. */
public record IserveuOnboardingLine(String retailerCode, String accountNumber, String vpa) {

    /**
     * Splits {@code CODE -> note -> account -> vpa}. The note is ignored.
     * Returns null when the line does not contain an account and a VPA.
     */
    public static IserveuOnboardingLine parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("->");
        if (parts.length < 3) {
            return null;
        }
        String vpa = parts[parts.length - 1].trim();
        String account = parts[parts.length - 2].trim();
        String code = parts[0].trim();
        if (!vpa.contains("@") || !account.matches("[0-9]{6,30}")) {
            return null;
        }
        return new IserveuOnboardingLine(code, account, vpa);
    }
}
