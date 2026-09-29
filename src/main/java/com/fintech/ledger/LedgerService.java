package com.fintech.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Double-entry postings. Every group must balance (Σ debits = Σ credits) — validated here
 * and enforced again by the deferred DB constraint trigger at commit.
 */
@Service
public class LedgerService {

    /** Platform-level account ids (seeded in V2). */
    public static final UUID PLATFORM_SETTLEMENT = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    public static final UUID PG_RECEIVABLE = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    public static final UUID FEE_INCOME = UUID.fromString("b0000000-0000-0000-0000-000000000003");

    public record Entry(UUID accountId, String direction, BigDecimal amount, String narration) {
        public static Entry debit(UUID accountId, BigDecimal amount, String narration) {
            return new Entry(accountId, "DEBIT", amount, narration);
        }
        public static Entry credit(UUID accountId, BigDecimal amount, String narration) {
            return new Entry(accountId, "CREDIT", amount, narration);
        }
    }

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Posts a balanced group of entries for a transaction. Returns the posting group id. */
    public UUID postGroup(UUID transactionId, List<Entry> entries) {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Entry e : entries) {
            if ("DEBIT".equals(e.direction())) {
                debits = debits.add(e.amount());
            } else {
                credits = credits.add(e.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalStateException("Unbalanced posting group: debits=" + debits + " credits=" + credits);
        }
        UUID group = UUID.randomUUID();
        for (Entry e : entries) {
            jdbc.update("""
                    INSERT INTO ledger_entries (transaction_id, posting_group, account_id, direction, amount, narration)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, transactionId, group, e.accountId(), e.direction(), e.amount(), e.narration());
        }
        return group;
    }
}
