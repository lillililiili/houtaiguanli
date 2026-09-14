package com.uav.lowaltitude.modules.fusion.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    @Scheduled(fixedDelayString = "${app.fusion.poll-millis:500}")
    public void poll() { drainOnce(); }

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
                FrameOutcome outcome = perFrame.execute(status -> pipeline.processFrame(row));
                inbox.done(row.inboxId(), clock.nowMillis());
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
