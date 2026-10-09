package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunHandle;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunSummary;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;

/**
 * 飞手距离端到端（新-29，2026-10-08 法规核对，确认书 2-10）：与 RuleEngineWorker 每个 tick 相同的入口 RuleRunService.start + runBatch（SCHEDULED），
 * 经真实的 C01 计划匹配、C02 检查、C03 四态、证据充分性、钩子与 C06 合并，落到 rule_evaluation、alarm、uav_event。
 * 超视距不再算违规、不出告警：飞手离得远（超过 vlos_m）只在 C02-6 明细里提示“飞手离无人机约 N 米（超过 500 米），是否经批准请核实”，
 * 没有遥控器位置这一项不判；10-07 那条“只有超视距时判非法、低风险告警”取消。
 * 规则集用种子里已发布并生效的 LEGALITY-DEMO 版本：没有 severity.BVLOS_EXCEEDED、vlos_m=500、ignore_undetermined_rules=C02-6，
 * 不重新发布、不改参数——验证"已发布版本不重发也按新口径判"。
 * 只有空间事实（空域命中、到航线距离）按目标给桩：H2 没有 PostGIS，PostgreSQL 版（PilotDistanceNoteFlowPostgresTest）用同一个桩，两库结论逐项可比。
 * 评估时刻固定在北京时间 10:00，夜航窗口（20–6 时）不会随运行时刻混进结论。机构/区域、目标、计划都是本用例自建，测试事务结束即回滚。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PilotDistanceNoteFlowTest {
    /** 2026-10-06 10:00 Asia/Shanghai。 */
    static final OffsetDateTime TICK = OffsetDateTime.of(2026, 10, 6, 2, 0, 0, 0, ZoneOffset.UTC);
    private static final String LONGITUDE = "118.40", LATITUDE = "37.40";
    /** 飞手相对目标的正北纬度差：大圆距离 = 6371008.8 m × Δ × π/180。 */
    private static final String NORTH_499_M = "0.0044876", NORTH_501_M = "0.0045056", NORTH_800_M = "0.0071946", NORTH_3000_M = "0.0269796";
    private static final String DEMO_SUFFIX = "；参数为 DEMO 演示值，尚未确认";

    @Autowired RuleRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SpatialStub spatial;
    @Autowired TargetReadRepository targets;

    private String suffix, org, district;

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean @Primary SpatialStub pilotDistanceSpatialStub() { return new SpatialStub(); }
    }

    /** 默认：不在任何空域内，离本机航线中心线 5 m（走廊半宽 50 m）；登记过的目标在禁飞区内或偏离航线 100 m。 */
    static class SpatialStub implements SpatialFactPort {
        final Set<String> inProhibitedAirspace = ConcurrentHashMap.newKeySet();
        final Set<String> offRoute = ConcurrentHashMap.newKeySet();
        @Override public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf) {
            if (state == null || !inProhibitedAirspace.contains(state.targetId())) return List.of();
            return List.of(new AirspaceHit("pilot-airspace", "pilot-airspace-version", "PROHIBITED", "COVERS", null, null, null, TICK.minusDays(1), null, null));
        }
        @Override public RouteDistance distanceToRoute(TargetState state, String routeVersionId) {
            boolean off = state != null && offRoute.contains(state.targetId());
            return new RouteDistance(routeVersionId, new BigDecimal(off ? "100" : "5"), new BigDecimal("50"), null);
        }
        @Override public boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime asOf) { return false; }
    }

    @BeforeEach
    void fixture() {
        spatial.inProhibitedAirspace.clear();
        spatial.offRoute.clear();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "pilot-org-" + suffix; district = "pilot-dist-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "PILOT-" + suffix, "飞手距离测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "PILOT-" + suffix, "飞手距离测试区域");
    }

    /** 有任务的无人机按航线飞、遥控器放到 501 / 800 / 3000 米外：研判合法、不出告警，C02-6 明细写那句提示（带取整后的距离）。 */
    @Test
    void farPilotOnAFiledFlightStaysLegalWithANoteAndNoAlarm() throws Exception {
        String m501 = uav("501", true), m800 = uav("800", true), m3000 = uav("3000", true);
        pilotNorth(m501, NORTH_501_M); pilotNorth(m800, NORTH_800_M); pilotNorth(m3000, NORTH_3000_M);
        RunHandle run = tick(m501, m800, m3000);
        assertPublishedVersionWithoutBvlosSeverity(run.ruleSetVersionId());

        for (Map.Entry<String, String> expected : Map.of(m501, "501", m800, "800", m3000, "3000").entrySet()) {
            String target = expected.getKey();
            Map<String, Object> evaluation = evaluation(run, target);
            assertThat(evaluation.get("legal_status")).as(target).isEqualTo("LEGAL");
            assertThat(evaluation.get("plan_match_code")).isEqualTo("FULL");
            assertThat(evaluation.get("grade")).isNull();
            assertThat(strings(evaluation.get("violation_reasons"))).isEmpty();
            assertThat(strings(evaluation.get("unknown_reasons"))).isEmpty();
            assertThat(evaluation.get("decision_assurance_code")).isEqualTo("SUFFICIENT");
            assertThat(evaluation.get("alarm_id")).isNull();
            JsonNode c026 = check(evaluation.get("hit_details"), "C02-6");
            String note = "飞手离无人机约 " + expected.getValue() + " 米（超过 500 米），是否经批准请核实";
            assertThat(c026.path("result_code").asText()).isEqualTo("PASS");
            assertThat(c026.path("facts").path("beyond_vlos").asBoolean()).isTrue();
            assertThat(c026.path("facts").path("pilot_distance_note").asText()).isEqualTo(note);
            assertThat(c026.path("message").asText()).isEqualTo(note + DEMO_SUFFIX);
            assertThat(alarms(target)).isEmpty();
            // 目标详情、告警详情读的目标摘要：只把带这句的研判明细取回来（两库都走 CAST(... AS VARCHAR) LIKE 粗筛）。
            assertThat(targets.summaries(List.of(target)).get(target).legality().pilotNoteHitsJson()).as(target).contains(note);
        }
        assertThat(jdbc.queryForObject("select alarm_created_count from rule_run where run_id=?", Integer.class, run.runId())).isZero();
    }

    @Test
    void withinTheThresholdOrWithoutAPilotStaysLegalWithoutANote() throws Exception {
        String near = uav("499", true), noPilot = uav("nopilot", true);
        pilotNorth(near, NORTH_499_M);
        RunHandle run = tick(near, noPilot);

        Map<String, Object> inside = evaluation(run, near);
        assertThat(inside.get("legal_status")).isEqualTo("LEGAL");
        assertThat(inside.get("grade")).isNull();
        assertThat(strings(inside.get("violation_reasons"))).isEmpty();
        JsonNode within = check(inside.get("hit_details"), "C02-6");
        assertThat(within.path("result_code").asText()).isEqualTo("PASS");
        assertThat(within.path("facts").path("distance_m").asDouble()).isBetween(498.5, 500.0);
        assertThat(within.path("facts").has("pilot_distance_note")).isFalse();

        // 没有遥控器位置：这一项不判（NOT_APPLICABLE），不进未知原因，结论照常 LEGAL，不告警。
        Map<String, Object> missing = evaluation(run, noPilot);
        assertThat(missing.get("legal_status")).isEqualTo("LEGAL");
        assertThat(strings(missing.get("unknown_reasons"))).isEmpty();
        JsonNode unmeasured = check(missing.get("hit_details"), "C02-6");
        assertThat(unmeasured.path("result_code").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(unmeasured.path("reason_code").asText()).isEqualTo("PILOT_POSITION_UNAVAILABLE");
        assertThat(unmeasured.path("facts").has("pilot_distance_note")).isFalse();
        assertThat(missing.get("decision_assurance_code")).isEqualTo("SUFFICIENT");

        for (Map<String, Object> evaluation : List.of(inside, missing)) assertThat(evaluation.get("alarm_id")).isNull();
        for (String target : List.of(near, noPilot)) assertThat(targets.summaries(List.of(target)).get(target).legality().pilotNoteHitsJson()).as(target).isNull();
        assertThat(alarms(near)).isEmpty();
        assertThat(alarms(noPilot)).isEmpty();
        assertThat(jdbc.queryForObject("select alarm_created_count from rule_run where run_id=?", Integer.class, run.runId())).isZero();
    }

    /**
     * 飞手离得远又有别的违规：结论、等级、分数、违规原因和告警等级都与"同样情形但没有遥控器位置"的目标逐项一致，违规原因里没有超视距，
     * 只是 C02-6 明细多了那句提示。三组分别是 禁飞区+无计划（HIGH）、无计划（MEDIUM）、偏离航线（LOW）。
     */
    @Test
    void farPilotNextToOtherViolationsChangesNothingButTheNote() throws Exception {
        String prohibited = uav("proh", false), prohibitedFar = uav("proh-f", false);
        String noPlan = uav("nopl", false), noPlanFar = uav("nopl-f", false);
        String deviation = uav("dev", true), deviationFar = uav("dev-f", true);
        spatial.inProhibitedAirspace.addAll(List.of(prohibited, prohibitedFar));
        spatial.offRoute.addAll(List.of(deviation, deviationFar));
        for (String target : List.of(prohibitedFar, noPlanFar, deviationFar)) pilotNorth(target, NORTH_800_M);
        RunHandle run = tick(prohibited, prohibitedFar, noPlan, noPlanFar, deviation, deviationFar);

        String[][] pairs = {{prohibited, prohibitedFar, "HIGH"}, {noPlan, noPlanFar, "MEDIUM"}, {deviation, deviationFar, "LOW"}};
        for (String[] pair : pairs) {
            Map<String, Object> without = evaluation(run, pair[0]), with = evaluation(run, pair[1]);
            assertThat(without.get("legal_status")).as(pair[0]).isEqualTo("ILLEGAL");
            assertThat(with.get("legal_status")).as(pair[1]).isEqualTo("ILLEGAL");
            assertThat(without.get("grade")).as(pair[0]).isEqualTo(pair[2]);
            assertThat(with.get("grade")).as(pair[1]).isEqualTo(pair[2]);
            assertThat((BigDecimal) with.get("score")).as(pair[1]).isEqualByComparingTo((BigDecimal) without.get("score"));
            assertThat(strings(with.get("violation_reasons"))).as(pair[1]).containsExactlyInAnyOrderElementsOf(strings(without.get("violation_reasons")));
            assertThat(check(with.get("hit_details"), "C02-6").path("facts").path("pilot_distance_note").asText())
                    .isEqualTo("飞手离无人机约 800 米（超过 500 米），是否经批准请核实");
            assertThat(alarms(pair[0])).hasSize(1);
            assertThat(alarms(pair[1])).hasSize(1);
            assertThat(alarms(pair[0]).get(0).get("severity")).isEqualTo(pair[2]);
            assertThat(alarms(pair[1]).get(0).get("severity")).as("告警等级 %s", pair[1]).isEqualTo(pair[2]);
            assertThat(strings(node(alarms(pair[1]).get(0).get("detail")).path("violation_reasons"))).doesNotContain("BVLOS_EXCEEDED");
        }
        assertThat(strings(evaluation(run, prohibited).get("violation_reasons"))).containsExactlyInAnyOrder("NO_AUTHORIZATION", "INSIDE_RESTRICTED_AIRSPACE");
        assertThat(strings(evaluation(run, noPlan).get("violation_reasons"))).containsExactly("NO_AUTHORIZATION");
        assertThat(strings(evaluation(run, deviation).get("violation_reasons"))).containsExactly("ROUTE_DEVIATION");
    }

    /** 与 RuleEngineWorker 的 tick 相同：起 SCHEDULED 运行，按主体逐个研判（每主体一个保存点），再收尾、自动关闭到期合并组。 */
    private RunHandle tick(String... targets) {
        RunHandle run = runs.start(LocalStage7RuleEngineSeeder.RULE_SET_CODE, RunMode.ACTIVE, LegalityEvaluationService.TRIGGER_SCHEDULED, null, null, TICK);
        RunSummary summary = runs.runBatch(run, Arrays.stream(targets).map(id -> new Subject(SubjectKind.TARGET, id, null, null, null)).toList(), TICK);
        assertThat(summary.errors()).isEmpty();
        assertThat(summary.evaluatedCount()).isEqualTo(targets.length);
        return run;
    }

    private void assertPublishedVersionWithoutBvlosSeverity(String versionId) {
        assertThat(jdbc.queryForObject("select status_code from rule_set_version where rule_set_version_id=?", String.class, versionId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select count(*) from rule_param where rule_set_version_id=? and rule_code='C03' and param_key='severity.BVLOS_EXCEEDED'",
                Integer.class, versionId)).isZero();
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C02-6' and param_key='vlos_m'",
                String.class, versionId)).isEqualTo("500");
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C03' and param_key='ignore_undetermined_rules'",
                String.class, versionId)).isEqualTo("C02-6");
    }

    /**
     * 目标（编号唯一、状态与 5 个轨迹点都在 TICK 前 10–30 s）；withPlan 时另建本机计划（航线高度带 10–120 m AMSL，时段覆盖 TICK），目标飞在 80 m AMSL / 离地 60 m。
     * 没有计划的目标飞在离地 150 m：120 米以下的普通区域飞行按规定无需申请（新-28），这里要验的是"没有任务 → 无飞行授权"。name 不超过 7 个字符（主键 36 位）。
     */
    private String uav(String name, boolean withPlan) {
        String key = name + "-" + suffix, target = "pilot-target-" + key, sn = "PILOT-SN-" + key, link = "pilot-link-" + key, track = "pilot-track-" + key;
        String point = "SRID=4326;POINT(" + LONGITUDE + " " + LATITUDE + ")";
        OffsetDateTime observed = TICK.minusSeconds(10), created = TICK.minusMinutes(5);
        String amsl = withPlan ? "80" : "170", agl = withPlan ? "60" : "150";
        jdbc.update("insert into target (target_id,target_no,object_type_code,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'UAV',?,?,?,'mock',?,?,?,?,0)", target, "PILOT-T-" + key, sn, ts(created), ts(observed), org, district, ts(created), ts(observed));
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,?,?,?,?)",
                link, target, LocalStage7RuleEngineSeeder.SOURCE_ID, "s-" + key, "x-" + key, ts(created));
        jdbc.update("insert into target_latest_state (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,"
                + "observed_at,received_at,created_at,updated_at,version) values (?,CAST(? AS GEOMETRY),?,?,10,90,0.9,0.95,?,?,?,?,0)",
                target, point, new BigDecimal(amsl), new BigDecimal(agl), ts(observed), ts(observed), ts(created), ts(observed));
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at) values (?,?,?,?,?,?)", track, target, link, "tr-" + key, ts(created), ts(created));
        for (int i = 0; i < 5; i++) {
            Timestamp seen = ts(observed.minusSeconds((4 - i) * 5L));
            jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at)"
                    + " values (?,?,?,?,?,CAST(? AS GEOMETRY),?,?,?)", "pilot-pt-" + key + "-" + i, track, i, seen, seen, point, new BigDecimal(amsl), new BigDecimal(agl), seen);
        }
        if (withPlan) {
            String route = "pilot-route-" + key, routeVersion = "pilot-rv-" + key, plan = "pilot-plan-" + key;
            jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                    + " values (?,?,?,true,?,'mock',?,?,?,?,0)", route, "PILOT-R-" + key, "飞手距离测试航线", LocalStage7RuleEngineSeeder.SOURCE_ID, org, district, ts(created), ts(created));
            jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                    + " values (?,?,1,CAST(? AS GEOMETRY),100,10,120,'AMSL',?,?)", routeVersion, route,
                    "SRID=4326;LINESTRING(" + LONGITUDE + " " + LATITUDE + ",118.41 37.41)", ts(TICK.minusHours(2)), ts(created));
            jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                    + " values (?,?,'PENDING',?,'mock',?,?,?,?,?,?,?,?,0)", plan, "PILOT-P-" + key, LocalStage7RuleEngineSeeder.SOURCE_ID, sn,
                    ts(TICK.minusMinutes(30)), ts(TICK.plusMinutes(30)), routeVersion, org, district, ts(created), ts(created));
        }
        return target;
    }

    private void pilotNorth(String target, String degrees) {
        String latitude = new BigDecimal(LATITUDE).add(new BigDecimal(degrees)).toPlainString();
        jdbc.update("update target_latest_state set pilot_location=CAST(? AS GEOMETRY),pilot_observed_at=? where target_id=?",
                "SRID=4326;POINT(" + LONGITUDE + " " + latitude + ")", ts(TICK.minusSeconds(10)), target);
    }

    private Map<String, Object> evaluation(RunHandle run, String target) {
        return jdbc.queryForMap("select evaluation_id,legal_status,plan_match_code,grade,score,violation_reasons,unknown_reasons,hit_details,alarm_id,alarm_outcome,"
                + "decision_assurance_code from rule_evaluation where run_id=? and target_id=?", run.runId(), target);
    }

    private List<Map<String, Object>> alarms(String target) {
        return jdbc.queryForList("select alarm_id,alarm_type,severity,detail from alarm where target_id=?", target);
    }

    private JsonNode check(Object hitDetails, String ruleCode) throws Exception {
        for (JsonNode hit : node(hitDetails)) if (ruleCode.equals(hit.path("rule_code").asText())) return hit;
        throw new AssertionError("研判明细里没有 " + ruleCode + "：" + text(hitDetails));
    }

    private List<String> strings(Object stored) throws Exception { return strings(node(stored)); }

    private static List<String> strings(JsonNode array) {
        assertThat(array.isArray()).as("应为数组：%s", array).isTrue();
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    /** H2 的 JSON 列经 getObject 返回 UTF-8 字节数组，PostgreSQL 返回 PGobject；都归一为文本再解析（兼容被二次编码成字符串的旧行）。 */
    private JsonNode node(Object stored) throws Exception {
        JsonNode node = json.readTree(text(stored));
        return node.isTextual() ? json.readTree(node.textValue()) : node;
    }

    private static String text(Object stored) {
        return stored instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(stored);
    }

    private static Timestamp ts(OffsetDateTime value) { return Timestamp.from(value.toInstant()); }
}
