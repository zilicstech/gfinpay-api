package com.fintech.ledger;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WalletController {

    private final WalletService walletService;
    private final TransactionService transactionService;
    private final JdbcTemplate jdbc;

    public WalletController(WalletService walletService, TransactionService transactionService, JdbcTemplate jdbc) {
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.jdbc = jdbc;
    }

    @GetMapping("/wallet")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> myWallet(@AuthenticationPrincipal AuthPrincipal me) {
        WalletService.WalletInfo w = walletService.getByUser(me.userId());
        return ApiResponse.ok(Map.of(
                "walletId", w.walletId(),
                "availableBalance", w.available(),
                "holdBalance", w.hold()));
    }

    @GetMapping("/wallet/statement")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> statement(@AuthenticationPrincipal AuthPrincipal me) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT le.id, le.transaction_id, la.account_type::text AS account_type, le.direction::text AS direction, le.amount, le.narration, le.created_at
                  FROM ledger_entries le
                  JOIN ledger_accounts la ON la.id = le.account_id
                  JOIN wallets w ON w.id = la.wallet_id
                 WHERE w.user_id = ?
                 ORDER BY le.id DESC
                 LIMIT 50
                """, me.userId());
        return ApiResponse.ok(rows);
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> myTransactions(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(transactionService.listByAgent(me.userId(), 50));
    }

    @GetMapping("/transactions/{id}")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> byId(@AuthenticationPrincipal AuthPrincipal me,
                                                 @PathVariable UUID id) {
        Map<String, Object> txn = transactionService.getById(id);
        return ApiResponse.ok(txn);
    }
}
