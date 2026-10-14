package com.fintech.audit;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class ActivityAuditConfig {

    @Bean(name = "activityAuditExecutor")
    public ThreadPoolTaskExecutor activityAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("activity-audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public ActivityAuditFilter activityAuditFilter(ActivityAuditWriter writer,
                                                   com.fasterxml.jackson.databind.ObjectMapper mapper,
                                                   org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new ActivityAuditFilter(writer, mapper, jdbc);
    }

    @Bean
    public FilterRegistrationBean<ActivityAuditFilter> activityAuditFilterRegistration(ActivityAuditFilter filter) {
        FilterRegistrationBean<ActivityAuditFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
