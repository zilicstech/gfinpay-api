package com.fintech.platform.scheduler;

import com.fintech.platform.outbox.OutboxProcessor;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls outbox_events and dispatches to partners. Fixed-delay Spring Scheduler job, ShedLock-guarded. */
@Component
public class OutboxDispatcherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherJob.class);

    private final JdbcTemplate jdbc;
    private final OutboxProcessor processor;

    public OutboxDispatcherJob(JdbcTemplate jdbc, OutboxProcessor processor) {
        this.jdbc = jdbc;
        this.processor = processor;
    }

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "outbox-dispatcher", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    public void run() {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM outbox_events
                 WHERE status IN ('NEW','FAILED') AND next_retry_at <= now()
                 ORDER BY id LIMIT 20
                """, Long.class);
        if (ids.isEmpty()) {
            return;
        }
        long startMs = System.currentTimeMillis();
        log.info("OUTBOX_BATCH started size={}", ids.size());
        try {
            for (Long id : ids) {
                try {
                    processor.process(id);
                } catch (Exception e) {
                    log.error("OUTBOX_BATCH event {} unexpected failure: {}", id, e.getMessage(), e);
                }
            }
        } finally {
            log.info("OUTBOX_BATCH completed size={} in {} ms", ids.size(), System.currentTimeMillis() - startMs);
        }
    }
}
