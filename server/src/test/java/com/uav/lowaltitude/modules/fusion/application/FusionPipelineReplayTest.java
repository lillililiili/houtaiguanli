package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusedLayerWriter;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TargetFrameResult;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;

/**
 * 六场景端到端（H2 + 记录型 FusedLayerWriter 桩）：三源同见、单源缺失、交叉、分裂合并、迟到乱序、精度差异。
 * 空间事实全部来自 Java 侧 Haversine/ENU（域层不依赖 SQL 几何），因此 H2 与 PostGIS 得到同一套关联结果。
 */
@SpringBootTest(properties = {
        "app.dev-seed.enabled=true", "app.fusion.enabled=false", "app.fusion.replay.run-on-start=false",
        "spring.datasource.url=jdbc:h2:mem:stage8_pipeline;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class FusionPipelineReplayTest {

    /** 记录型融合层写入器：E2 落地前用它证明 E1 把正确的 TargetFrameResult 交了出去。 */
    static final List<TargetFrameResult> FRAMES = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class RecordingWriterConfig {
        /** @Primary：E2 的 DefaultFusedLayerWriter 已是 @Component，两个候选会让 ObjectProvider 抛 NoUniqueBeanDefinition。
         *  本用例只验证 E1 交给融合层的 TargetFrameResult，因此用记录型桩顶替真实写入器。 */
        @Bean
        @Primary
        FusedLayerWriter recordingFusedLayerWriter() { return FRAMES::add; }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FusionInboxRepository inbox;
    @Autowired FusionPipeline pipeline;

    /**
     * 回放只跑一次并把结果留在 FRAMES 里：种子已在上下文启动时摄取过一遍，这里把 inbox 复位后由本测试自己驱动管线，
     * 以便断言 E1 交给融合层的每一帧。不在每个用例前重跑，避免用例之间互相影响。
     */
    @BeforeEach
    void replayOnce() {
        if (!FRAMES.isEmpty()) return;
        jdbc.update("delete from track_point"); jdbc.update("delete from track"); jdbc.update("delete from source_observation");
        jdbc.update("delete from target_current_alias"); jdbc.update("delete from target_lineage"); jdbc.update("delete from target_track_status");
        jdbc.update("delete from target_source_link"); jdbc.update("delete from target where unified=true");
        // 阶段 8.5：种子摄取的是直连报文（lingyun: / eo-edge:），复位条件跟着换，否则这里一帧都放不回去。
        jdbc.update("update inbox_message set status='RECEIVED', processed_at=null, last_error=null, lease_token=null, lease_until=null"
                + " where source like 'lingyun:%' or source like 'eo-edge:%' or source like 'live-radar:%'");
        drain();
    }

    /** 逐帧驱动管线：一帧一次 processFrame，与 Worker 的调用形状一致（这里不套事务，成功即提交）。 */
    private void drain() {
        for (int round = 0; round < 1000; round++) {
            List<FusionInboxRepository.InboxRow> rows = inbox.claim(System.currentTimeMillis(), 50, 30_000L);
            if (rows.isEmpty()) return;
            for (FusionInboxRepository.InboxRow row : rows) { pipeline.processFrame(row); inbox.done(row.inboxId(), System.currentTimeMillis()); }
        }
    }

    @Test
    void threeSourcesOnOneTargetProduceOneTargetThreeLinksAndThreeEstimates() {
        String targetId = targetByExternal("R-T1");
        assertThat(targetId).isNotNull();
        // 三路来源同见：一个目标、三条 link，同一时刻的一组帧里三个来源各出现一次。
        assertThat(jdbc.queryForObject("select count(*) from target_source_link where target_id=?", Long.class, targetId)).isEqualTo(3L);
        assertThat(targetByExternal("D-T1")).isEqualTo(targetId);
        assertThat(targetByExternal("E-T1")).isEqualTo(targetId);
        List<TargetFrameResult> frames = framesOf(targetId);
        assertThat(frames).isNotEmpty();
        assertThat(frames).anySatisfy(frame -> assertThat(frame.estimates()).hasSize(1));
        List<String> sourceTypes = new ArrayList<>();
        for (TargetFrameResult frame : frames) for (SourceEstimate estimate : frame.estimates()) sourceTypes.add(estimate.sourceType());
        assertThat(sourceTypes).contains("RADAR", "TDOA", "EO");
        // 每条估计都带滤波后的精度与 schema_status，缺精度时标 accuracy_defaulted。
        assertThat(frames.get(0).estimates().get(0).accuracyM()).isNotNull().isPositive();
        assertThat(frames.get(0).estimates().get(0).schemaStatus()).isIn("CONFIRMED", "DEMO");
        assertThat(frames.get(0).configVersion()).isEqualTo("demo-v1");
    }

    @Test
    void missingSingleSourceKeepsTheSameTargetIdentity() {
        String targetId = targetByExternal("R-T1");
        // single-missing 场景：TDOA 空窗 6 帧，雷达/光电继续；目标不得因此换 ID 或新建。
        long radarLinks = jdbc.queryForObject("select count(*) from target_source_link l join integration_source s on s.source_id=l.source_id"
                + " where l.target_id=? and s.source_code=?", Long.class, targetId, FusionReplayDatasetGenerator.RADAR);
        assertThat(radarLinks).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(distinct target_id) from target_source_link where external_target_id in ('R-T1','D-T1','E-T1')", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, targetId)).isIn("TENTATIVE", "STABLE", "SHORT_LOST", "TERMINATED");
    }

    @Test
    void crossingTargetsKeepSeparateIdentities() {
        String a = targetByExternal("R-A"), b = targetByExternal("R-B");
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        // 交叉：两条航迹最小间距约 40 m，仍必须是两个目标，不能在交叉点互换或合成一个。
        assertThat(a).isNotEqualTo(b);
        assertThat(jdbc.queryForObject("select count(*) from target_source_link where external_target_id='R-A'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from target_source_link where external_target_id='R-B'", Long.class)).isEqualTo(1L);
    }

    /**
     * 决策 16-1：两个已稳定的目标收敛到 2σ 内并保持 ≥ merge_min_frames 帧后，系统自动合并。
     *
     * 断言站在"外面能看到什么"这一侧：旧 id 还解析得到（历史外键不能断）、被并者状态是 MERGE、
     * 血缘是 SYSTEM 的一行 MERGE、事件发了 MERGED。**目标版本号不许动**——系统写入不与人工写入争版本，
     * 否则用户正在编辑时乐观锁会被后台随机打断。
     */
    @Test
    void convergeMergeScenarioLeavesOneTargetWithSystemMergeLineage() {
        // 合并由系统发起、没有操作人，所以先从血缘认出幸存者与被并者，再看"外面能看到什么"。
        Map<String, Object> lineage = jdbc.queryForMap(
                "select survivor_target_id, CAST(member_target_ids AS VARCHAR) as members from target_lineage"
                + " where op='MERGE' and operator_kind='SYSTEM'");
        String survivor = (String) lineage.get("survivor_target_id");
        String loser = String.valueOf(lineage.get("members")).replaceAll("[^0-9a-fA-F-]", "");
        assertThat(loser).as("被并者").hasSize(36).isNotEqualTo(survivor);

        // 两个回波最终都落到幸存者身上——"同一架出现两次"在页面上消失了，这正是本决策要的效果。
        assertThat(targetByExternal("R-CV1")).isEqualTo(survivor);
        assertThat(targetByExternal("R-CV2")).isEqualTo(survivor);

        // 旧 id 必须还解析得到：告警、事件、风险、交接里存的都是旧 id，断了就是历史数据打不开。
        assertThat(jdbc.queryForObject("select current_target_id from target_current_alias where historical_target_id=?",
                String.class, loser)).isEqualTo(survivor);
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, loser))
                .isEqualTo("MERGE");
        assertThat(jdbc.queryForObject("select count(*) from fusion_event where event_type='MERGED' and target_id=?",
                Long.class, survivor)).isPositive();

        Map<String, Object> pending = jdbc.queryForMap(
                "select frames_seen, resolution from association_pending where reason='MANY_TO_ONE'");
        assertThat(pending.get("resolution")).isEqualTo("MERGED");
        // 恰好 merge_min_frames 帧才动手：早一帧就并说明门限没起作用，晚了说明计数被谁重置过。
        assertThat(((Number) pending.get("frames_seen")).intValue()).isEqualTo(4);

        // 系统合并不碰版本号（决策 16-1 / 8-6）：否则用户正在编辑时乐观锁会被后台随机打断。
        assertThat(jdbc.queryForObject("select version from target where target_id=?", Long.class, survivor)).isZero();
    }

    @Test
    void splitScenarioKeepsTheOriginIdAndRecordsSystemSplitLineage() {
        String origin = targetByExternal("R-M1"), child = targetByExternal("R-M2");
        assertThat(origin).isNotNull();
        assertThat(child).isNotNull();
        // 决策 16-2：原目标**保留 ID 继续存活**，不按契约原文"终止原目标 + 两个新 ID"——
        // 态势页上一直在跟的目标突然换号，比多出一个目标更难解释。
        assertThat(origin).isNotEqualTo(child);
        assertThat(jdbc.queryForObject("select count(*) from target where target_id=?", Long.class, origin)).isEqualTo(1L);

        // 创建当时那行 CREATE 留着不动（血缘只增），达阈后**另补**一行 SPLIT 指回原目标。
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where op='CREATE' and survivor_target_id in (?,?)",
                Long.class, origin, child)).isEqualTo(2L);
        Map<String, Object> split = jdbc.queryForMap("select origin_target_id, operator_kind"
                + " from target_lineage where op='SPLIT' and survivor_target_id=?", child);
        assertThat(split.get("origin_target_id")).as("分裂血缘要指回原目标").isEqualTo(origin);
        assertThat(split.get("operator_kind")).isEqualTo("SYSTEM");
        assertThat(jdbc.queryForObject("select count(*) from fusion_event where event_type='SPLIT' and target_id=?",
                Long.class, child)).isPositive();

        // 决策 16-2 修订（审查 P1-1）：原目标的 target_track_status **不许**被写成 SPLIT。
        // SPLIT 对引擎是终态（IdentityStateMachine.TrackState.terminal()），而原目标自己的回波还在，
        // 写了它就不再被跟踪——分裂出去一个新目标，不该让原来那个当场"死"掉。
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, origin))
                .as("原目标状态").isNotEqualTo("SPLIT");

        Map<String, Object> pending = jdbc.queryForMap(
                "select frames_seen, resolution from association_pending where pending_key=?",
                "ONE_TO_MANY|" + origin + "," + child);
        assertThat(pending.get("resolution")).isEqualTo("SPLIT");
        // 帧数与间距要同时够：只够帧数就分，抖一下就多一个目标。
        assertThat(((Number) pending.get("frames_seen")).intValue()).isGreaterThanOrEqualTo(4);
    }

    /**
     * 决策 16-2 的反面：同源第二回波一直只隔 40 m，够不上 `split_min_separation_m`(100)。
     * 这种"贴着飞"的回波不该被判成分裂——计到 `pending_expire_frames` 就该以 EXPIRED 收场，
     * 而不是一直挂着占位、或者凑够帧数后偷偷分出一个目标。
     */
    @Test
    void nearEchoThatNeverSeparatesEnoughExpiresInsteadOfSplitting() {
        String origin = targetByExternal("R-NE1"), child = targetByExternal("R-NE2");
        assertThat(origin).isNotNull();
        assertThat(child).isNotNull();
        assertThat(jdbc.queryForObject("select resolution from association_pending where reason='ONE_TO_MANY'"
                + " and pending_key=?", String.class, "ONE_TO_MANY|" + origin + "," + child)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where op='SPLIT' and survivor_target_id=?",
                Long.class, child)).as("间距不够就不许分裂").isZero();
    }

    @Test
    void lateFrameIsStoredInRawLayerWithItsOwnObservedTime() {
        String targetId = targetByExternal("E-L");
        assertThat(targetId).isNotNull();
        Long lateObservations = jdbc.queryForObject("select count(*) from source_observation o join integration_source s on s.source_id=o.source_id"
                + " where s.source_code=? and o.external_target_id='E-L'", Long.class, FusionReplayDatasetGenerator.EO);
        assertThat(lateObservations).isPositive();
        // 迟到帧照写原始层，其 observed_at 早于该目标当时的最新观测；是否回退融合结果由 E2 按 observedAt 决定。
        Long earlier = jdbc.queryForObject("select count(*) from source_observation o where o.external_target_id='E-L'"
                + " and o.observed_at < (select max(o2.observed_at) from source_observation o2 where o2.external_target_id='R-L')", Long.class);
        assertThat(earlier).isPositive();
        assertThat(framesOf(targetId)).isNotEmpty();
        List<TargetFrameResult> frames = framesOf(targetId);
        assertThat(frames).allSatisfy(frame -> assertThat(frame.observedAt()).isNotNull());
    }

    @Test
    void accuracyGapIsCarriedThroughToEstimates() {
        String targetId = targetByExternal("R-G");
        assertThat(targetId).isNotNull();
        List<Double> accuracies = new ArrayList<>();
        for (TargetFrameResult frame : framesOf(targetId)) for (SourceEstimate estimate : frame.estimates()) accuracies.add(estimate.accuracyM());
        assertThat(accuracies).isNotEmpty();
        // 雷达 15 m 与 TDOA 60 m 的差异必须原样传给融合层，不能被抹平成同一个数。
        assertThat(accuracies.stream().anyMatch(a -> a != null && a <= 20)).isTrue();
        assertThat(accuracies.stream().anyMatch(a -> a != null && a >= 50)).isTrue();
        // 阶段 8.5：凌云协议里没有精度字段，观测的 position_accuracy_m 为空，精度由 fusion_config 的缺省值补上并标记，
        // 数值与阶段 8 显式给的一致（TDOA 60 m），所以上面的估计精度断言不变。
        Long tdoaWithoutAccuracy = jdbc.queryForObject("select count(*) from source_observation o join integration_source s on s.source_id=o.source_id"
                + " where s.source_code=? and o.position_accuracy_m is null", Long.class, FusionReplayDatasetGenerator.TDOA);
        assertThat(tdoaWithoutAccuracy).isPositive();
    }

    @Test
    void tdoaPilotPositionReachesTheObservationRow() {
        String targetId = targetByExternal("D-PILOT");
        assertThat(targetId).isNotNull();
        // 飞手位置必须真的落进 pilot_location：C02-6 超视距要拿它和目标位置算距离，写不进去这条规则就永远不可判定。
        Long withPilot = jdbc.queryForObject("select count(*) from source_observation where external_target_id='D-PILOT' and pilot_location is not null", Long.class);
        assertThat(withPilot).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='D-PILOT' and class_source='SENSE_DATA'", Long.class)).isPositive();
        assertThat(jdbc.queryForObject("select identity_clue from source_observation where external_target_id='D-PILOT' fetch first 1 rows only", String.class))
                .isEqualTo("SN-PILOT-01");
    }

    @Test
    void aoaKeepsIdentityCluesWithoutContributingAPosition() {
        Long withoutPosition = jdbc.queryForObject("select count(*) from source_observation where external_target_id='A-BEARING' and location is null", Long.class);
        assertThat(withoutPosition).isPositive();
        // 报文里带着经纬度，但协议说它无效：一条都不许落进 location，否则关联会拿假位置当证据。
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='A-BEARING' and location is not null", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='A-BEARING' and identity_clue='SN-PILOT-01'", Long.class)).isPositive();
        // 没有位置的观测不写测量点，但方位要留在 quality 里。
        assertThat(jdbc.queryForObject("select count(*) from track_point p join track t on t.track_id=p.track_id"
                + " join target_source_link l on l.link_id=t.link_id where l.external_target_id='A-BEARING'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select cast(quality as varchar) from source_observation where external_target_id='A-BEARING' fetch first 1 rows only", String.class))
                .contains("bearing_deg");
    }

    @Test
    void opticalOnlyHasObservationsWhileItIsTracking() {
        Long observations = jdbc.queryForObject("select count(*) from source_observation where external_target_id='T-EO-TRACK'", Long.class);
        // 第 4–8 帧在跟踪，其余是心跳：心跳不产生观测，否则会凭空造出"目标一直可见"。
        assertThat(observations).isEqualTo(5L);
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='T-EO-TRACK' and class_source='EO_TRACKING' and class_code='UAV'", Long.class))
                .isEqualTo(5L);
        // 心跳帧照样处理完（DONE），不是失败。
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like 'eo-edge:%' and status='FAILED'", Long.class)).isZero();
    }

    @Test
    void identifyingTargetsHaveNoClassCode() {
        Long identifying = jdbc.queryForObject("select count(*) from source_observation where external_target_id='R-IDENT'", Long.class);
        assertThat(identifying).isPositive();
        // 255 是"还没认出来"：给了 class_code，页面就会把它显示成一个确定的类型。
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='R-IDENT' and class_code is not null", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select cast(quality as varchar) from source_observation where external_target_id='R-IDENT' fetch first 1 rows only", String.class))
                .contains("identifying");
    }

    @Test
    void idleSenseDataFrameIsDoneWithoutAnyObservation() {
        // 协议 A 的空 objects 是"本帧什么都没探到"，与协议 C 的心跳同一性质：
        // 不产生观测，但 inbox 必须走到 DONE。判成 FAILED 会累计 fusion_attempts，
        // 空闲周期一多就把一台正常设备的帧推到重试上限（决策 10-16）。
        String sourceId = jdbc.queryForObject("select source_id from integration_source where source_code=?"
                , String.class, FusionReplayDatasetGenerator.RADAR);
        String inboxId = UUID.randomUUID().toString();
        long observedAt = System.currentTimeMillis();
        String payload = "{\"deviceId\":\"IDLE\",\"msgCnt\":7,\"ptTime\":" + observedAt + ",\"objects\":[]}";
        jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status,fusion_attempts)"
                + " values (?,?,?,?,?,?,cast(? as json),'RECEIVED',0)",
                inboxId, "lingyun:radar:IDLE", "idle-" + inboxId, observedAt, sourceId, "0".repeat(64), payload);

        long observationsBefore = count("source_observation");
        pipeline.processFrame(new FusionInboxRepository.InboxRow(inboxId, "lingyun:radar:IDLE", "idle", sourceId, observedAt, payload));
        inbox.done(inboxId, System.currentTimeMillis());

        assertThat(jdbc.queryForObject("select status from inbox_message where inbox_id=?", String.class, inboxId)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select fusion_attempts from inbox_message where inbox_id=?", Integer.class, inboxId)).isZero();
        assertThat(count("source_observation")).as("空帧不该产生任何观测").isEqualTo(observationsBefore);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void failedFrameRollsBackWholeFrameAndMarksInboxFailed() {
        // 构造一条坏帧：source_id 指向不存在的来源 → 整帧失败，不得留下任何观测。
        String inboxId = UUID.randomUUID().toString();
        String badSource = UUID.randomUUID().toString(); // source_id 是 VARCHAR(36)，夹具不能自造更长的 ID
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " values (?,?,?,false,'replay',current_timestamp,current_timestamp,0)", badSource, "BAD-" + UUID.randomUUID().toString().substring(0, 8), "停用来源");
        jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status)"
                + " values (?,?,?,?,?,?,cast(? as json),'RECEIVED')", inboxId, "replay:BAD:ds", "bad-1", System.currentTimeMillis(), badSource,
                "0".repeat(64), "{\"dataset_id\":\"ds\",\"record_no\":1,\"received_at\":1,\"frame\":{\"source_code\":\"BAD\",\"observed_at\":1,\"items\":[{\"external_target_id\":\"X\",\"lon\":118.6,\"lat\":37.4}]}}");
        long observationsBefore = jdbc.queryForObject("select count(*) from source_observation", Long.class);
        try {
            List<FusionInboxRepository.InboxRow> rows = inbox.claim(System.currentTimeMillis(), 50, 30_000L);
            FusionInboxRepository.InboxRow row = rows.stream().filter(r -> r.inboxId().equals(inboxId)).findFirst().orElseThrow();
            try { pipeline.processFrame(row); }
            catch (RuntimeException expected) { inbox.fail(row.inboxId(), System.currentTimeMillis(), expected.getMessage()); }
            assertThat(jdbc.queryForObject("select status from inbox_message where inbox_id=?", String.class, inboxId)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select last_error from inbox_message where inbox_id=?", String.class, inboxId)).isNotBlank();
            assertThat(jdbc.queryForObject("select count(*) from source_observation", Long.class)).isEqualTo(observationsBefore);
            assertThat(jdbc.queryForObject("select count(*) from source_observation where inbox_id=?", Long.class, inboxId)).isZero();
        } finally {
            // 坏帧夹具自己清理：留下的行会被其他用例的 drain 领走并再次抛异常。
            jdbc.update("delete from inbox_message where inbox_id=?", inboxId);
            jdbc.update("delete from integration_source where source_id=?", badSource);
        }
    }

    private String targetByExternal(String externalTargetId) {
        List<String> ids = jdbc.queryForList("select target_id from target_source_link where external_target_id=?", String.class, externalTargetId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<TargetFrameResult> framesOf(String targetId) {
        return FRAMES.stream().filter(frame -> frame.targetId().equals(targetId)).toList();
    }
}
