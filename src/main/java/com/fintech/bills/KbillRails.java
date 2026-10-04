package com.fintech.bills;

public final class KbillRails {

    public static final int GAS = 2;
    public static final int CREDIT_CARD = 7;
    public static final int ELECTRICITY = 8;
    public static final int FASTAG = 22;

    private KbillRails() {}

    public static boolean locationRequired(int categoryId) {
        return categoryId == ELECTRICITY;
    }

    public static String payPath(int categoryId) {
        return switch (categoryId) {
            case ELECTRICITY -> "/bill-pay-electricty";
            case GAS -> "/bill-pay-gas";
            case CREDIT_CARD -> "/bill-pay-credit-card";
            case FASTAG -> "/bill-pay-fastag";
            default -> null;
        };
    }

    public static boolean payEnabled(int categoryId) {
        return payPath(categoryId) != null;
    }
}
