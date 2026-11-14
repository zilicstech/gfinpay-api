package com.fintech.onboarding;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OnboardingHousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(OnboardingHousekeepingJob.class);

    private final JdbcTemplate jdbc;

    public OnboardingHousekeepingJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 3600_000, initialDelay = 60_000)
    @SchedulerLock(name = "onboarding-housekeeping", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    public void run() {
        int expiredInvites = jdbc.update("""
                UPDATE onboarding_invites
                   SET status = 'EXPIRED', updated_at = now()
                 WHERE status IN ('SENT', 'IN_PROGRESS')
                   AND expires_at < now()
                """);
        int expiredJourneys = jdbc.update("""
                UPDATE onboarding_journeys j
                   SET status = 'EXPIRED'
                  FROM onboarding_invites i
                 WHERE j.invite_id = i.id
                   AND i.status = 'EXPIRED'
                   AND j.status IN ('IN_PROGRESS', 'ABANDONED')
                """);
        int abandonedJourneys = jdbc.update("""
                UPDATE onboarding_journeys
                   SET status = 'ABANDONED'
                 WHERE status = 'IN_PROGRESS'
                   AND last_activity_at < now() - interval '72 hours'
                """);
        if (expiredInvites > 0 || expiredJourneys > 0 || abandonedJourneys > 0) {
            log.info("ONBOARDING_HOUSEKEEPING expiredInvites={} expiredJourneys={} abandonedJourneys={}",
                    expiredInvites, expiredJourneys, abandonedJourneys);
        }
    }
}

