package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.application.FusionLossSweeper.SweepReport;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * ZT-20 复测 2（真实管线 + 真实融合层写入器，H2）：设备停报后，同一分区再没有别的数据，目标也要按平台多久没收到它的数据
 * 按时短失（3 s）、终止（15 s），不再一直停在 STABLE、等同一设备再报一帧才一步跳到 TERMINATED。
 * 每个用例用自己的外部编号、一块远离种子的位置和相隔 3 小时的时间段；horizon 设为 1 小时，用例之间互不推进对方的目标。
 */
@SpringBootTest(properties = {
        "app.dev-seed.enabled=true", "app.fusion.enabled=false", "app.fusion.replay.run-on-start=false",
        "app.fusion.loss-sweep.horizon-millis=3600000",
        "spring.datasource.url=jdbc:h2:mem:fusion_loss_sweeper;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class FusionLossSweeperTest {
    private static final String RADAR = "seed-stage8-source-radar";
    private static final double METERS_PER_DEG_LAT = 111_320.0;
    private static final int UAV = 30;

    @Autowired JdbcTemplate jdbc;
    @Autowired FusionPipeline pipeline;
    @Autowired FusionLossSweeper sweeper;
    @Autowired FusionInboxRepository inbox;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper json;

    private long msgCnt = 1;

    @Test
    void radarAloneInItsAreaStopsAndItsTargetIsLostThenTerminatedOnThePlatformClock() {
        // 复测原样：分区里只有这一台雷达，它报 5 帧后停报，之后再没有任何帧进来。
        Instant t0 = Instant.parse("2026-10-06T09:00:00Z");
        double lon = 118.45, lat = 37.45;
        Instant lastReceived = report("ZT20I-R1", t0, 5, lon, lat, 0);
        String target = targetOf("ZT20I-R1");
        assertThat(status(target)).isEqualTo("STABLE");
        Instant lastObserved = t0.plusSeconds(4);

        assertThat(sweeper.sweep(lastReceived.plusMillis(3_400)).aged()).as("停报 3.4 s：还在 3 s 阈值加 0.5 s 余量以内").isZero();
        assertThat(status(target)).isEqualTo("STABLE");

        Instant lostAt = lastReceived.plusMillis(3_600);
        assertThat(sweeper.sweep(lostAt).aged()).isEqualTo(1);
        Map<String, Object> lost = jdbc.queryForMap("select status,since,miss_frames,last_observed_at,last_received_at from target_track_status where target_id=?", target);
        assertThat(lost.get("status")).isEqualTo("SHORT_LOST");
        assertThat(instant(lost.get("since"))).as("短失时刻是平台判定的时刻").isEqualTo(lostAt);
        assertThat(instant(lost.get("last_received_at"))).as("推进不改最近一次命中的到达时刻").isEqualTo(lastReceived);
        assertThat(instant(lost.get("last_observed_at"))).isEqualTo(lastObserved);
        JsonNode basis = jsonOf(jdbc.queryForObject("select cast(basis as varchar) from target_lineage where op='STATUS' and survivor_target_id=?"
                + " and occurred_at=?", String.class, target, java.sql.Timestamp.from(lostAt)));
        assertThat(basis.path("status").asText()).isEqualTo("SHORT_LOST");
        assertThat(basis.path("no_data_ms").asLong()).isEqualTo(3_600L);
        assertThat(instant(jdbc.queryForObject("select observed_at from target_latest_state where target_id=?", Object.class, target)))
                .as("最新状态的观测时刻不推到平台此刻").isEqualTo(lastObserved);

        assertThat(sweeper.sweep(lastReceived.plusMillis(15_400)).aged()).as("停报 15.4 s：短失，还没到终止").isZero();
        assertThat(status(target)).isEqualTo("SHORT_LOST");
        assertThat(sweeper.sweep(lastReceived.plusMillis(15_600)).aged()).isEqualTo(1);
        assertThat(status(target)).isEqualTo("TERMINATED");
        assertThat(jdbc.queryForList("select ended_at from track where target_id=? and layer='RAW'", Object.class, target))
                .as("原始轨迹在最后一次观测处结束").isNotEmpty().allSatisfy(ended -> assertThat(instant(ended)).isEqualTo(lastObserved));
        assertThat(jdbc.queryForList("select ended_at from track where target_id=? and layer='FUSED'", Object.class, target))
                .as("融合轨迹同样结束").isNotEmpty().allSatisfy(ended -> assertThat(instant(ended)).isEqualTo(lastObserved));
        assertThat(sweeper.sweep(lastReceived.plusSeconds(60)).aged()).as("终止后不再推进").isZero();

        // 停报一分钟后恢复上报：原目标已终止，按新目标跟踪（与管线里同一分区有别的数据时的行为一致）。
        report("ZT20I-R1", t0.plusSeconds(65), 3, lon, lat, 0);
        String resumed = targetOf("ZT20I-R1");
        assertThat(resumed).isNotEqualTo(target);
        assertThat(status(target)).isEqualTo("TERMINATED");
    }

    @Test
    void slowClockRadarComesBackAfterAShortGapAndKeepsItsTargetAndLivePosition() {
        // 雷达时钟慢 120 s。停报 5 s（短失）后恢复：仍是同一个目标、回到 STABLE，最新状态跟着它的新位置走——
        // 推进短失时若把最新状态的观测时刻推到平台此刻，它恢复后的帧在融合层看来全是"迟到帧"，位置两分钟不更新。
        Instant t0 = Instant.parse("2026-10-06T12:00:00Z");
        double lon = 118.55, lat = 37.55;
        Instant lastReceived = report("ZT20K-R1", t0, 5, lon, lat, 120);
        String target = targetOf("ZT20K-R1");
        assertThat(sweeper.sweep(lastReceived.plusMillis(3_600)).aged()).isEqualTo(1);
        assertThat(status(target)).isEqualTo("SHORT_LOST");

        Instant resumedAt = t0.plusSeconds(10);
        double north = lat + 50.0 / METERS_PER_DEG_LAT;
        frame(RADAR, resumedAt.minusSeconds(120), resumedAt.plusMillis(200), "ZT20K-R1", lon, north);
        assertThat(targetOf("ZT20K-R1")).isEqualTo(target);
        assertThat(status(target)).as("恢复后命中，确认次数早已够，直接回到 STABLE").isEqualTo("STABLE");
        assertThat(instant(jdbc.queryForObject("select observed_at from target_latest_state where target_id=?", Object.class, target)))
                .as("最新状态是恢复后这一帧").isEqualTo(resumedAt.minusSeconds(120));
        assertThat(sweeper.sweep(resumedAt.plusMillis(3_000)).aged()).as("恢复后 3 s 内不再判短失").isZero();
    }

    @Test
    void framesStillWaitingInTheInboxHoldTheSweepBack() {
        // 积压：这台雷达停报前的最后一帧已收到、还没处理。按平台时钟它已"停报 20 s"，但积压的帧可能正要命中它，不能先判失联；
        // 处理完之后再按那一帧的到达时刻起算。
        Instant t0 = Instant.parse("2026-10-06T15:00:00Z");
        double lon = 118.65, lat = 37.65;
        Instant lastReceived = report("ZT20B-R1", t0, 4, lon, lat, 0);
        String target = targetOf("ZT20B-R1");
        Instant backlogReceived = lastReceived.plusSeconds(1);
        String pending = enqueue(RADAR, t0.plusSeconds(4), backlogReceived, "ZT20B-R1", lon, lat);

        SweepReport held = sweeper.sweep(lastReceived.plusSeconds(20));
        assertThat(held.reference()).as("以最早一条没处理完的帧为界").isEqualTo(backlogReceived);
        assertThat(status(target)).isEqualTo("STABLE");

        process(pending, RADAR, t0.plusSeconds(4), backlogReceived, "ZT20B-R1", lon, lat);
        assertThat(sweeper.sweep(backlogReceived.plusMillis(3_400)).aged()).isZero();
        assertThat(sweeper.sweep(backlogReceived.plusMillis(3_600)).aged()).isEqualTo(1);
        assertThat(status(target)).isEqualTo("SHORT_LOST");
    }

    @Test
    void targetsLastHeardBeforeTheHorizonAndTargetsBeingWrittenAreLeftAlone() throws Exception {
        // horizon（这里 1 小时）以外收到过数据的目标不动：回放数据集的到达时刻在回放时钟上，是几周前的固定时刻。
        Instant t0 = Instant.parse("2026-10-06T18:00:00Z");
        Instant oldReceived = report("ZT20H-R1", t0, 3, 118.75, 37.75, 0);
        String old = targetOf("ZT20H-R1");
        Instant now = oldReceived.plusSeconds(3_700);
        assertThat(sweeper.sweep(now).candidates()).isZero();
        assertThat(status(old)).isEqualTo("STABLE");

        // 状态行正被别的事务锁着（融合正在写它）：这一轮跳过，不等锁；锁放开后下一轮照常推进。
        Instant recentReceived = report("ZT20H-R2", now.minusSeconds(10), 3, 118.76, 37.76, 0);
        String busy = targetOf("ZT20H-R2");
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread holder = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("select target_id from target_track_status where target_id=? for update", busy);
            locked.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }));
        holder.start();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(sweeper.sweep(recentReceived.plusMillis(3_600)).aged()).isZero();
        } finally {
            release.countDown();
            holder.join(10_000);
        }
        assertThat(status(busy)).isEqualTo("STABLE");
        assertThat(sweeper.sweep(recentReceived.plusMillis(3_600)).aged()).isEqualTo(1);
        assertThat(status(busy)).isEqualTo("SHORT_LOST");
    }

    /** 这台雷达报 frames 帧（1 s 一帧，设备时钟慢 slowSeconds 秒，平台收到比报文晚 0.2 s），返回最后一帧的到达时刻。 */
    private Instant report(String externalId, Instant t0, int frames, double lon, double lat, long slowSeconds) {
        Instant received = null;
        for (int k = 0; k < frames; k++) {
            Instant now = t0.plusSeconds(k);
            received = now.plusMillis(200);
            frame(RADAR, now.minusSeconds(slowSeconds), received, externalId, lon, lat + 5.0 * k / METERS_PER_DEG_LAT);
        }
        return received;
    }

    private String status(String targetId) {
        return jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, targetId);
    }

    private String targetOf(String externalTargetId) {
        List<String> ids = jdbc.queryForList("select target_id from target_source_link where external_target_id=?", String.class, externalTargetId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static Instant instant(Object value) {
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        throw new IllegalStateException("unexpected timestamp type " + (value == null ? null : value.getClass()));
    }

    /** H2 把 CAST(文本 AS JSON) 存成 JSON 字符串，读回来要多解一层；PostgreSQL 上就是对象本身。 */
    private JsonNode jsonOf(String value) {
        try {
            JsonNode node = json.readTree(value);
            return node.isTextual() ? json.readTree(node.asText()) : node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 写一条凌云 SenseData 进 inbox，并像 FusionIngestWorker 那样在一个事务里处理并置 DONE。 */
    private void frame(String sourceId, Instant observedAt, Instant receivedAt, String externalId, double lon, double lat) {
        process(enqueue(sourceId, observedAt, receivedAt, externalId, lon, lat), sourceId, observedAt, receivedAt, externalId, lon, lat);
    }

    /** 只写进 inbox（RECEIVED），不处理：模拟已收到、还在排队的帧。 */
    private String enqueue(String sourceId, Instant observedAt, Instant receivedAt, String externalId, double lon, double lat) {
        String inboxId = UUID.randomUUID().toString();
        jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status,fusion_attempts)"
                + " values (?,?,?,?,?,?,cast(? as json),'RECEIVED',0)", inboxId, "lingyun:radar:" + sourceId, inboxId, receivedAt.toEpochMilli(), sourceId,
                "0".repeat(64), body(sourceId, observedAt, externalId, lon, lat));
        return inboxId;
    }

    private void process(String inboxId, String sourceId, Instant observedAt, Instant receivedAt, String externalId, double lon, double lat) {
        InboxRow row = new InboxRow(inboxId, "lingyun:radar:" + sourceId, inboxId, sourceId, receivedAt.toEpochMilli(), body(sourceId, observedAt, externalId, lon, lat));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            pipeline.processFrame(row);
            inbox.done(inboxId, receivedAt.toEpochMilli());
        });
    }

    private final Map<String, String> bodies = new LinkedHashMap<>();

    /** 同一帧的报文写进 inbox 与交给管线的必须是同一份（msgCnt 只在第一次生成时递增）。 */
    private String body(String sourceId, Instant observedAt, String externalId, double lon, double lat) {
        return bodies.computeIfAbsent(sourceId + "|" + observedAt + "|" + externalId + "|" + lon + "|" + lat, key -> {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("objectId", externalId);
            object.put("time", observedAt.toEpochMilli());
            object.put("longitude", lon);
            object.put("latitude", lat);
            object.put("height", 80);
            object.put("extension", Map.of("objectType", UAV));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("deviceId", sourceId);
            payload.put("msgCnt", msgCnt++);
            payload.put("ptTime", observedAt.toEpochMilli());
            payload.put("objects", new ArrayList<>(List.of(object)));
            try { return json.writeValueAsString(payload); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        });
    }
}
