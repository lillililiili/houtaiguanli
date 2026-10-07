package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.AssociationPendingRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 验收问题回归（真实管线 + 真实融合层写入器，H2）：
 * ZT-01 序列号不同的两架贴近飞行不并成一个目标，门限歧义的待定记录不再拖垮整帧；
 * ZT-04 融合类别变化（识别中→无人机→鸟）同步到目标头行并留改判记录；
 * ZT-20 设备时钟慢两分钟的数据照常入库，但最新状态写明报文时刻不可信。
 * 每个用例用自己的外部编号和一块远离回放种子的位置，彼此、与种子目标都不进同一个门限。
 */
@SpringBootTest(properties = {
        "app.dev-seed.enabled=true", "app.fusion.enabled=false", "app.fusion.replay.run-on-start=false",
        "spring.datasource.url=jdbc:h2:mem:fusion_identity_class_clock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class FusionIdentityClassAndClockTest {
    private static final String RADAR = "seed-stage8-source-radar", TDOA = "seed-stage8-source-tdoa";
    private static final double METERS_PER_DEG_LAT = 111_320.0;
    private static final int UAV = 30, BIRD = 40, IDENTIFYING = 255;

    @Autowired JdbcTemplate jdbc;
    @Autowired FusionPipeline pipeline;
    @Autowired FusionInboxRepository inbox;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AssociationPendingRepository pendings;

    private long msgCnt = 1;

    /** 一个凌云 SenseData 目标：objectType 为 null 时不报类别，serial 为 null 时不报序列号。 */
    private record Obj(String id, double lon, double lat, Integer objectType, String serial) { }

    @Test
    void twoDronesWithDifferentSerialsTwelveMetresApartStayTwoTargets() {
        // TC-FUS-002 / TC-EXT-001：雷达与 TDOA 同时看见两架，间距 12 m（雷达目标 σ≈10 m，自动合并门限 2σ≈20 m），
        // 同向飞 8 帧。两边序列号不同：关联不能交叉、自动合并不能把它们并掉，TDOA 的帧也不能因为待定记录失败。
        Instant t0 = Instant.parse("2026-10-06T01:00:00Z");
        double lon = 118.95, lat = 37.95, east12m = meters(12, lat);
        for (int k = 0; k < 8; k++) {
            Instant at = t0.plusSeconds(k);
            double north = lat + 5.0 * k / METERS_PER_DEG_LAT;
            frame("radar", RADAR, at, at.plusMillis(200),
                    new Obj("ZT01A-R1", lon, north, UAV, null), new Obj("ZT01A-R2", lon + east12m, north, UAV, null));
            frame("tdoa", TDOA, at.plusMillis(100), at.plusMillis(300),
                    new Obj("ZT01A-T1", lon, north, UAV, "zt01a-sn-a"), new Obj("ZT01A-T2", lon + east12m, north, UAV, "ZT01A-SN-B"));
        }
        String a = targetOf("ZT01A-T1"), b = targetOf("ZT01A-T2");
        assertThat(a).isNotNull().isNotEqualTo(b);
        assertThat(targetOf("ZT01A-R1")).as("SN-A 与离它最近的雷达目标在一起").isEqualTo(a);
        assertThat(targetOf("ZT01A-R2")).isEqualTo(b);
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where op='MERGE' and survivor_target_id in (?,?)", Long.class, a, b))
                .as("序列号不同不自动合并").isZero();
        assertThat(jdbc.queryForList("select status from target_track_status where target_id in (?,?)", String.class, a, b))
                .containsOnly("STABLE");

        // 门限歧义照常记待定，而且记在已经入库的观测上（外键 fk_stage8_pending_observation 曾让整帧失败）。
        Long pendingOnStoredObservations = jdbc.queryForObject("select count(*) from association_pending p join source_observation o on o.observation_id=p.observation_id"
                + " where p.reason='GATE_AMBIGUOUS' and o.external_target_id in ('ZT01A-T1','ZT01A-T2')", Long.class);
        assertThat(pendingOnStoredObservations).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id in ('ZT01A-T1','ZT01A-T2')", Long.class))
                .as("TDOA 每帧两条观测都在").isEqualTo(16L);
    }

    @Test
    void secondDroneSeenOnlyByTdoaIsNotPutOnTheNearbyDronesTarget() {
        // ZT-01 原样：第一架雷达 + TDOA（SN-A）稳定跟踪；第二架只有 TDOA 报出 SN-B，离第一架 40 m。
        // TDOA 缺省精度 60 m，单看位置它落在第一架的门限里；序列号不同必须另立目标。
        Instant t0 = Instant.parse("2026-10-06T02:00:00Z");
        double lon = 118.85, lat = 37.85, east40m = meters(40, lat);
        for (int k = 0; k < 4; k++) {
            Instant at = t0.plusSeconds(k);
            frame("radar", RADAR, at, at.plusMillis(200), new Obj("ZT01B-R1", lon, lat, UAV, null));
            frame("tdoa", TDOA, at.plusMillis(100), at.plusMillis(300), new Obj("ZT01B-T1", lon, lat, UAV, "ZT01B-SN-A"));
        }
        String first = targetOf("ZT01B-R1");
        assertThat(targetOf("ZT01B-T1")).isEqualTo(first);

        // 这一帧 TDOA 只报了第二架（第一架恰好漏报）：第一架的目标不被本帧占用，单看位置第二架就会落进去。
        Instant at = t0.plusSeconds(4);
        frame("radar", RADAR, at, at.plusMillis(200), new Obj("ZT01B-R1", lon, lat, UAV, null));
        frame("tdoa", TDOA, at.plusMillis(100), at.plusMillis(300), new Obj("ZT01B-T9", lon + east40m, lat, UAV, "ZT01B-SN-B"));
        String second = targetOf("ZT01B-T9");
        assertThat(second).as("SN-B 不进 SN-A 的目标").isNotNull().isNotEqualTo(first);

        // 同一个 TDOA 外部编号后来报出另一个序列号（来源把编号给了另一架）：旧 link 不能再沿用，按新观测重新关联，
        // 并在观测上记 identity_conflict。两个已有目标的序列号都对不上，只能另立目标。
        at = t0.plusSeconds(5);
        frame("radar", RADAR, at, at.plusMillis(200), new Obj("ZT01B-R1", lon, lat, UAV, null));
        frame("tdoa", TDOA, at.plusMillis(100), at.plusMillis(300), new Obj("ZT01B-T1", lon, lat, UAV, "ZT01B-SN-C"));
        String third = targetOf("ZT01B-T1");
        assertThat(third).isNotIn(first, second);
        assertThat(targetOf("ZT01B-R1")).as("雷达这一路仍在第一架身上").isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='ZT01B-T1'"
                + " and identity_clue='ZT01B-SN-C' and cast(quality as varchar) like '%identity_conflict%'", Long.class)).isEqualTo(1L);
    }

    @Test
    void aPendingRecordThatCannotBeWrittenDoesNotLoseTheFrame() {
        // ZT-01 第二半："一条待定记录写不进去不能让整帧失败"。在与 Worker 同形的整帧事务里让待定记录的写入抛错：
        // 观测、link、目标照常提交，只少这一条旁注。
        doThrow(new DataIntegrityViolationException("simulated pending failure"))
                .when(pendings).insert(anyString(), any(), anyString(), eq("GATE_AMBIGUOUS"), anyString(), any());
        Instant t0 = Instant.parse("2026-10-06T03:00:00Z");
        double lon = 118.75, lat = 37.75, east12m = meters(12, lat);
        frame("radar", RADAR, t0, t0.plusMillis(200), new Obj("ZT01C-R1", lon, lat, UAV, null), new Obj("ZT01C-R2", lon + east12m, lat, UAV, null));
        frame("tdoa", TDOA, t0.plusMillis(100), t0.plusMillis(300), new Obj("ZT01C-T1", lon + east12m / 2, lat, UAV, null));

        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='ZT01C-T1'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status from inbox_message where source='lingyun:tdoa:" + TDOA + "' and received_at=?",
                String.class, t0.plusMillis(300).toEpochMilli())).isEqualTo("DONE");
        assertThat(targetOf("ZT01C-T1")).as("歧义时仍按最优匹配挂到已有目标上").isIn(targetOf("ZT01C-R1"), targetOf("ZT01C-R2"));
        assertThat(jdbc.queryForObject("select count(*) from association_pending p join source_observation o on o.observation_id=p.observation_id"
                + " where o.external_target_id='ZT01C-T1'", Long.class)).isZero();
    }

    @Test
    void fusedClassChangesReachTheTargetHeaderWithASystemRecord() {
        // TC-EXT-003：雷达先报"识别中"，再报无人机，再报鸟。目标头行要跟着变（不递增 version），
        // 每次变化留一条 SYSTEM 的 CLASS_REVISION（何时、由什么改成什么）并发 CLASS_REVISED。
        Instant t0 = Instant.parse("2026-10-06T04:00:00Z");
        double lon = 118.65, lat = 37.65;
        for (int k = 0; k < 9; k++) {
            int objectType = k < 3 ? IDENTIFYING : (k < 6 ? UAV : BIRD);
            Instant at = t0.plusSeconds(k);
            frame("radar", RADAR, at, at.plusMillis(200), new Obj("ZT04-R1", lon, lat + 5.0 * k / METERS_PER_DEG_LAT, objectType, null));
            String target = targetOf("ZT04-R1");
            String header = jdbc.queryForObject("select object_type_code from target where target_id=?", String.class, target);
            if (k < 3) assertThat(header).as("frame " + k + " 识别中不是一个类别").isNull();
            else if (k < 6) assertThat(header).as("frame " + k).isEqualTo("UAV");
            else assertThat(header).as("frame " + k).isEqualTo("BIRD");
        }
        String target = targetOf("ZT04-R1");
        List<Map<String, Object>> revisions = jdbc.queryForList("select occurred_at, operator_kind, cast(basis as varchar) as basis from target_lineage"
                + " where survivor_target_id=? and op='CLASS_REVISION' order by occurred_at", target);
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0).get("operator_kind")).isEqualTo("SYSTEM");
        JsonNode first = jsonOf(revisions.get(0).get("basis")), second = jsonOf(revisions.get(1).get("basis"));
        assertThat(first.path("previous_class_code").asText()).as("识别中→无人机：原来没有类别").isEmpty();
        assertThat(first.path("new_class_code").asText()).isEqualTo("UAV");
        assertThat(second.path("previous_class_code").asText()).isEqualTo("UAV");
        assertThat(second.path("new_class_code").asText()).isEqualTo("BIRD");
        assertThat(second.path("reason").asText()).isEqualTo("FUSED_CLASS_CHANGED");
        assertThat(instant(revisions.get(1).get("occurred_at"))).as("改判时刻是第一次报鸟的那一帧").isEqualTo(t0.plusSeconds(6));
        assertThat(jdbc.queryForObject("select count(*) from fusion_event where target_id=? and event_type='CLASS_REVISED'", Long.class, target)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select version from target where target_id=?", Long.class, target)).as("系统写入不动版本号（决策 8-6）").isZero();
        assertThat(jdbc.queryForObject("select class_code from target_attribute_selection where target_id=?", String.class, target)).isEqualTo("BIRD");
    }

    @Test
    void manuallyRevisedClassIsNotOverwrittenByTheEngine() {
        Instant t0 = Instant.parse("2026-10-06T05:00:00Z");
        double lon = 118.55, lat = 37.55;
        frame("radar", RADAR, t0, t0.plusMillis(200), new Obj("ZT04M-R1", lon, lat, UAV, null));
        String target = targetOf("ZT04M-R1");
        // 人工修订过类别（manual_class_override）：引擎后续不再改头行，也不留系统改判记录。
        jdbc.update("update target set object_type_code='BALLOON' where target_id=?", target);
        jdbc.update("update target_attribute_selection set manual_class_override=true, class_code='BALLOON' where target_id=?", target);
        frame("radar", RADAR, t0.plusSeconds(1), t0.plusSeconds(1).plusMillis(200), new Obj("ZT04M-R1", lon, lat, BIRD, null));
        assertThat(jdbc.queryForObject("select object_type_code from target where target_id=?", String.class, target)).isEqualTo("BALLOON");
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where survivor_target_id=? and op='CLASS_REVISION'", Long.class, target))
                .as("建目标时已是无人机，之后只有人工结论：没有系统改判").isZero();
    }

    @Test
    void deviceClockTwoMinutesBehindIsMarkedInsteadOfLookingLive() {
        // TC-EXT-017：报文时刻比平台收到时晚 120 s。观测照常入库、目标照常更新，但观测与最新状态都写明时刻不可信，
        // 页面据此显示"数据过期/设备时间不准"；恢复正常的帧上来后提示消失。
        Instant t0 = Instant.parse("2026-10-06T06:00:00Z");
        double lon = 118.45, lat = 37.45;
        for (int k = 0; k < 3; k++) {
            Instant at = t0.plusSeconds(k);
            frame("radar", RADAR, at, at.plusSeconds(120), new Obj("ZT20-R1", lon, lat + 5.0 * k / METERS_PER_DEG_LAT, UAV, null));
        }
        String target = targetOf("ZT20-R1");
        List<JsonNode> qualities = jdbc.queryForList("select cast(quality as varchar) from source_observation where external_target_id='ZT20-R1'", String.class)
                .stream().map(this::jsonOf).toList();
        assertThat(qualities).hasSize(3).allSatisfy(quality -> {
            assertThat(quality.path("time_untrusted").asBoolean()).isTrue();
            assertThat(quality.path("arrival_lag_ms").asLong()).isEqualTo(120_000L);
        });
        assertThat(timeUntrustedIssue(target)).as("最新状态写明 observed_at 时刻不可信").isTrue();
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, target)).isEqualTo("STABLE");

        // 20 s 的迟到在 30 s 阈值以内，不算不可信；恢复正常后提示随最新状态一起消失。
        Instant at = t0.plusSeconds(3);
        frame("radar", RADAR, at, at.plusSeconds(20), new Obj("ZT20-R1", lon, lat + 15.0 / METERS_PER_DEG_LAT, UAV, null));
        assertThat(timeUntrustedIssue(target)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='ZT20-R1'"
                + " and cast(quality as varchar) like '%time_untrusted%'", Long.class)).isEqualTo(3L);
    }

    @Test
    void slowDeviceClockKeepsOneTargetWhileAnotherDeviceReportsOnTime() {
        // 复测 ZT-20：同一辖区里还有时钟正常的设备在报。拿它的报文时刻去比，慢钟雷达的目标一出现就"已有 120 s 没见"被终止，
        // 下一帧又新建一个（4 分钟 238 个只有一个点的目标）。失联要按平台收到数据的时刻判断：慢 120 s，以及慢 20 s
        // （在 30 s 可信阈值以内、但超过 15 s 的终止时长）都应始终是同一个目标；慢钟雷达真的停报后照常短失、终止。
        long[] slowCases = {120, 20};
        for (int c = 0; c < slowCases.length; c++) {
            long slow = slowCases[c];
            String radarId = "ZT20S" + slow + "-R1", tdoaId = "ZT20S" + slow + "-T1";
            Instant t0 = Instant.parse("2026-10-06T07:00:00Z").plusSeconds(600L * c);
            double lon = 118.35 - 0.05 * c, lat = 37.35;
            for (int k = 0; k < 12; k++) {
                Instant now = t0.plusSeconds(k);
                frame("tdoa", TDOA, now, now.plusMillis(300), new Obj(tdoaId, lon + 0.02, lat, UAV, "ZT20S-SN-T" + slow));
                frame("radar", RADAR, now.minusSeconds(slow), now.plusMillis(200),
                        new Obj(radarId, lon, lat + 5.0 * k / METERS_PER_DEG_LAT, UAV, "ZT20S-SN-R" + slow));
            }
            String target = targetOf(radarId);
            assertThat(created(radarId)).as("慢 " + slow + " s：12 帧始终是同一个目标").isEqualTo(1L);
            assertThat(trackStatus(target)).isEqualTo("STABLE");
            assertThat(instant(jdbc.queryForMap("select last_received_at from target_track_status where target_id=?", target).get("last_received_at")))
                    .as("记下最近一次命中时平台收到那一帧的时刻").isEqualTo(t0.plusSeconds(11).plusMillis(200));
            assertThat(timeUntrustedIssue(target)).as("慢 " + slow + " s 是否标时刻不可信").isEqualTo(slow * 1000 > 30_000);

            // 慢钟雷达停报，TDOA 照常报：按到达时刻过了 3 s 短失、过了 15 s 终止。
            for (int k = 12; k < 30; k++) {
                Instant now = t0.plusSeconds(k);
                frame("tdoa", TDOA, now, now.plusMillis(300), new Obj(tdoaId, lon + 0.02, lat, UAV, "ZT20S-SN-T" + slow));
                if (k == 13) assertThat(trackStatus(target)).as("停报 2.1 s").isEqualTo("STABLE");
                if (k == 14) assertThat(trackStatus(target)).as("停报 3.1 s").isEqualTo("SHORT_LOST");
            }
            assertThat(trackStatus(target)).as("停报 18.1 s").isEqualTo("TERMINATED");
            assertThat(trackStatus(targetOf(tdoaId))).as("时钟正常的那一路不受影响").isEqualTo("STABLE");
        }
    }

    @Test
    void lastReceivedTimeNeverMovesBackWhenFramesAreProcessedOutOfArrivalOrder() {
        // 几个调度线程并行处理，积压时晚到的帧可能先提交。雷达与 TDOA 看见同一架：TDOA 9.3 s 到的一帧先处理，
        // 雷达 3.2 s 到的一帧后处理、同样命中这个目标——"最近一次命中的到达时刻"仍是 9.3 s，不能退回 3.2 s。
        Instant t0 = Instant.parse("2026-10-06T08:00:00Z");
        double lon = 118.25, lat = 37.25;
        for (int k = 0; k < 3; k++) {
            Instant at = t0.plusSeconds(k);
            frame("radar", RADAR, at, at.plusMillis(200), new Obj("ZT20O-R1", lon, lat, UAV, null));
            frame("tdoa", TDOA, at.plusMillis(100), at.plusMillis(300), new Obj("ZT20O-T1", lon, lat, UAV, "ZT20O-SN"));
        }
        String target = targetOf("ZT20O-R1");
        assertThat(targetOf("ZT20O-T1")).isEqualTo(target);
        frame("tdoa", TDOA, t0.plusMillis(9100), t0.plusMillis(9300), new Obj("ZT20O-T1", lon, lat, UAV, "ZT20O-SN"));
        frame("radar", RADAR, t0.plusSeconds(3), t0.plusMillis(3200), new Obj("ZT20O-R1", lon, lat, UAV, null));
        assertThat(targetOf("ZT20O-R1")).as("雷达这一帧仍命中同一个目标").isEqualTo(target);
        assertThat(instant(jdbc.queryForMap("select last_received_at from target_track_status where target_id=?", target).get("last_received_at")))
                .isEqualTo(t0.plusMillis(9300));
    }

    /** 这个外部编号的观测一共建过几个目标：建目标时血缘 CREATE 的依据里记着外部编号。 */
    private long created(String externalTargetId) {
        return jdbc.queryForObject("select count(*) from target_lineage where op='CREATE' and cast(basis as varchar) like ?", Long.class,
                "%\"" + externalTargetId + "%");
    }

    private String trackStatus(String targetId) {
        return jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, targetId);
    }

    private static Instant instant(Object value) {
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        throw new IllegalStateException("unexpected timestamp type " + (value == null ? null : value.getClass()));
    }

    /** H2 把 CAST(文本 AS JSON) 存成 JSON 字符串，读回来要多解一层；PostgreSQL 上就是对象本身。 */
    private JsonNode jsonOf(Object value) {
        try {
            JsonNode node = json.readTree(String.valueOf(value));
            return node.isTextual() ? json.readTree(node.asText()) : node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private boolean timeUntrustedIssue(String targetId) {
        JsonNode unknown = jsonOf(jdbc.queryForObject("select cast(unknown_fields as varchar) from target_latest_state where target_id=?", String.class, targetId));
        for (JsonNode issue : unknown) {
            if ("observed_at".equals(issue.path("field").asText()) && "TIME_UNTRUSTED".equals(issue.path("reason_code").asText())) return true;
        }
        return false;
    }

    private String targetOf(String externalTargetId) {
        List<String> ids = jdbc.queryForList("select target_id from target_source_link where external_target_id=?", String.class, externalTargetId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static double meters(double east, double lat) {
        return east / (METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat)));
    }

    /** 写一条凌云 SenseData 进 inbox，并像 FusionIngestWorker 那样在一个事务里处理并置 DONE。 */
    private void frame(String deviceType, String sourceId, Instant observedAt, Instant receivedAt, Obj... objects) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Obj o : objects) {
            Map<String, Object> extension = new LinkedHashMap<>();
            if (o.objectType() != null) extension.put("objectType", o.objectType());
            if (o.serial() != null) extension.put("uavSN", o.serial());
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("objectId", o.id());
            object.put("time", observedAt.toEpochMilli());
            object.put("longitude", o.lon());
            object.put("latitude", o.lat());
            object.put("height", 80);
            object.put("extension", extension);
            list.add(object);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", sourceId);
        payload.put("msgCnt", msgCnt++);
        payload.put("ptTime", observedAt.toEpochMilli());
        payload.put("objects", list);
        String body;
        try { body = json.writeValueAsString(payload); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        String inboxId = UUID.randomUUID().toString();
        String source = "lingyun:" + deviceType + ":" + sourceId;
        jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status,fusion_attempts)"
                + " values (?,?,?,?,?,?,cast(? as json),'RECEIVED',0)", inboxId, source, inboxId, receivedAt.toEpochMilli(), sourceId, "0".repeat(64), body);
        InboxRow row = new InboxRow(inboxId, source, inboxId, sourceId, receivedAt.toEpochMilli(), body);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            pipeline.processFrame(row);
            inbox.done(inboxId, receivedAt.toEpochMilli());
        });
    }
}
