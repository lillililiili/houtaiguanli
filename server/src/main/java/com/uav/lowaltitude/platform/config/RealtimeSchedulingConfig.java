package com.uav.lowaltitude.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keep measured ingestion and short-horizon forecasting independent of notification sweeps. */
@Configuration(proxyBeanMethods = false)
public class RealtimeSchedulingConfig {
    public static final String FUSION_SCHEDULER = "fusionTaskScheduler";
    public static final String PREDICTION_SCHEDULER = "predictionTaskScheduler";

    // Adding a TaskScheduler makes Boot back off its default scheduler bean. Rebuild that
    // default from Boot's configured builder so pool size, shutdown and customizers survive.
    @Bean(name = "taskScheduler")
    @Primary
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(name = FUSION_SCHEDULER)
    public ThreadPoolTaskScheduler fusionTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        // Ingestion may drain several batches; loss sweeping must still have its own slot.
        return builder.poolSize(2).threadNamePrefix("fusion-scheduling-").build();
    }

    @Bean(name = PREDICTION_SCHEDULER)
    public ThreadPoolTaskScheduler predictionTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        // The worker is sequential and its durable per-target locks retain retry/idempotency.
        return builder.poolSize(1).threadNamePrefix("prediction-scheduling-").build();
    }
}
