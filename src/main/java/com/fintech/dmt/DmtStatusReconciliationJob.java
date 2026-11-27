package com.fintech.dmt;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Webhook-free scheduled DMT status reconciliation &amp; post-settlement reconfirmation job.
 *
 * <p>Because InstantPay does not emit webhooks for DMT, this ShedLock-guarded scheduler
 * polls {@code POST /reports/txnStatus} for:
 * <ol>
 *   <li>Open ({@code HOLD}/{@code PENDING}) DMT transfers past the initial cooling window ({@code >= 30m})
 *       and poll interval ({@code >= 4h})</li>
 *   <li>Settled {@code NEFT} {@code SUCCESS} transfers ({@code 2h–48h} old) that have not yet been
 *       reconfirmed, catching delayed destination-bank batch returns automatically</li>
 * </ol>
 */
@Component
public class DmtStatusReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(DmtStatusReconciliationJob.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MAINT_START = LocalTime.of(23, 30);
    private static final LocalTime MAINT_END = LocalTime.of(0, 30);

    private final JdbcTemplate jdbc;
    private final DmtCasaService dmtService;
    private final DmtProperties props;

    public DmtStatusReconciliationJob(JdbcTemplate jdbc, DmtCasaService dmtService, DmtProperties props) {
        this.jdbc = jdbc;
        this.dmtService = dmtService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.dmt.recon-sweep-ms:300000}", initialDelay = 45_000)
    @SchedulerLock(name = "dmt-status-recon", lockAtMostFor = "PT4M", lockAtLeastFor = "PT10S")
    public void runSweep() {
        if (!props.reconEnabled()) {
            return;
        }
        LocalTime nowIst = LocalTime.now(IST);
        if (!nowIst.isBefore(MAINT_START) || nowIst.isBefore(MAINT_END)) {
            return;
        }

        int initDelayMins = props.statusInitialDelayMinutes();
        int intervalMins = props.statusPollIntervalMinutes();

        List<UUID> pendingIds = jdbc.queryForList("""
                SELECT transaction_id
                  FROM dmt_transfers
                 WHERE status IN ('HOLD', 'PENDING', 'IN_PROCESS', 'SENT', 'ON_HOLD')
                   AND created_at <= now() - make_interval(mins => ?)
                   AND (last_status_polled_at IS NULL OR last_status_polled_at <= now() - make_interval(mins => ?))
                 ORDER BY created_at ASC
                 LIMIT 20
                """, UUID.class, initDelayMins, intervalMins);

        List<UUID> neftReconfirmIds = jdbc.queryForList("""
                SELECT transaction_id
                  FROM dmt_transfers
                 WHERE status = 'SUCCESS'
                   AND txn_type = 'NEFT'
                   AND reconfirmed_at IS NULL
                   AND created_at BETWEEN now() - interval '48 hours' AND now() - interval '2 hours'
                   AND (last_status_polled_at IS NULL OR last_status_polled_at <= now() - make_interval(mins => ?))
                 ORDER BY created_at ASC
                 LIMIT 10
                """, UUID.class, intervalMins);

        if (pendingIds.isEmpty() && neftReconfirmIds.isEmpty()) {
            return;
        }

        log.info("DMT_RECON_SWEEP started pending={} neftReconfirm={}", pendingIds.size(), neftReconfirmIds.size());
        for (UUID txnId : pendingIds) {
            try {
                dmtService.reconcileByTransactionId(txnId, false);
            } catch (Exception ex) {
                log.warn("DMT_RECON_SWEEP pending check failed txnId={}: {}", txnId, ex.getMessage());
            }
        }
        for (UUID txnId : neftReconfirmIds) {
            try {
                dmtService.reconcileByTransactionId(txnId, false);
            } catch (Exception ex) {
                log.warn("DMT_RECON_SWEEP NEFT reconfirm failed txnId={}: {}", txnId, ex.getMessage());
            }
        }
    }
}
