package com.fintech.identity;

import java.util.List;

/** Authorities for each account type. The consoles are already split by user type. */
public final class AccessCatalog {

    private AccessCatalog() {}

    public static List<String> forUserType(String userType) {
        return switch (userType) {
            case "SUPER_ADMIN", "ADMIN" -> List.of(
                    "users.onboard",
                    "commissions.configure",
                    "reports.view",
                    "catalog.view",
                    "catalog.manage",
                    "sales.view",
                    "settings.manage",
                    "admins.manage",
                    "finance.review",
                    "inquiries.manage",
                    "banks.manage");
            case "MASTER_DISTRIBUTOR" -> List.of(
                    "reports.view",
                    "wallet.view",
                    "outlets.manage",
                    "catalog.view",
                    "sales.view");
            case "RETAILER" -> List.of(
                    "reports.view",
                    "dmt.initiate",
                    "senders.manage",
                    "wallet.view",
                    "bills.pay",
                    "catalog.view",
                    "sales.view",
                    "sales.create");
            case "HUBADMIN" -> List.of("users.onboard", "reports.view", "sales.view", "inquiries.manage");
            case "AUDITOR" -> List.of(
                    "finance.review",
                    "finance.wallet.adjust",
                    "reports.view",
                    "sales.view",
                    "banks.manage");
            default -> List.of();
        };
    }
}
