package com.fintech.identity;

import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Named views for admin transaction search. Each group maps to one or more ledger txn_type values. */
public final class AdminTxnTypes {

    public record Group(String code, String label, List<String> txnTypes) {}

    private static final List<Group> GROUPS = List.of(
            new Group("WALLET", "Wallet Transactions",
                    List.of("WALLET_TOPUP", "ADJUSTMENT", "COMMISSION_PAYOUT", "REVERSAL",
                            "FD_CARD_FEE", "FD_ZET", "FD_PAYSPRINT", "FD_GROWMORE")),
            new Group("DMT", "DMT Transactions", List.of("DMT")),
            new Group("CASHOUT_UPI", "UPI to Cash transactions", List.of("CASHOUT_UPI")),
            new Group("CASHOUT_AEPS", "Aadhaar cash transactions", List.of("CASHOUT_AEPS")),
            new Group("BBPS", "Bill pay transactions", List.of("BBPS", "RECHARGE", "DTH", "FASTAG", "LIC")));

    private static final Map<String, Group> BY_CODE;

    static {
        Map<String, Group> map = new LinkedHashMap<>();
        for (Group group : GROUPS) {
            map.put(group.code(), group);
        }
        BY_CODE = Map.copyOf(map);
    }

    private AdminTxnTypes() {}

    public static List<Map<String, String>> catalog() {
        return GROUPS.stream()
                .map(g -> Map.of("type", g.code(), "label", g.label()))
                .toList();
    }

    public static Group require(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TXN_TYPE",
                    "Select a transaction type");
        }
        Group group = BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
        if (group == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TXN_TYPE",
                    "Unknown transaction type");
        }
        return group;
    }
}
