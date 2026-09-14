package com.uav.lowaltitude.modules.fusion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.modules.fusion.application.FusionIngestWorker;
import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 融合领取的**恢复路径**（决策 16-5）：进程在持有租约时挂掉，那一帧必须还能被下一个进程接着处理。
 *
 * <p>这条路在现有用例里是空白的：`Stage8PostgresTest` 只钉了"租约没过期不许重领"，
 * 毒帧死信也有，但**"租约过期 + 还没领够次数 → 重领并走完"从来没被跑过**。
 * 而这正是 worker 崩一次之后系统能不能自己恢复的分界：重领不了，那一帧就永远停在 PROCESSING 里，
 * 没有任何告警、也没有任何人会发现少了一帧。
 *
 * <p>不加 `@Transactional`：`FusionIngestWorker` 内部自己开事务，
 * 套在测试事务里会让管线一旦抛异常就把整个测试事务标成 rollback-only，后面的断言全部失败在无关的地方。
 * 各用例用独立 id，跑完留下的行都是终态（DONE/FAILED）或租约在远未来的 PROCESSING，都不可被领取，不干扰别人。
 */
@SpringBootTest(properties = {"app.dev-seed.enabled=false", "app.outbox.enabled=false",
        // worker 本身默认不注册，要测它就得打开；但把轮询间隔推到一小时，
        // 让调度器只在启动时空跑一次（那时还没有任何夹具行），之后全程由用例自己调 drain()。
        "app.fusion.enabled=true", "app.fusion.poll-millis=3600000", "app.fusion.replay.run-on-start=false"})
@ActiveProfiles("test")
class FusionInboxRecoveryTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired FusionInboxRepository inbox;
    @Autowired FusionIngestWorker worker;
    @Autowired FusionProperties properties;

    /**
     * 租约过期的 PROCESSING 行必须能被重领，并且能一路走到 DONE。
     *
     * <p>断言到 DONE 而不是只断言"领到了"：领到了但状态留在 PROCESSING，下一轮还会被重领，
     * 看起来"能恢复"其实是在原地打转。
     */
    @Test
    void expiredLeaseIsReclaimedAndCanBeCompleted() {
        String sourceId = source();
        String stale = row(sourceId, "replay:recovery", "PROCESSING", 1, System.currentTimeMillis() - 60_000L);

        List<InboxRow> claimed = inbox.claim(System.currentTimeMillis(), 50, 30_000L, 6);

        assertThat(claimed).extracting(InboxRow::inboxId)
                .as("租约已过期、领取次数未耗尽的帧必须能被重领——领不了它就永远停在 PROCESSING，而且没人会发现")
                .contains(stale);
        Map<String, Object> afterClaim = jdbc.queryForMap(
                "select status, fusion_attempts, lease_until from inbox_message where inbox_id=?", stale);
        assertThat(afterClaim).containsEntry("status", "PROCESSING");
        assertThat(((Number) afterClaim.get("fusion_attempts")).intValue())
                .as("重领必须把次数加一，否则毒帧可以无限重领、永远走不到死信").isEqualTo(2);
        assertThat(((Number) afterClaim.get("lease_until")).longValue())
                .as("重领必须续上新租约").isGreaterThan(System.currentTimeMillis());

        inbox.done(stale, System.currentTimeMillis());
        Map<String, Object> afterDone = jdbc.queryForMap(
                "select status, processed_at, lease_token, lease_until from inbox_message where inbox_id=?", stale);
        assertThat(afterDone).containsEntry("status", "DONE").containsEntry("lease_token", null)
                .containsEntry("lease_until", null);
        assertThat(afterDone.get("processed_at")).as("终态必须落处理时刻，否则事后查不出这帧什么时候收尾的").isNotNull();
    }

    /**
     * 租约还没过期的 PROCESSING 行不许被重领，且**一个字节都不许动**。
     *
     * <p>与上一条是同一把规则的两面。只验"能重领"的话，把过期判断整个去掉也照样绿，
     * 而那会让两个 worker 同时处理同一帧——同一份观测进库两次，目标轨迹凭空多出一串点。
     */
    @Test
    void liveLeaseIsNotReclaimedAndIsLeftUntouched() {
        String sourceId = source();
        String leased = row(sourceId, "replay:recovery", "PROCESSING", 1, System.currentTimeMillis() + 600_000L);

        assertThat(inbox.claim(System.currentTimeMillis(), 50, 30_000L, 6))
                .extracting(InboxRow::inboxId).doesNotContain(leased);
        assertThat(jdbc.queryForMap("select status, fusion_attempts from inbox_message where inbox_id=?", leased))
                .as("没被领就不该被改动").containsEntry("status", "PROCESSING").containsEntry("fusion_attempts", 1);
    }

    /**
     * `FusionIngestWorker.drainOnce` 一趟里**既判死信又领取**：毒帧被判 FAILED，健康帧被带到终态。
     *
     * <p>关于"先死信后领取"的顺序：它在外部**观测不到**，因为两步作用的行集是互斥的
     * （`failExhausted` 要 `PROCESSING 且租约过期 且次数>=上限`，`claim` 要 `次数<上限`）。
     * 所以这里不假装在验顺序，验的是**一趟之内两件事都发生了**——这才是 `drainOnce` 此前完全没有测试覆盖的地方。
     *
     * <p>健康帧断言到"终态 + 有处理时刻"而不是断言 DONE：它最终是 DONE 还是 FAILED 取决于
     * 管线认不认这份合成载荷，那不是这条用例要管的事；要管的是 `drainOnce` 有没有把它带离 RECEIVED。
     */
    @Test
    void drainOnceBothDeadLettersPoisonFramesAndClaimsHealthyOnes() {
        String sourceId = source();
        int max = properties.getMaxAttempts();
        String poison = row(sourceId, "replay:recovery", "PROCESSING", max, System.currentTimeMillis() - 60_000L);
        String healthy = row(sourceId, "replay:recovery", "RECEIVED", 0, null);

        worker.drain();

        Map<String, Object> poisonRow = jdbc.queryForMap(
                "select status, processed_at, last_error, lease_token from inbox_message where inbox_id=?", poison);
        assertThat(poisonRow).as("领取次数已耗尽的帧必须被判死信，不能无限重领")
                .containsEntry("status", "FAILED").containsEntry("lease_token", null);
        assertThat(poisonRow.get("processed_at")).isNotNull();
        assertThat((String) poisonRow.get("last_error")).as("死信要说清为什么放弃").contains("最大领取次数");

        Map<String, Object> healthyRow = jdbc.queryForMap(
                "select status, processed_at, fusion_attempts from inbox_message where inbox_id=?", healthy);
        assertThat((String) healthyRow.get("status"))
                .as("同一趟里健康帧必须被领走并带到终态，不能还留在 RECEIVED").isIn("DONE", "FAILED");
        assertThat(healthyRow.get("processed_at")).isNotNull();
        assertThat(((Number) healthyRow.get("fusion_attempts")).intValue()).isEqualTo(1);
    }

    /* ---------------------------------------------------------------- 夹具 */

    private String row(String sourceId, String source, String status, int attempts, Long leaseUntil) {
        String inboxId = UUID.randomUUID().toString();
        jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,"
                + "status,fusion_attempts,lease_token,lease_until)"
                + " values (?,?,?,?,?,?,cast(? as json),?,?,?,?)",
                inboxId, source, UUID.randomUUID().toString(), System.currentTimeMillis(), sourceId,
                "0".repeat(64), "{\"items\":[]}", status, attempts,
                leaseUntil == null ? null : "lease-" + inboxId.substring(0, 8), leaseUntil);
        return inboxId;
    }

    private String source() {
        String sourceId = UUID.randomUUID().toString();
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,source_type,"
                + "created_at,updated_at,version) values (?,?,?,true,'replay','RADAR',current_timestamp,current_timestamp,0)",
                sourceId, "SRC-RECOVER-" + sourceId.substring(0, 8), "恢复路径测试来源");
        return sourceId;
    }
}
