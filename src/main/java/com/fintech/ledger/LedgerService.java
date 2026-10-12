package com.fintech.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Append-only wallet movements. A line debits or credits one wallet. */
@Service
public class LedgerService {

    public record Entry(UUID walletId, String direction, BigDecimal amount, String narration) {
        public static Entry debit(UUID walletId, BigDecimal amount, String narration) {
            return new Entry(walletId, "DEBIT", amount, narration);
        }

        public static Entry credit(UUID walletId, BigDecimal amount, String narration) {
            return new Entry(walletId, "CREDIT", amount, narration);
        }
    }

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID post(UUID transactionId, UUID walletId, String direction, BigDecimal amount, String narration) {
        return postGroup(transactionId, List.of(new Entry(walletId, direction, amount, narration)));
    }

    /** Writes one posting group. Returns the group id stored on each line. */
    public UUID postGroup(UUID transactionId, List<Entry> entries) {
        UUID group = UUID.randomUUID();
        jdbc.batchUpdate("""
                INSERT INTO ledger_entries (transaction_id, posting_group, wallet_id, direction, amount, narration)
                VALUES (?, ?, ?, ?, ?, ?)
                """, entries.stream()
                .map(e -> new Object[]{transactionId, group, e.walletId(), e.direction(), e.amount(), e.narration()})
                .toList());
        return group;
    }
}
