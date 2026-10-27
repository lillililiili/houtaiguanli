package com.uav.lowaltitude.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Verification-triggered notifications must not wait behind historical polling sweeps. */
@Configuration(proxyBeanMethods = false)
public class AdvisoryDispatchConfig {
    public static final String SMS_EXECUTOR = "advisorySmsDispatchExecutor";

    @Bean(name = SMS_EXECUTOR)
    public ThreadPoolTaskExecutor advisorySmsDispatchExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("advisory-sms-dispatch-");
        return executor;
    }
}
