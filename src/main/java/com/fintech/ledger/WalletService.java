package com.fintech.ledger;

import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    public record WalletInfo(UUID walletId, BigDecimal available, BigDecimal hold, String status, boolean active) {}

    private final JdbcTemplate jdbc;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;

    public WalletService(JdbcTemplate jdbc, LedgerService ledgerService, TransactionService transactionService) {
        this.jdbc = jdbc;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
    }

    /** Creates the single wallet row for a new user. */
    public UUID createForUser(UUID userId) {
        UUID walletId = UUID.randomUUID();
        jdbc.update("INSERT INTO wallets (id, user_id, wallet_kind) VALUES (?, ?, 'MAIN')", walletId, userId);
        return walletId;
    }

    /** Rewards are paid to retailers, distributors, and hub admins. Created on first use. */
    public UUID ensureRewards(UUID userId) {
        List<UUID> existing = jdbc.query("""
                SELECT id FROM wallets WHERE user_id = ? AND wallet_kind = 'REWARDS'
                """, (rs, row) -> rs.getObject("id", UUID.class), userId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID walletId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO wallets (id, user_id, wallet_kind) VALUES (?, ?, 'REWARDS')", walletId, userId);
            return walletId;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return jdbc.queryForObject(
                    "SELECT id FROM wallets WHERE user_id = ? AND wallet_kind = 'REWARDS'", UUID.class, userId);
        }
    }

    public WalletInfo getRewards(UUID userId) {
        ensureRewards(userId);
        return fetchRewards(userId, false);
    }

    public WalletInfo lockRewards(UUID userId) {
        ensureRewards(userId);
        return fetchRewards(userId, true);
    }

    /** Moves rewards into the operating wallet so they can be used for services. */
    @Transactional
    public Map<String, BigDecimal> transferRewardsToMain(UUID userId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Amount must be positive");
        }
        WalletInfo rewards = lockRewards(userId);
        if (rewards.available().compareTo(amount) < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                    "Rewards balance is less than the transfer");
        }
        WalletInfo main = lockByUser(userId);
        UUID txnId = transactionService.create("REWARDS_TRANSFER", userId, main.walletId(), amount, BigDecimal.ZERO,
                null, null, "REWARDS", null);
        ledgerService.post(txnId, rewards.walletId(), "DEBIT", amount, "Rewards moved to wallet");
        ledgerService.post(txnId, main.walletId(), "CREDIT", amount, "Rewards moved to wallet");
        adjustCache(rewards.walletId(), amount.negate(), BigDecimal.ZERO);
        adjustCache(main.walletId(), amount, BigDecimal.ZERO);
        transactionService.transition(txnId, "SUCCESS", null, null);
        WalletInfo nextRewards = getRewards(userId);
        WalletInfo nextMain = getByUser(userId);
        return Map.of("availableBalance", nextMain.available(), "rewardsBalance", nextRewards.available());
    }

    public WalletInfo getByUser(UUID userId) {
        return fetch(userId, false);
    }

    /** Row-locks the wallet for balance-affecting operations. */
    public WalletInfo lockByUser(UUID userId) {
        return fetch(userId, true);
    }

    /** Row-locks a wallet by its id (used by webhook finalization, which starts from the txn row). */
    public WalletInfo lockByWalletId(UUID walletId) {
        return fetchBy("w.id", walletId, true);
    }

    private WalletInfo fetch(UUID userId, boolean forUpdate) {
        return fetchKind(userId, "MAIN", forUpdate);
    }

    private WalletInfo fetchRewards(UUID userId, boolean forUpdate) {
        return fetchKind(userId, "REWARDS", forUpdate);
    }

    private WalletInfo fetchKind(UUID userId, String kind, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE OF w" : "";
        var rows = jdbc.queryForList("""
                SELECT w.id AS wallet_id, w.available_balance, w.hold_balance, w.status, w.is_active
                  FROM wallets w
                 WHERE w.user_id = ? AND w.wallet_kind = ?
                """ + lock, userId, kind);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "No wallet exists for this user");
        }
        return toInfo(rows.get(0));
    }

    private WalletInfo fetchBy(String column, UUID value, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE OF w" : "";
        var rows = jdbc.queryForList(
                "SELECT w.id AS wallet_id, w.available_balance, w.hold_balance, w.status, w.is_active "
                        + "FROM wallets w WHERE " + column + " = ?" + lock,
                value);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "No wallet exists for this user");
        }
        return toInfo(rows.get(0));
    }

    private static WalletInfo toInfo(Map<String, Object> r) {
        return new WalletInfo((UUID) r.get("wallet_id"),
                (BigDecimal) r.get("available_balance"), (BigDecimal) r.get("hold_balance"),
                String.valueOf(r.get("status")), Boolean.TRUE.equals(r.get("is_active")));
    }

    /** Applies deltas to the cached balances. */
    public void adjustCache(UUID walletId, BigDecimal availableDelta, BigDecimal holdDelta) {
        int updated = jdbc.update("""
                UPDATE wallets
                   SET available_balance = available_balance + ?,
                       hold_balance = hold_balance + ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ?
                """, availableDelta, holdDelta, walletId);
        if (updated != 1) {
            throw new IllegalStateException("Wallet cache update failed for " + walletId);
        }
    }
}
