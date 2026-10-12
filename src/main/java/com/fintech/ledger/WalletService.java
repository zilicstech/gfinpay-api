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

    public record WalletInfo(UUID walletId, BigDecimal available, BigDecimal hold, String status, boolean active) {}

    private final JdbcTemplate jdbc;

    public WalletService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the single wallet row for a new user. */
    public UUID createForUser(UUID userId) {
        UUID walletId = UUID.randomUUID();
        jdbc.update("INSERT INTO wallets (id, user_id) VALUES (?, ?)", walletId, userId);
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
                "SELECT w.id AS wallet_id, w.available_balance, w.hold_balance, w.status, w.is_active "
                        + "FROM wallets w WHERE " + column + " = ?" + lock,
                value);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "No wallet exists for this user");
        }
        Map<String, Object> r = rows.get(0);
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
