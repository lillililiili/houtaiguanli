package com.uav.lowaltitude.platform.worker;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.device.application.DeviceOperationsProcessor;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 同应用 Worker：通过条件更新领取到期 Outbox。处理器以业务 ID 幂等，服务重启后可继续扫描。
 *
 * 可以关（决策 15-39，与 {@code MqttSessionSupervisor} 同形）：默认开，`app.outbox.enabled=false` 时连 bean 都不注册。
 * 起因是 PG 用例类 `@AfterAll` 拆 schema 到上下文关闭之间有一段窗口，这个 400ms 轮询还在跑 `expireCommands`，
 * CI 日志里落下 6 条 `relation "device_command" does not exist`。真正的风险不在那几行噪音：
 * 它**不是只读的**（会把超时指令置 TIMED_OUT 并写设备事件），哪天 PG 用例种了带 deadline 的 device_command，
 * 就会有一个后台写手在断言中途改行——那种红查起来极贵，且看着像业务代码的错。
 * 用产品开关而不是去改九个测试类：测试要关掉的是"后台线程在跑"，那本来就该是应用自己的开关。
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final DeviceRepository repository;
    private final DeviceOperationsProcessor processor;
    private final AppClock clock;

    public OutboxWorker(DeviceRepository repository, DeviceOperationsProcessor processor, AppClock clock) {
        this.repository = repository;
        this.processor = processor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-millis:400}")
    public void poll() {
        long now = clock.nowMillis();
        processor.expireCommands(now);
        List<Map<String, Object>> rows = repository.dueOutbox(now, 20);
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("outbox_id"));
            long availableAt = ((Number) row.get("available_at")).longValue();
            if (repository.claimOutbox(id, availableAt, now + 30_000L) != 1) continue;
            try {
                processor.process(String.valueOf(row.get("topic")), String.valueOf(row.get("payload")));
                repository.completeOutbox(id, clock.nowMillis());
            } catch (Exception ex) {
                int attempts = row.get("attempt_count") instanceof Number n ? n.intValue() + 1 : 1;
                if (attempts >= 6) {
                    String detail = "适配器连续重试失败：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    processor.timeout(String.valueOf(row.get("topic")), String.valueOf(row.get("payload")), detail);
                    repository.completeOutbox(id, clock.nowMillis());
                } else {
                    long delay = Math.min(60_000L, 1_000L << Math.min(attempts, 6));
                    repository.failOutbox(id, clock.nowMillis() + delay, ex.getMessage());
                }
                log.warn("outbox processing failed: id={}, topic={}, attempt={}", id, row.get("topic"), attempts);
            }
        }
    }
}
