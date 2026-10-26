package com.uav.lowaltitude.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.fusion.application.FusionIngestWorker;
import com.uav.lowaltitude.modules.fusion.application.FusionLossSweeper;
import com.uav.lowaltitude.modules.fusion.application.FusionPipeline;
import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.prediction.application.PredictionConfiguration;
import com.uav.lowaltitude.modules.prediction.application.PredictionJobQueue;
import com.uav.lowaltitude.modules.prediction.application.PredictionResponseService;
import com.uav.lowaltitude.modules.prediction.application.PredictionWorker;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository;
import com.uav.lowaltitude.platform.number.BusinessNumberService;
import com.uav.lowaltitude.platform.time.AppClock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

class RealtimeSchedulingConfigTest {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class, RealtimeSchedulingConfig.class)
                .withPropertyValues("spring.task.scheduling.pool.size=1",
                        "spring.task.scheduling.thread-name-prefix=background-test-",
                        "app.fusion.enabled=true",
                        "app.fusion.initial-delay-millis=0", "app.fusion.poll-millis=20",
                        "app.fusion.loss-sweep.interval-millis=20",
                        "app.prediction.initial-delay-ms=0", "app.prediction.poll-ms=20");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void preservesConfiguredDefaultPoolAndBoundsRealtimePools(int size) {
        context().withPropertyValues("spring.task.scheduling.pool.size=" + size).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var background = ctx.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(background.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(size);
            assertThat(background.getThreadNamePrefix()).isEqualTo("background-test-");
            assertThat(ctx.getBean(RealtimeSchedulingConfig.FUSION_SCHEDULER, ThreadPoolTaskScheduler.class)
                    .getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(ctx.getBean(RealtimeSchedulingConfig.PREDICTION_SCHEDULER, ThreadPoolTaskScheduler.class)
                    .getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        });
    }

    @Test
    void scheduledWorkersContinueWhenDefaultPoolIsOccupied() {
        var background = new BackgroundBlocker();
        var workers = new Workers();
        var fusion = workers.fusion;
        var prediction = workers.prediction;
        var fusionNext = new AtomicReference<CountDownLatch>();
        var predictionNext = new AtomicReference<CountDownLatch>();
        var fusionThread = new AtomicReference<String>();
        var predictionThread = new AtomicReference<String>();
        doAnswer(call -> {
            signal(fusionNext, fusionThread);
            return List.of();
        }).when(workers.inbox).claim(anyLong(), anyInt(), anyLong(), anyInt());
        doAnswer(call -> {
            signal(predictionNext, predictionThread);
            return false;
        }).when(workers.jobs).enabled();
        context().withBean(BackgroundBlocker.class, () -> background)
                .withBean(FusionIngestWorker.class, () -> fusion)
                .withBean(PredictionWorker.class, () -> prediction).run(ctx -> {
                    try {
                        assertThat(ctx).hasNotFailed();
                        assertWorkerSchedules(ctx, fusion, prediction);
                        await(background.entered);
                        var fusionTick = new CountDownLatch(1);
                        var predictionTick = new CountDownLatch(1);
                        fusionNext.set(fusionTick);
                        predictionNext.set(predictionTick);
                        await(fusionTick);
                        await(predictionTick);
                        assertThat(background.thread.get()).startsWith("background-test-");
                        assertThat(fusionThread.get()).startsWith("fusion-scheduling-");
                        assertThat(predictionThread.get()).startsWith("prediction-scheduling-");
                    } finally {
                        background.release.countDown();
                    }
                });
    }

    @Test
    void blockedFusionIngestAndSweepDoNotDelayPrediction() {
        var workers = new Workers();
        var fusion = workers.fusion;
        var prediction = workers.prediction;
        var fusionEntered = new CountDownLatch(2);
        var releaseFusion = new CountDownLatch(1);
        var predictionNext = new AtomicReference<CountDownLatch>();
        doAnswer(call -> {
            hold(fusionEntered, releaseFusion);
            return List.of();
        }).when(workers.inbox).claim(anyLong(), anyInt(), anyLong(), anyInt());
        doAnswer(call -> hold(fusionEntered, releaseFusion)).when(workers.sweeper).sweep(any());
        doAnswer(call -> {
            signal(predictionNext, new AtomicReference<>());
            return false;
        }).when(workers.jobs).enabled();
        context().withBean(FusionIngestWorker.class, () -> fusion)
                .withBean(PredictionWorker.class, () -> prediction).run(ctx -> {
                    try {
                        assertThat(ctx).hasNotFailed();
                        assertWorkerSchedules(ctx, fusion, prediction);
                        await(fusionEntered);
                        var next = new CountDownLatch(1);
                        predictionNext.set(next);
                        await(next);
                    } finally {
                        releaseFusion.countDown();
                    }
                });
    }

    @Test
    void blockedPredictionDoesNotDelayMeasuredIngestOrLossSweep() {
        var workers = new Workers();
        var fusion = workers.fusion;
        var prediction = workers.prediction;
        var predictionEntered = new CountDownLatch(1);
        var releasePrediction = new CountDownLatch(1);
        var ingestNext = new AtomicReference<CountDownLatch>();
        var sweepNext = new AtomicReference<CountDownLatch>();
        doAnswer(call -> {
            signal(ingestNext, new AtomicReference<>());
            return List.of();
        }).when(workers.inbox).claim(anyLong(), anyInt(), anyLong(), anyInt());
        doAnswer(call -> signal(sweepNext, new AtomicReference<>())).when(workers.sweeper).sweep(any());
        doAnswer(call -> {
            hold(predictionEntered, releasePrediction);
            return false;
        }).when(workers.jobs).enabled();
        context().withBean(FusionIngestWorker.class, () -> fusion)
                .withBean(PredictionWorker.class, () -> prediction).run(ctx -> {
                    try {
                        assertThat(ctx).hasNotFailed();
                        assertWorkerSchedules(ctx, fusion, prediction);
                        await(predictionEntered);
                        var ingest = new CountDownLatch(1);
                        var sweep = new CountDownLatch(1);
                        ingestNext.set(ingest);
                        sweepNext.set(sweep);
                        await(ingest);
                        await(sweep);
                    } finally {
                        releasePrediction.countDown();
                    }
                });
    }

    @Test
    void contextShutdownClosesAllOwnedSchedulers() throws InterruptedException {
        var executors = new AtomicReference<List<ScheduledThreadPoolExecutor>>();
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            executors.set(ctx.getBeansOfType(ThreadPoolTaskScheduler.class).values().stream()
                    .map(ThreadPoolTaskScheduler::getScheduledThreadPoolExecutor).toList());
            assertThat(executors.get()).hasSize(3);
            for (var executor : executors.get()) {
                var ran = new CountDownLatch(1);
                executor.execute(ran::countDown);
                await(ran);
            }
        });
        for (var executor : executors.get()) {
            assertThat(executor.isShutdown()).isTrue();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Inspect the production worker annotations, rather than scheduling test substitutes. */
    private static void assertWorkerSchedules(ApplicationContext context, FusionIngestWorker fusion,
            PredictionWorker prediction) {
        assertThat(context.getBean(FusionIngestWorker.class)).isSameAs(fusion);
        assertThat(context.getBean(PredictionWorker.class)).isSameAs(prediction);
        // Spring 6.2 wraps scheduled runnables to track outcomes; Task.toString()
        // delegates to the registered method without depending on that internal wrapper.
        var methods = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().stream()
                .map(task -> task.getTask().toString()).toList();
        String fusionType = FusionIngestWorker.class.getName() + ".";
        String predictionType = PredictionWorker.class.getName() + ".";
        assertThat(methods.stream().filter(method -> method.startsWith(fusionType)).toList())
                .containsExactlyInAnyOrder(fusionType + "poll", fusionType + "sweepSilentTargets");
        assertThat(methods.stream().filter(method -> method.startsWith(predictionType)).toList())
                .containsExactly(predictionType + "poll");
    }

    private static Void signal(AtomicReference<CountDownLatch> next, AtomicReference<String> thread) {
        thread.set(Thread.currentThread().getName());
        var latch = next.get();
        if (latch != null) latch.countDown();
        return null;
    }

    private static Void hold(CountDownLatch entered, CountDownLatch release) throws InterruptedException {
        entered.countDown();
        release.await(10, TimeUnit.SECONDS);
        return null;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /** Real worker beans retain their scheduling metadata; only their I/O boundaries are mocked. */
    private static final class Workers {
        final FusionInboxRepository inbox = mock(FusionInboxRepository.class);
        final FusionLossSweeper sweeper = mock(FusionLossSweeper.class);
        final PredictionJobQueue jobs = mock(PredictionJobQueue.class);
        final FusionIngestWorker fusion;
        final PredictionWorker prediction;

        Workers() {
            var clock = new AppClock();
            var transactions = mock(PlatformTransactionManager.class);
            var fusionProperties = new FusionProperties();
            fusionProperties.setDrainBudgetMillis(0);
            fusion = new FusionIngestWorker(inbox, mock(FusionPipeline.class), sweeper,
                    fusionProperties, clock, transactions);
            prediction = new PredictionWorker(mock(JdbcTemplate.class), new ObjectMapper(), jobs,
                    mock(PredictionInputRepository.class), mock(PredictionConfiguration.class),
                    mock(PredictionResponseService.class), mock(AlarmMergeRepository.class),
                    mock(UavEventRepository.class), mock(BusinessNumberService.class), clock, transactions);
        }
    }

    static class BackgroundBlocker {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<String> thread = new AtomicReference<>();

        @Scheduled(fixedDelay = 20)
        public void poll() throws InterruptedException {
            thread.set(Thread.currentThread().getName());
            hold(entered, release);
        }
    }
}
