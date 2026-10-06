package com.uav.lowaltitude.modules.fusion.application;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.modules.fusion.application.FusionPipeline.FrameOutcome;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 融合摄取 Worker：条件更新领取回放 inbox 行（不触碰 ops 的 live-device:* 行），一帧一个事务。
 * 整帧失败回滚并写 FAILED(last_error)：半帧数据会让后续关联建立在残缺证据上，比整帧丢弃更危险。
 * 置 DONE 与该帧的融合结果在同一个事务里提交：提交后、置 DONE 前进程退出时，租约过期会把这一帧再处理一遍，观测重复入库。
 * 有积压时一次调度连续领取多批（ZT-06）：原先每批处理完都要睡 poll-millis，本地每批 5 帧时上限约每秒 5 帧，
 * 来得再多也只能越积越多；现在领空或用完 drain-budget-millis 才让出调度线程。
 * 不扩展 OutboxWorker（那是设备命令的下行通道，主题词表与死信策略都属设备模块）。
 * @ConditionalOnProperty 默认关闭：生产不跑回放摄取；种子与测试直接调用 {@link #drain()} 同步驱动。
 */
@Component
@ConditionalOnProperty(prefix = "app.fusion", name = "enabled", havingValue = "true")
public class FusionIngestWorker {
    private static final Logger log = LoggerFactory.getLogger(FusionIngestWorker.class);

    private final FusionInboxRepository inbox;
    private final FusionPipeline pipeline;
    private final FusionProperties properties;
    private final AppClock clock;
    private final TransactionTemplate perFrame;

    public FusionIngestWorker(FusionInboxRepository inbox, FusionPipeline pipeline, FusionProperties properties, AppClock clock,
            PlatformTransactionManager transactionManager) {
        this.inbox = inbox; this.pipeline = pipeline; this.properties = properties; this.clock = clock;
        this.perFrame = new TransactionTemplate(transactionManager);
    }

    // 首轮默认随调度器启动立即跑；测试可推迟首轮，免得排在其他定时任务之后的首轮和用例手工领取撞在一起。
    @Scheduled(fixedDelayString = "${app.fusion.poll-millis:500}", initialDelayString = "${app.fusion.initial-delay-millis:0}")
    public void poll() {
        // 预算量的是"这次调度已经占了调度线程多久"，用单调时钟；它不是业务时间，不走 AppClock。
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(properties.getDrainBudgetMillis());
        long started = System.nanoTime();
        while (true) {
            int claimed = drainOnce();
            // 不足一批说明已经领空；预算为 0 时保持旧行为：每次调度只处理一批。
            if (claimed < properties.getBatchSize() || budgetNanos == 0 || System.nanoTime() - started >= budgetNanos) return;
        }
    }

    /** 把当前所有待处理回放帧跑完（种子/测试用；避免依赖调度节拍）。返回处理的帧数。 */
    public int drain() {
        int total = 0;
        for (int round = 0; round < 1000; round++) {
            int processed = drainOnce();
            if (processed == 0) return total;
            total += processed;
        }
        return total;
    }

    private int drainOnce() {
        long now = clock.nowMillis();
        // 先把领取次数耗尽且租约过期的毒帧置 FAILED，再领取新一批；两步都只作用于 replay:* 行。
        int exhausted = inbox.failExhausted(now, properties.getMaxAttempts());
        if (exhausted > 0) log.warn("fusion frames abandoned after max attempts: count={}", exhausted);
        List<InboxRow> rows = inbox.claim(now, properties.getBatchSize(), properties.getLeaseMillis(), properties.getMaxAttempts());
        for (InboxRow row : rows) {
            try {
                FrameOutcome outcome = perFrame.execute(status -> {
                    FrameOutcome result = pipeline.processFrame(row);
                    inbox.done(row.inboxId(), clock.nowMillis());
                    return result;
                });
                log.debug("fusion frame done: inbox={}, observations={}, targets={}", row.inboxId(), outcome.observationCount(), outcome.targetCount());
            } catch (RuntimeException ex) {
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                inbox.fail(row.inboxId(), clock.nowMillis(), reason.length() > 1000 ? reason.substring(0, 1000) : reason);
                log.warn("fusion frame failed: inbox={}, error={}", row.inboxId(), ex.toString());
            }
        }
        return rows.size();
    }
}
