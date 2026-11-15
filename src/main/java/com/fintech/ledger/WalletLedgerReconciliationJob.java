package com.fintech.ledger;

import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Logs wallets whose cache does not match the ledger. It never changes a balance.
 * For a main wallet, available + hold equals net ledger plus open payout holds,
 * because a payout hold posts a debit and also increases hold.
 */
@Component
public class WalletLedgerReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(WalletLedgerReconciliationJob.class);

    private final JdbcTemplate jdbc;

    public WalletLedgerReconciliationJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "walletLedgerReconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void check() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT w.id, w.user_id, w.wallet_kind, w.available_balance, w.hold_balance,
                       COALESCE((
                           SELECT SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END)
                             FROM ledger_entries le
                            WHERE le.wallet_id = w.id
                       ), 0) AS net_ledger,
                       CASE WHEN w.wallet_kind = 'MAIN' THEN COALESCE((
                           SELECT SUM(p.amount + p.fee)
                             FROM payout_transfers p
                            WHERE p.user_id = w.user_id
                              AND p.status IN ('HOLD', 'PENDING')
                       ), 0) ELSE 0 END AS open_holds
                  FROM wallets w
                """);
        int mismatches = 0;
        for (Map<String, Object> row : rows) {
            java.math.BigDecimal available = (java.math.BigDecimal) row.get("available_balance");
            java.math.BigDecimal hold = (java.math.BigDecimal) row.get("hold_balance");
            java.math.BigDecimal net = (java.math.BigDecimal) row.get("net_ledger");
            java.math.BigDecimal open = (java.math.BigDecimal) row.get("open_holds");
            java.math.BigDecimal expected = net.add(open);
            if (available.signum() < 0 || hold.signum() < 0 || available.add(hold).compareTo(expected) != 0) {
                mismatches++;
                log.error("WALLET_LEDGER_MISMATCH wallet={} user={} kind={} available={} hold={} net={} openHolds={}",
                        row.get("id"), row.get("user_id"), row.get("wallet_kind"), available, hold, net, open);
            }
        }
        log.info("WALLET_LEDGER_CHECK wallets={} mismatches={}", rows.size(), mismatches);
    }
}
