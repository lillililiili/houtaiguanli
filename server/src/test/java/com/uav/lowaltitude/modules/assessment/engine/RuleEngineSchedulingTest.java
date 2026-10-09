package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Freshness;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.LegalStatus;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks.EvaluationOutcome;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks.HookResult;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;

/**
 * 定时评估取数（ZT-06/ZT-20）：只决定"这一轮评谁、先评谁"，不改任何判定规则。
 * 新目标（建档 fast-window 内）每轮都评；老目标同版本 reevaluate 窗口内评过就等下一轮；从没评过的排最前；
 * 报文时刻过期但刚收到的目标也要评一次，留下 STALE/NOT_APPLICABLE 的研判写明不判的原因，而不是悄悄跳过；
 * 最新一帧是失联帧的目标不评，结论保持最后一次真实观测的研判。
 * 夹具沿用 LegalityEvaluationServiceTest 的做法（桩空间事实/计划匹配/钩子，直接插目标与规则集）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RuleEngineSchedulingTest {
    private static final int FRESH_SECONDS = 120, FAST_WINDOW_SECONDS = 30, REEVALUATE_MILLIS = 5000;

    @Autowired LegalityEvaluationService service;
    @Autowired RuleRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleEngineRepository repository;
    @Autowired RecordingHooks hooks;
    @Autowired TargetReadRepository targets;

    private String code, versionId, suffix;
    private OffsetDateTime now;

    @TestConfiguration
    static class Stubs {
        @Bean @Primary SpatialFactPort schedulingSpatialFacts() {
            return new SpatialFactPort() {
                @Override public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf) { return List.of(); }
                @Override public RouteDistance distanceToRoute(TargetState state, String routeVersionId) { return new RouteDistance(routeVersionId, new BigDecimal("5"), new BigDecimal("50"), null); }
                @Override public boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime asOf) { return false; }
            };
        }
        @Bean @Primary RecordingHooks schedulingHooks() { return new RecordingHooks(); }
        @Bean @Primary PlanMatcher schedulingPlanMatcher() {
            return (EvaluationContext context, List<PlanFact> candidates, RuleParams params) -> new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        }
    }

    static class RecordingHooks implements RuleEngineHooks {
        final List<EvaluationOutcome> outcomes = new ArrayList<>();
        @Override public HookResult afterEvaluation(EvaluationOutcome outcome) { outcomes.add(outcome); return HookResult.none(); }
    }

    @BeforeEach
    void fixture() {
        hooks.outcomes.clear();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        code = "ZT06-RS-" + suffix;
        now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        versionId = ruleSet(ts(now.minusHours(1)));
    }

    @Test
    void youngTargetsAreReevaluatedEveryTickOlderOnesAtMostOncePerWindow() {
        String young = target("young", now.minusSeconds(10), now.minusSeconds(2), now.minusSeconds(2));
        String old = target("old", now.minusMinutes(10), now.minusSeconds(2), now.minusSeconds(2));
        evaluate(young, now.minusSeconds(1));
        evaluate(old, now.minusSeconds(1));
        // 两个目标都有了新数据（最新状态比上一次研判新），正常口径下都该再评。
        advance(young, now.minusSeconds(1));
        advance(old, now.minusSeconds(1));
        assertThat(scheduled(now)).as("老目标 5 s 内评过：本轮跳过；新目标照评").contains(young).doesNotContain(old);
        // 研判行的 evaluated_at 是评估当时的真实时刻（略晚于夹具的 now），再过一个重评窗口老目标也评。
        assertThat(scheduled(now.plusSeconds(10))).as("过了重评窗口老目标也评").contains(young, old);
        // 旧口径（手动/回放等非定时调用）不节流。
        assertThat(repository.pendingSubjects(RunMode.ACTIVE, versionId, now.minusSeconds(FRESH_SECONDS), 1000))
                .extracting(Subject::subjectId).contains(young, old);
    }

    @Test
    void neverEvaluatedTargetsComeFirst() {
        String evaluated = target("evaluated", now.minusMinutes(10), now.minusSeconds(20), now.minusSeconds(20));
        evaluate(evaluated, now.minusSeconds(19));
        advance(evaluated, now.minusSeconds(10));
        // 新出现的目标最新状态更晚（按更新时间排它会在后面），但从没评过就排在前面。
        String fresh = target("fresh", now.minusSeconds(3), now.minusSeconds(1), now.minusSeconds(1));
        List<String> order = scheduled(now.plusSeconds(10)).stream().filter(id -> id.equals(evaluated) || id.equals(fresh)).toList();
        assertThat(order).containsExactly(fresh, evaluated);
    }

    @Test
    void staleObservationThatJustArrivedIsEvaluatedAsStaleInsteadOfSkipped() {
        // TC-EXT-017：设备时钟慢 10 分钟，报文时刻早已过了新鲜窗口，但平台 20 s 前建档、刚刚收到最新一帧。
        String lagging = target("lagging", now.minusSeconds(20), now.minusMinutes(10), now.minusSeconds(1));
        assertThat(repository.pendingSubjects(RunMode.ACTIVE, versionId, now.minusSeconds(FRESH_SECONDS), 1000))
                .as("旧口径只看报文时刻：悄悄跳过").extracting(Subject::subjectId).doesNotContain(lagging);
        assertThat(scheduled(now)).as("定时口径：刚收到就评一次").contains(lagging);

        var result = evaluate(lagging, now);
        assertThat(result.freshness()).isEqualTo(Freshness.STALE);
        assertThat(result.legalStatus()).as("过期位置不判合法性").isEqualTo(LegalStatus.NOT_APPLICABLE);
        assertThat(result.unknownReasons()).contains(RuleCodes.STATE_STALE);
        assertThat(hooks.outcomes).hasSize(1);
        assertThat(hooks.outcomes.get(0).alarmEligible()).as("不产生告警").isFalse();
        // 同一份状态评过一次就不再重复评。
        assertThat(scheduled(now.plusSeconds(10))).doesNotContain(lagging);
    }

    @Test
    void lossFrameIsNotEvaluatedSoTheLastRealVerdictStands() {
        // 第四次复测：一台设备整体停报约 5 秒后，目标研判从"违规"变成"不可判定（置信度不足）"，它的告警还是"违规"。
        String lost = target("lost", now.minusMinutes(10), now.minusSeconds(3), now.minusSeconds(3));
        degradation(lost, "THREE_SOURCE", "0", true);
        assertThat(evaluate(lost, now.minusSeconds(2)).legalStatus()).as("真实观测：无计划判违规").isEqualTo(LegalStatus.ILLEGAL);
        // 停报前最后一帧真实数据落在重评窗口里，还没轮到评；
        advance(lost, now.minusSeconds(1));
        // 随后融合层写下失联帧：观测时刻不变，位置留在最后一个点，高度速度清空，置信度调低到门槛以下。
        lossFrame(lost, now);
        assertThat(scheduled(now.plusSeconds(10))).as("失联帧不研判").doesNotContain(lost);
        assertThat(repository.pendingSubjects(RunMode.ACTIVE, versionId, now.minusSeconds(FRESH_SECONDS), 1000))
                .extracting(Subject::subjectId).as("非定时口径同样不研判").doesNotContain(lost);

        // 取数时还是真实观测、轮到它时已是失联帧：不研判、不写行，批量运行记作跳过而不是失败。
        int before = evaluationCount(lost);
        assertThat(evaluate(lost, now.plusSeconds(10))).isNull();
        var run = runs.start(code, RunMode.ACTIVE, LegalityEvaluationService.TRIGGER_SCHEDULED, null, null, now.plusSeconds(10));
        var summary = runs.runBatch(run, List.of(new Subject(SubjectKind.TARGET, lost, null, null, null)), now.plusSeconds(10));
        assertThat(summary.evaluatedCount()).isZero();
        assertThat(summary.errors()).isEmpty();
        assertThat(evaluationCount(lost)).isEqualTo(before);
        assertThat(targets.summaries(List.of(lost)).get(lost).legality().legalStatus())
                .as("目标摘要仍是最后一次真实观测的结论，与告警一致").isEqualTo("ILLEGAL");

        // 来源回来、有了新的观测：照常研判。
        degradation(lost, "THREE_SOURCE", "0", true);
        jdbc.update("update target_latest_state set fusion_confidence=0.95, altitude_amsl_m=170, height_agl_m=150 where target_id=?", lost);
        advance(lost, now.plusSeconds(11));
        assertThat(scheduled(now.plusSeconds(20))).contains(lost);
        assertThat(evaluate(lost, now.plusSeconds(20)).legalStatus()).isEqualTo(LegalStatus.ILLEGAL);
    }

    private List<String> scheduled(OffsetDateTime tick) {
        return repository.pendingSubjects(RunMode.ACTIVE, versionId, tick.minusSeconds(FRESH_SECONDS), tick.minusSeconds(FAST_WINDOW_SECONDS),
                tick.minusNanos(REEVALUATE_MILLIS * 1_000_000L), 1000).stream().map(Subject::subjectId).toList();
    }

    /** 与 RuleEngineWorker 同形：SCHEDULED 运行，新鲜度按评估时点判断。 */
    private LegalityEvaluationService.EvaluationResult evaluate(String targetId, OffsetDateTime at) {
        var run = runs.start(code, RunMode.ACTIVE, LegalityEvaluationService.TRIGGER_SCHEDULED, null, null, at);
        return service.evaluate(new Subject(SubjectKind.TARGET, targetId, null, null, null), RunMode.ACTIVE, at, run.runId());
    }

    private void advance(String targetId, OffsetDateTime observed) {
        jdbc.update("update target_latest_state set observed_at=?, received_at=?, updated_at=? where target_id=?", ts(observed), ts(observed), ts(observed), targetId);
    }

    /** 融合层的失联帧（DefaultFusedLayerWriter 无位置帧）：观测时刻不变，位置不动，高度速度清空，置信度调低，降级等级 NONE。 */
    private void lossFrame(String targetId, OffsetDateTime received) {
        jdbc.update("update target_latest_state set fusion_confidence=0.7, altitude_amsl_m=null, height_agl_m=null, speed_mps=null, heading_deg=null,"
                + " received_at=?, updated_at=? where target_id=?", ts(received), ts(received), targetId);
        degradation(targetId, "NONE", "0.3", true);
    }

    private void degradation(String targetId, String level, String deficit, boolean determined) {
        jdbc.update("delete from target_degradation where target_id=?", targetId);
        jdbc.update("insert into target_degradation (target_id,level,available_source_ids,confidence_deficit,determined,since,updated_at) values (?,?,CAST(? AS JSON),?,?,?,?)",
                targetId, level, "NONE".equals(level) ? "[]" : "[\"seed-stage3-source\"]", new BigDecimal(deficit), determined, ts(now), ts(now));
    }

    private int evaluationCount(String targetId) {
        return jdbc.queryForObject("select count(*) from rule_evaluation where target_id=?", Integer.class, targetId);
    }

    /**
     * created 是平台建档时刻（target.created_at）；first/last_seen 是报文时刻，设备时钟慢时两者可以差很远。
     * 目标没有报备任务，离地 150 米：高于 120 米，按规定要申请（新-28），无计划照旧判违规。
     */
    private String target(String name, OffsetDateTime created, OffsetDateTime observed, OffsetDateTime received) {
        String id = "zt06-" + name + "-" + suffix, link = "zt06-link-" + name + "-" + suffix, track = "zt06-track-" + name + "-" + suffix;
        OffsetDateTime firstSeen = created.isBefore(observed.minusSeconds(8)) ? created : observed.minusSeconds(8);
        jdbc.update("insert into target (target_id,target_no,object_type_code,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'UAV',?,?,?,'mock','seed-stage3-org','seed-stage3-district',?,?,0)", id, "ZT06-" + name + "-" + suffix, "ZT06-SN-" + suffix, ts(firstSeen), ts(observed), ts(created), ts(received));
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,'seed-stage3-source',?,?,?)",
                link, id, "s-" + name + "-" + suffix, "x-" + name + "-" + suffix, ts(created));
        jdbc.update("insert into target_latest_state (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,observed_at,received_at,created_at,updated_at,version)"
                + " values (?,CAST('SRID=4326;POINT(118.025 37.025)' AS GEOMETRY),170,150,10,90,0.9,0.95,?,?,?,?,0)", id, ts(observed), ts(received), ts(created), ts(received));
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at) values (?,?,?,?,?,?)", track, id, link, "tr-" + name + "-" + suffix, ts(firstSeen), ts(created));
        for (int i = 0; i < 5; i++) {
            Timestamp seen = ts(observed.minusSeconds((4 - i) * 2L));
            jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at)"
                    + " values (?,?,?,?,?,CAST('SRID=4326;POINT(118.025 37.025)' AS GEOMETRY),170,150,?)", "zt06-pt-" + name + "-" + suffix + "-" + i, track, i, seen, seen, seen);
        }
        return id;
    }

    /** LEGALITY 规则集 v1 PUBLISHED+ACTIVE（成员与参数同 LegalityEvaluationServiceTest 的 DEMO 目录，新鲜窗口 120 s）。 */
    private String ruleSet(Timestamp at) {
        String setId = "zt06-rs-" + suffix, version = "zt06-rsv-" + suffix;
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,version,created_at,updated_at) values (?,?,?,0,?,?)", setId, code, "调度测试规则集", at, at);
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at)"
                + " values (?,?,1,'PUBLISHED','DEMO',?,?,'mock',?,?)", version, setId, at, "v1", at, at);
        jdbc.update("update rule_set set active_version_id=? where rule_set_id=?", version, setId);
        String[] codes = {"C01", "C02-1", "C02-2", "C02-3", "C02-4", "C02-5", "C02-6", "C02-7", "C02-8", "C03", "C06"};
        int[] priorities = {100, 210, 220, 230, 240, 250, 260, 270, 280, 300, 400};
        for (int i = 0; i < codes.length; i++) {
            String ruleVersion = "zt06-rv-" + codes[i] + "-" + suffix;
            jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,created_at) values (?,?,?,?,?,?,?)",
                    ruleVersion, codes[i], ThreadLocalRandom.current().nextInt(100_000, 999_999), "ACTIVE", at, "mock", at);
            jdbc.update("insert into rule_set_member (rule_set_version_id,rule_version_id,priority,enabled) values (?,?,?,true)", version, ruleVersion, priorities[i]);
        }
        String[][] params = {
                {"C01", "time_window_min", "10", "INTEGER"}, {"C01", "corridor_tolerance_m", "20", "NUMBER"},
                {"C02-1", "kinds", "PROHIBITED,RESTRICTED", "LIST"}, {"C02-2", "kinds", "ALTITUDE_LIMIT", "LIST"},
                {"C02-3", "tolerance_m", "20", "NUMBER"}, {"C02-4", "grace_min", "10", "INTEGER"},
                {"C02-5", "timezone", "Asia/Shanghai", "STRING"}, {"C02-5", "night_from", "24", "INTEGER"}, {"C02-5", "night_to", "0", "INTEGER"},
                {"C02-6", "vlos_m", "500", "NUMBER"}, {"C02-8", "kinds", "TEMPORARY_CONTROL", "LIST"},
                {"C03", "fresh_seconds", Integer.toString(FRESH_SECONDS), "INTEGER"}, {"C03", "track_points", "10", "INTEGER"}, {"C03", "conf_min", "0.75", "NUMBER"},
                {"C03", "min_points", "3", "INTEGER"}, {"C03", "gap_seconds", "30", "INTEGER"}, {"C03", "no_plan_status", "ILLEGAL", "STRING"},
                {"C03", "ignore_undetermined_rules", "C02-6", "LIST"},
                {"C03", "w.violation", "0.40", "NUMBER"}, {"C03", "w.plan_match", "0.25", "NUMBER"}, {"C03", "w.airspace", "0.15", "NUMBER"},
                {"C03", "w.track", "0.10", "NUMBER"}, {"C03", "w.confidence", "0.10", "NUMBER"},
                {"C03", "severity.INSIDE_RESTRICTED_AIRSPACE", "1.0", "NUMBER"}, {"C03", "severity.AIRSPACE_ALTITUDE_EXCEEDED", "0.9", "NUMBER"},
                {"C03", "severity.TEMPORARY_RESTRICTION_ACTIVE", "0.9", "NUMBER"}, {"C03", "severity.NO_AUTHORIZATION", "0.8", "NUMBER"},
                {"C03", "severity.ROUTE_DEVIATION", "0.6", "NUMBER"}, {"C03", "severity.PLAN_ALTITUDE_EXCEEDED", "0.5", "NUMBER"},
                {"C03", "severity.TIME_WINDOW_OVERRUN", "0.4", "NUMBER"}, {"C03", "severity.NIGHT_FLIGHT", "0.3", "NUMBER"},
                {"C03", "grade.high", "67", "NUMBER"}, {"C03", "grade.medium", "34", "NUMBER"},
                {"C06", "dedup_window_min", "5", "INTEGER"}, {"C06", "upgrade_window_min", "10", "INTEGER"}, {"C06", "auto_close_min", "15", "INTEGER"},
                {"C06", "severity_by_grade", "HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW", "LIST"}};
        for (String[] param : params) {
            jdbc.update("insert into rule_param (rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,param_status) values (?,?,?,?,?,?,'DEMO')",
                    UUID.randomUUID().toString(), version, param[0], param[1], param[2], param[3]);
        }
        return version;
    }

    private static Timestamp ts(OffsetDateTime value) { return Timestamp.from(Instant.from(value)); }
}
