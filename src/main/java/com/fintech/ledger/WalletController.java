package com.fintech.ledger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.finance.FinanceService;
import com.fintech.platform.idempotency.ExternalRefs;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WalletController {

    public record TransferRequest(@NotNull @DecimalMin("0.01") BigDecimal amount) {}

    public record SettleRequest(BigDecimal amount, String remarks, UUID payoutAccountId) {}

    private final WalletService walletService;
    private final TransactionService transactionService;
    private final FinanceService finance;
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public WalletController(WalletService walletService, TransactionService transactionService,
                            FinanceService finance, JdbcTemplate jdbc,
                            IdempotencyService idempotency, ObjectMapper mapper) {
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.finance = finance;
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @PostMapping("/wallet/settle")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> settle(@AuthenticationPrincipal AuthPrincipal me,
                                                   @RequestBody SettleRequest body) {
        return ApiResponse.ok(finance.submitEarningsWithdrawal(me, body.amount(), body.remarks(), body.payoutAccountId()));
    }

    @PostMapping("/wallet/rewards/transfer")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, BigDecimal>> transferRewards(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest body) {
        String key = ExternalRefs.requireKey(idempotencyKey);
        IdempotencyService.Claim claim = idempotency.claim(me.userId(), key, body.amount().toPlainString());
        if (claim.replay()) {
            try {
                return ApiResponse.ok(mapper.readValue(claim.responseBody(), new TypeReference<>() {}));
            } catch (Exception e) {
                throw ApiException.of(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY", "Stored response could not be read");
            }
        }
        Map<String, BigDecimal> result = walletService.transferRewardsToMain(me.userId(), body.amount());
        try {
            idempotency.complete(claim.keyId(), 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "IDEMPOTENCY_STORE", "Could not store the transfer result");
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/wallet")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<Map<String, Object>> myWallet(@AuthenticationPrincipal AuthPrincipal me) {
        WalletService.WalletInfo w = walletService.getByUser(me.userId());
        WalletService.WalletInfo rewards = walletService.getRewards(me.userId());
        return ApiResponse.ok(Map.of(
                "walletId", w.walletId(),
                "availableBalance", w.available(),
                "holdBalance", w.hold(),
                "rewardsBalance", rewards.available()));
    }

    @GetMapping("/wallet/statement")
    @PreAuthorize("hasAuthority('wallet.view')")
    public ApiResponse<List<Map<String, Object>>> statement(@AuthenticationPrincipal AuthPrincipal me) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT le.id, le.transaction_id, w.wallet_kind AS account_type, le.direction::text AS direction, le.amount, le.narration, le.created_at
                  FROM ledger_entries le
                  JOIN wallets w ON w.id = le.wallet_id
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
