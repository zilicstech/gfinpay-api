package com.fintech.ledger;

import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WalletService {

    public record WalletInfo(UUID walletId, UUID availableAccountId, UUID holdAccountId,
                             BigDecimal available, BigDecimal hold) {}

    private final JdbcTemplate jdbc;

    public WalletService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the wallet plus its AVAILABLE and HOLD ledger accounts for a new user. */
    public UUID createForUser(UUID userId) {
        UUID walletId = UUID.randomUUID();
        jdbc.update("INSERT INTO wallets (id, user_id) VALUES (?, ?)", walletId, userId);
        jdbc.update("INSERT INTO ledger_accounts (id, account_type, wallet_id) VALUES (?, 'WALLET_AVAILABLE', ?)",
                UUID.randomUUID(), walletId);
        jdbc.update("INSERT INTO ledger_accounts (id, account_type, wallet_id) VALUES (?, 'WALLET_HOLD', ?)",
                UUID.randomUUID(), walletId);
        return walletId;
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
        return fetchBy("w.user_id", userId, forUpdate);
    }

    private WalletInfo fetchBy(String column, UUID value, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE OF w" : "";
        var rows = jdbc.queryForList(
                "SELECT w.id AS wallet_id, w.available_balance, w.hold_balance, "
                        + "a.id AS avail_id, h.id AS hold_id "
                        + "FROM wallets w "
                        + "JOIN ledger_accounts a ON a.wallet_id = w.id AND a.account_type = 'WALLET_AVAILABLE' "
                        + "JOIN ledger_accounts h ON h.wallet_id = w.id AND h.account_type = 'WALLET_HOLD' "
                        + "WHERE " + column + " = ?" + lock,
                value);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "No wallet exists for this user");
        }
        Map<String, Object> r = rows.get(0);
        return new WalletInfo((UUID) r.get("wallet_id"), (UUID) r.get("avail_id"), (UUID) r.get("hold_id"),
                (BigDecimal) r.get("available_balance"), (BigDecimal) r.get("hold_balance"));
    }

    /** Applies deltas to the cached balances (ledger entries remain the source of truth). */
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
