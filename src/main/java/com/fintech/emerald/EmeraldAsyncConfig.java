package com.fintech.emerald;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated non-blocking background executor for Emerald request/response sanitization,
 * trace logging, and {@code emerald_api_logs} DB persistence so synchronous payment/onboarding
 * threads experience zero audit-logging latency overhead.
 */
@Configuration
public class EmeraldAsyncConfig {

    @Bean(name = "emeraldAuditExecutor")
    public Executor emeraldAuditExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(6);
        exec.setQueueCapacity(2000);
        exec.setThreadNamePrefix("emerald-audit-");
        // Never block or throw on the caller thread if the queue saturates under extreme load
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(5);
        exec.initialize();
        return exec;
    }
}

