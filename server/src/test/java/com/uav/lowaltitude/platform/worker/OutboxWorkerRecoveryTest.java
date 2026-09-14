package com.uav.lowaltitude.platform.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.modules.device.application.DeviceOperationsProcessor;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * Outbox 投递的**重试与死信**（决策 16-5）：设备适配器一时不通时，指令既不能丢、也不能无限重发。
 *
 * <p>这一整段此前在测试里零命中：`attempt_count`、退避 `min(60s, 1s<<attempts)`、
 * 第六次转死信、以及 `claimOutbox` 用 `available_at` 做 CAS 当 30 秒租约，都没有任何用例走过。
 * 现有那两处 `TIMED_OUT` 断言走的是**指令 deadline** 那条路，不是 worker 的重试路，两者容易混为一谈。
 *
 * <p>用 mock 的 `DeviceOperationsProcessor` 而不是构造一个会失败的真载荷：
 * 要验的是 worker 在"处理抛异常"时怎么记账，不是适配器为什么失败；
 * 真载荷一改格式，这些用例就会红在跟重试毫无关系的地方。**产品代码一行没改**（决策 16-5）。
 *
 * <p>时钟固定：退避时间要能**按等号**断言。用真实时钟的话只能写"大于现在"，
 * 而那条断言在退避被算成 1 毫秒时照样是绿的。
 */
@SpringBootTest(properties = {"app.dev-seed.enabled=false", "app.outbox.enabled=false"})
@ActiveProfiles("test")
class OutboxWorkerRecoveryTest {

    private static final long NOW = Instant.parse("2026-09-09T03:00:00Z").toEpochMilli();
    private static final long LEASE_MILLIS = 30_000L;
    private static final String TOPIC = "device.reboot";

    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceRepository repository;

    /** 在途行（`available_at` 在未来）必须被跳过：30 秒租约内另一个 worker 不许插手同一条指令。 */
    @Test
    void inFlightRowIsSkippedWhileItsLeaseHolds() {
        DeviceOperationsProcessor processor = processor();
        String id = outbox("{\"case\":\"in-flight\"}", NOW + LEASE_MILLIS, 1);

        worker(processor).poll();

        verify(processor, never()).process(eq(TOPIC), contains("in-flight"));
        assertThat(row(id)).as("在途行不该被碰——碰了就等于同一条指令被下发两次")
                .containsEntry("attempt_count", 1).containsEntry("processed_at", null);
        assertThat(((Number) row(id).get("available_at")).longValue()).isEqualTo(NOW + LEASE_MILLIS);
    }

    /** 租约过期的行必须被重领并投递成功后标记完成。 */
    @Test
    void expiredRowIsReclaimedAndCompleted() {
        DeviceOperationsProcessor processor = processor();
        String id = outbox("{\"case\":\"expired\"}", NOW - 1_000L, 1);

        worker(processor).poll();

        verify(processor, times(1)).process(eq(TOPIC), contains("expired"));
        Map<String, Object> row = row(id);
        assertThat(row.get("processed_at")).as("投递成功必须落完成时刻，否则下一轮会重发同一条指令").isNotNull();
        assertThat(row).containsEntry("last_error", null);
        assertThat(((Number) row.get("attempt_count")).intValue())
                .as("领取本身要计一次数，否则失败次数永远到不了死信阈值").isEqualTo(2);
    }

    /**
     * 处理失败：次数加一，且 `available_at` 按**指数退避**推迟，行仍然待处理。
     *
     * <p>按等号断言退避值（第一次失败 = 1s<<1 = 2s）。只断言"推迟了"，
     * 退避被算成 1 毫秒也照样绿——那等于适配器一直不通时我们在全速重试它。
     */
    @Test
    void failedProcessingIncrementsAttemptsAndBacksOff() {
        DeviceOperationsProcessor processor = processor();
        doThrow(new IllegalStateException("适配器不可达")).when(processor).process(anyString(), anyString());
        String id = outbox("{\"case\":\"backoff\"}", NOW - 1_000L, 0);

        worker(processor).poll();

        Map<String, Object> row = row(id);
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("available_at")).longValue())
                .as("第一次失败后退避 1s<<1 = 2s").isEqualTo(NOW + 2_000L);
        assertThat(row.get("processed_at")).as("还没到死信阈值，这条指令必须留着继续重试").isNull();
        assertThat((String) row.get("last_error")).contains("适配器不可达");
        verify(processor, never()).timeout(anyString(), anyString(), anyString());
    }

    /**
     * 第六次失败转死信：调 `timeout` 把指令置超时，并把 outbox 行**完成**掉。
     *
     * <p>两半都要钉：只验"调了 timeout"的话，行没被完成就会被第七次、第八次领走，
     * 同一条指令被反复宣告超时；只验"行完成了"的话，指令会悄悄消失——
     * 下发过、再没有下文，事后谁也说不清那次重启到底有没有执行。
     */
    @Test
    void sixthFailureDeadLettersTheRowAndTimesOutTheCommand() {
        DeviceOperationsProcessor processor = processor();
        doThrow(new IllegalStateException("适配器连续不可达")).when(processor).process(anyString(), anyString());
        String id = outbox("{\"case\":\"dead-letter\"}", NOW - 1_000L, 5);

        worker(processor).poll();

        verify(processor, times(1)).timeout(eq(TOPIC), contains("dead-letter"), contains("适配器连续重试失败"));
        Map<String, Object> row = row(id);
        assertThat(row.get("processed_at")).as("死信必须把行完成掉，否则同一条指令会被反复宣告超时").isNotNull();
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(6);
    }

    /* ---------------------------------------------------------------- 夹具 */

    private OutboxWorker worker(DeviceOperationsProcessor processor) {
        return new OutboxWorker(repository, processor, new AppClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)));
    }

    private DeviceOperationsProcessor processor() {
        DeviceOperationsProcessor processor = mock(DeviceOperationsProcessor.class);
        doNothing().when(processor).expireCommands(anyLong());
        return processor;
    }

    /** 造一条 outbox 行。`addOutbox` 只会写 attempt_count=0，已重试过的状态要直接改列。 */
    private String outbox(String payload, long availableAt, int attempts) {
        String id = UUID.randomUUID().toString();
        repository.addOutbox(id, TOPIC, payload, NOW - 60_000L, availableAt);
        if (attempts != 0) jdbc.update("update outbox_event set attempt_count=? where outbox_id=?", attempts, id);
        return id;
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select attempt_count, available_at, processed_at, last_error from outbox_event where outbox_id=?", id);
    }
}
