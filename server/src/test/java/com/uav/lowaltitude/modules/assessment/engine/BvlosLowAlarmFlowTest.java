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

/**
 * 超视距告警端到端（2026-10-07 业务决定）：与 RuleEngineWorker 每个 tick 相同的入口 RuleRunService.start + runBatch（SCHEDULED），
 * 经真实的 C01 计划匹配、C02 检查、C03 四态、证据充分性、钩子与 C06 合并，落到 rule_evaluation、alarm、uav_event。
 * 规则集用种子里已发布并生效的 LEGALITY-DEMO 版本：没有 severity.BVLOS_EXCEEDED、vlos_m=500、ignore_undetermined_rules=C02-6，
 * 不重新发布、不改参数——验证"已发布版本不重发也按新口径判"。
 * 只有空间事实（空域命中、到航线距离）按目标给桩：H2 没有 PostGIS，PostgreSQL 版（BvlosLowAlarmFlowPostgresTest）用同一个桩，两库结论逐项可比。
 * 评估时刻固定在北京时间 10:00，夜航窗口（20–6 时）不会随运行时刻混进结论。机构/区域、目标、计划都是本用例自建，测试事务结束即回滚。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BvlosLowAlarmFlowTest {
    /** 2026-10-06 10:00 Asia/Shanghai。 */
    static final OffsetDateTime TICK = OffsetDateTime.of(2026, 10, 6, 2, 0, 0, 0, ZoneOffset.UTC);
    private static final String LONGITUDE = "118.40", LATITUDE = "37.40";
    /** 飞手相对目标的正北纬度差：大圆距离 = 6371008.8 m × Δ × π/180。 */
    private static final String NORTH_499_M = "0.0044876", NORTH_501_M = "0.0045056", NORTH_800_M = "0.0071946", NORTH_3000_M = "0.0269796";

    @Autowired RuleRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SpatialStub spatial;

    private String suffix, org, district;

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean @Primary SpatialStub bvlosSpatialStub() { return new SpatialStub(); }
    }

    /** 默认：不在任何空域内，离本机航线中心线 5 m（走廊半宽 50 m）；登记过的目标在禁飞区内或偏离航线 100 m。 */
    static class SpatialStub implements SpatialFactPort {
        final Set<String> inProhibitedAirspace = ConcurrentHashMap.newKeySet();
        final Set<String> offRoute = ConcurrentHashMap.newKeySet();
        @Override public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf) {
            if (state == null || !inProhibitedAirspace.contains(state.targetId())) return List.of();
            return List.of(new AirspaceHit("bvlos-airspace", "bvlos-airspace-version", "PROHIBITED", "COVERS", null, null, null, TICK.minusDays(1), null, null));
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
        org = "bvlos-org-" + suffix; district = "bvlos-dist-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "BVLOS-" + suffix, "超视距测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "BVLOS-" + suffix, "超视距测试区域");
    }

    @Test
    void bvlosAloneRaisesALowAlarmWhoseReasonQuotesTheDistance() throws Exception {
        String m501 = uav("501", true), m800 = uav("800", true), m3000 = uav("3000", true);
        pilotNorth(m501, NORTH_501_M); pilotNorth(m800, NORTH_800_M); pilotNorth(m3000, NORTH_3000_M);
        RunHandle run = tick(m501, m800, m3000);
        assertPublishedVersionWithoutBvlosSeverity(run.ruleSetVersionId());

        for (Map.Entry<String, String> expected : Map.of(m501, "501", m800, "800", m3000, "3000").entrySet()) {
            String target = expected.getKey();
            Map<String, Object> evaluation = evaluation(run, target);
            assertThat(evaluation.get("legal_status")).as(target).isEqualTo("ILLEGAL");
            assertThat(evaluation.get("plan_match_code")).isEqualTo("FULL");
            assertThat(evaluation.get("grade")).isEqualTo("LOW");
            assertThat(strings(evaluation.get("violation_reasons"))).containsExactly("BVLOS_EXCEEDED");
            assertThat(strings(evaluation.get("unknown_reasons"))).isEmpty();
            assertThat(evaluation.get("decision_assurance_code")).isEqualTo("SUFFICIENT");
            JsonNode c026 = check(evaluation.get("hit_details"), "C02-6");
            assertThat(c026.path("result_code").asText()).isEqualTo("FAIL");
            assertThat(c026.path("reason_code").asText()).isEqualTo("BVLOS_EXCEEDED");
            assertThat(c026.path("message").asText())
                    .isEqualTo("超视距飞行（飞手离无人机约 " + expected.getValue() + " 米，超过 500 米）；参数为 DEMO 演示值，尚未确认");

            // 告警走既有链路落库：等级低风险，告警明细带研判等级与原因码；同事务建待核实事件。
            List<Map<String, Object>> alarms = alarms(target);
            assertThat(alarms).hasSize(1);
            Map<String, Object> alarm = alarms.get(0);
            assertThat(alarm.get("alarm_id")).isEqualTo(evaluation.get("alarm_id"));
            assertThat(alarm.get("alarm_type")).isEqualTo("RULE_LEGALITY");
            assertThat(alarm.get("severity")).isEqualTo("LOW");
            JsonNode detail = node(alarm.get("detail"));
            assertThat(detail.path("legal_status").asText()).isEqualTo("ILLEGAL");
            assertThat(detail.path("grade").asText()).isEqualTo("LOW");
            assertThat(strings(detail.path("violation_reasons"))).containsExactly("BVLOS_EXCEEDED");
            assertThat(detail.path("merge_kind").asText()).isEqualTo("CREATED");
            JsonNode outcome = node(evaluation.get("alarm_outcome"));
            assertThat(outcome.path("kind").asText()).isEqualTo("CREATED");
            assertThat(outcome.path("severity").asText()).isEqualTo("LOW");
            assertThat(jdbc.queryForObject("select state_code from uav_event where alarm_id=?", String.class, alarm.get("alarm_id")))
                    .isEqualTo("PENDING_VERIFICATION");
        }
        assertThat(jdbc.queryForObject("select alarm_created_count from rule_run where run_id=?", Integer.class, run.runId())).isEqualTo(3);
    }

    @Test
    void withinTheThresholdOrWithoutAPilotStaysLegalAndRaisesNoAlarm() throws Exception {
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

        // 没有飞手位置：C02-6 未知但被 ignore_undetermined_rules 忽略，结论 LEGAL 而不是 UNDETERMINED，不告警。
        Map<String, Object> missing = evaluation(run, noPilot);
        assertThat(missing.get("legal_status")).isEqualTo("LEGAL");
        assertThat(strings(missing.get("unknown_reasons"))).containsExactly("PILOT_POSITION_UNAVAILABLE");
        assertThat(check(missing.get("hit_details"), "C02-6").path("reason_code").asText()).isEqualTo("PILOT_POSITION_UNAVAILABLE");
        assertThat(missing.get("decision_assurance_code")).isEqualTo("SUFFICIENT");

        for (Map<String, Object> evaluation : List.of(inside, missing)) assertThat(evaluation.get("alarm_id")).isNull();
        assertThat(alarms(near)).isEmpty();
        assertThat(alarms(noPilot)).isEmpty();
        assertThat(jdbc.queryForObject("select alarm_created_count from rule_run where run_id=?", Integer.class, run.runId())).isZero();
    }

    /**
     * 超视距与其他违规同时出现：等级、分数、告警等级都与"同样情形但没有超视距"的目标一致，高的不被压低、低的不被抬高；
     * 只是 violation_reasons 多一个 BVLOS_EXCEEDED。三组分别是 禁飞区+无计划（HIGH）、无计划（MEDIUM）、偏离航线（LOW）。
     */
    @Test
    void bvlosNextToOtherViolationsKeepsTheirLevel() throws Exception {
        String prohibited = uav("proh", false), prohibitedBvlos = uav("proh-b", false);
        String noPlan = uav("nopl", false), noPlanBvlos = uav("nopl-b", false);
        String deviation = uav("dev", true), deviationBvlos = uav("dev-b", true);
        spatial.inProhibitedAirspace.addAll(List.of(prohibited, prohibitedBvlos));
        spatial.offRoute.addAll(List.of(deviation, deviationBvlos));
        for (String target : List.of(prohibitedBvlos, noPlanBvlos, deviationBvlos)) pilotNorth(target, NORTH_800_M);
        RunHandle run = tick(prohibited, prohibitedBvlos, noPlan, noPlanBvlos, deviation, deviationBvlos);

        String[][] pairs = {{prohibited, prohibitedBvlos, "HIGH"}, {noPlan, noPlanBvlos, "MEDIUM"}, {deviation, deviationBvlos, "LOW"}};
        for (String[] pair : pairs) {
            Map<String, Object> without = evaluation(run, pair[0]), with = evaluation(run, pair[1]);
            assertThat(without.get("legal_status")).as(pair[0]).isEqualTo("ILLEGAL");
            assertThat(with.get("legal_status")).as(pair[1]).isEqualTo("ILLEGAL");
            assertThat(without.get("grade")).as(pair[0]).isEqualTo(pair[2]);
            assertThat(with.get("grade")).as(pair[1]).isEqualTo(pair[2]);
            assertThat((BigDecimal) with.get("score")).as(pair[1]).isEqualByComparingTo((BigDecimal) without.get("score"));
            List<String> expected = new ArrayList<>(strings(without.get("violation_reasons")));
            expected.add("BVLOS_EXCEEDED");
            assertThat(strings(with.get("violation_reasons"))).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(alarms(pair[0])).hasSize(1);
            assertThat(alarms(pair[1])).hasSize(1);
            assertThat(alarms(pair[0]).get(0).get("severity")).isEqualTo(pair[2]);
            assertThat(alarms(pair[1]).get(0).get("severity")).as("告警等级 %s", pair[1]).isEqualTo(pair[2]);
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
        String key = name + "-" + suffix, target = "bvlos-target-" + key, sn = "BVLOS-SN-" + key, link = "bvlos-link-" + key, track = "bvlos-track-" + key;
        String point = "SRID=4326;POINT(" + LONGITUDE + " " + LATITUDE + ")";
        OffsetDateTime observed = TICK.minusSeconds(10), created = TICK.minusMinutes(5);
        String amsl = withPlan ? "80" : "170", agl = withPlan ? "60" : "150";
        jdbc.update("insert into target (target_id,target_no,object_type_code,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'UAV',?,?,?,'mock',?,?,?,?,0)", target, "BVLOS-T-" + key, sn, ts(created), ts(observed), org, district, ts(created), ts(observed));
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,?,?,?,?)",
                link, target, LocalStage7RuleEngineSeeder.SOURCE_ID, "s-" + key, "x-" + key, ts(created));
        jdbc.update("insert into target_latest_state (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,"
                + "observed_at,received_at,created_at,updated_at,version) values (?,CAST(? AS GEOMETRY),?,?,10,90,0.9,0.95,?,?,?,?,0)",
                target, point, new BigDecimal(amsl), new BigDecimal(agl), ts(observed), ts(observed), ts(created), ts(observed));
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at) values (?,?,?,?,?,?)", track, target, link, "tr-" + key, ts(created), ts(created));
        for (int i = 0; i < 5; i++) {
            Timestamp seen = ts(observed.minusSeconds((4 - i) * 5L));
            jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at)"
                    + " values (?,?,?,?,?,CAST(? AS GEOMETRY),?,?,?)", "bvlos-pt-" + key + "-" + i, track, i, seen, seen, point, new BigDecimal(amsl), new BigDecimal(agl), seen);
        }
        if (withPlan) {
            String route = "bvlos-route-" + key, routeVersion = "bvlos-rv-" + key, plan = "bvlos-plan-" + key;
            jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                    + " values (?,?,?,true,?,'mock',?,?,?,?,0)", route, "BVLOS-R-" + key, "超视距测试航线", LocalStage7RuleEngineSeeder.SOURCE_ID, org, district, ts(created), ts(created));
            jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                    + " values (?,?,1,CAST(? AS GEOMETRY),100,10,120,'AMSL',?,?)", routeVersion, route,
                    "SRID=4326;LINESTRING(" + LONGITUDE + " " + LATITUDE + ",118.41 37.41)", ts(TICK.minusHours(2)), ts(created));
            jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                    + " values (?,?,'PENDING',?,'mock',?,?,?,?,?,?,?,?,0)", plan, "BVLOS-P-" + key, LocalStage7RuleEngineSeeder.SOURCE_ID, sn,
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
