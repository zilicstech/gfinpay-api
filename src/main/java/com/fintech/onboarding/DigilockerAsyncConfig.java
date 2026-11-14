package com.fintech.onboarding;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class DigilockerAsyncConfig {

    @Bean(name = "digilockerExecutor")
    public ThreadPoolTaskExecutor digilockerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("digilocker-doc-");
        executor.initialize();
        return executor;
    }
}

