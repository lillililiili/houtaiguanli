package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeInput;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeOutcome;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/**
 * 违规类别筛选（2026-10-07，告警页"类别"改按违规原因）：告警经真实合并路径写入，筛选按当前违规原因
 * （升级过取最近一次升级的累计原因）匹配完整代码，与列表展示的原因同源。H2 与隔离 PostgreSQL 同跑。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@Transactional
class AlarmViolationReasonFilterApiTest {
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 21, 3, 0, 0, 0, ZoneOffset.UTC);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AlarmMergePolicy policy;
    private final RuleParams params = new StubParams();
    String suffix, org, district, ruleSet, ruleSetVersion, run, actor, token;

    @BeforeEach void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "org-vr-" + suffix; district = "dist-vr-" + suffix; ruleSet = "rs-vr-" + suffix; ruleSetVersion = "rsv-vr-" + suffix; run = "run-vr-" + suffix;
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", org, "ORG-VR-" + suffix, "原因筛选机构");
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", district, "DIST-VR-" + suffix, "原因筛选区域");
        jdbc.update("INSERT INTO rule_set(rule_set_id,rule_set_code,name,version,created_at,updated_at) VALUES (?,?,?,0,current_timestamp,current_timestamp)", ruleSet, "RS-VR-" + suffix, "测试规则集");
        jdbc.update("INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at) VALUES (?,?,1,'PUBLISHED','DEMO',?,'测试','mock',current_timestamp,current_timestamp)", ruleSetVersion, ruleSet, ts(T0));
        jdbc.update("INSERT INTO rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) VALUES (?,?,?,'ACTIVE','MANUAL',?,?,'RUNNING',0,0,0,0,'mock',current_timestamp)", run, ruleSet, ruleSetVersion, ts(T0), ts(T0));
        String role = "ROLE-VR-" + suffix;
        actor = UUID.randomUUID().toString(); token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,?,'',FALSE,TRUE,0,0,0,FALSE)", role, role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES (?,'alarm:read','READ',FALSE,current_timestamp)", role);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", actor, "vr-" + suffix, "原因筛选读者", role);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES (?,?,?)", actor, org, district);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES (?,?,?,'127.0.0.1',0)", token, actor, System.currentTimeMillis() + 3_600_000L);
    }

    @Test void filterMatchesWholeCurrentReasonCodesIncludingEscalatedReasons() throws Exception {
        String a = target("A"), b = target("B"), c = target("C");
        MergeOutcome noAuthorization = alarm(a, "MEDIUM", T0, List.of("NO_AUTHORIZATION"));
        MergeOutcome planAltitude = alarm(b, "MEDIUM", T0, List.of("PLAN_ALTITUDE_EXCEEDED"));
        MergeOutcome airspaceAltitude = alarm(c, "MEDIUM", T0, List.of("AIRSPACE_ALTITUDE_EXCEEDED", "NIGHT_FLIGHT"));
        // 同一目标后续研判带来新原因：升级原告警（不新建），当前原因 = 升级后的累计原因。
        MergeOutcome escalated = alarm(a, "HIGH", T0.plusMinutes(2), List.of("NO_AUTHORIZATION", "ROUTE_DEVIATION"));
        assertThat(escalated.alarmId()).isEqualTo(noAuthorization.alarmId());

        assertThat(ids("violation_reason=ROUTE_DEVIATION")).containsExactly(noAuthorization.alarmId());
        assertThat(ids("violation_reason=NO_AUTHORIZATION")).containsExactly(noAuthorization.alarmId());
        assertThat(ids("violation_reason=PLAN_ALTITUDE_EXCEEDED")).containsExactly(planAltitude.alarmId());
        assertThat(ids("violation_reason=AIRSPACE_ALTITUDE_EXCEEDED")).containsExactly(airspaceAltitude.alarmId());
        assertThat(ids("violation_reason=NIGHT_FLIGHT")).containsExactly(airspaceAltitude.alarmId());
        assertThat(page("violation_reason=INSIDE_RESTRICTED_AIRSPACE").path("total").asLong()).isZero();
        assertThat(page("").path("total").asLong()).isEqualTo(3);

        // 筛出来的每一条，页面上显示的违规原因里都有这一项。
        for (String code : List.of("ROUTE_DEVIATION", "PLAN_ALTITUDE_EXCEEDED", "AIRSPACE_ALTITUDE_EXCEEDED")) {
            for (JsonNode row : page("violation_reason=" + code).path("items")) {
                assertThat(java.util.stream.StreamSupport.stream(row.path("violation_reasons").spliterator(), false).map(JsonNode::asText).toList()).contains(code);
            }
        }
        String csv = mvc.perform(get("/api/v1/alarms/export.csv?violation_reason=ROUTE_DEVIATION").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines().skip(1).toList()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='alarms_exported' AND user_id=?"
                + " AND detail LIKE '%violation_reason=ROUTE_DEVIATION%' AND detail LIKE '%rows=1%'", Integer.class, actor)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"violation_reason=NO_PLAN", "violation_reason=route_deviation", "violation_reason=ROUTE%25",
            "violation_reason=", "violation_reason=NIGHT_FLIGHT&violation_reason=ROUTE_DEVIATION"})
    void unknownOrRepeatedReasonsAreRejectedNotIgnored(String query) throws Exception {
        alarm(target("V"), "MEDIUM", T0, List.of("NO_AUTHORIZATION"));
        for (String path : List.of("/api/v1/alarms?", "/api/v1/alarms/export.csv?")) {
            mvc.perform(get(path + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    MergeOutcome alarm(String target, String grade, OffsetDateTime at, List<String> reasons) {
        String evaluation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,"
                + "violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at)"
                + " VALUES (?,?,?,'ACTIVE','TARGET',?,?,?,'REPLAY','FULL','ILLEGAL',?,?,CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,'mock',?)",
                evaluation, run, ruleSetVersion, target, ts(at), ts(at), new BigDecimal("70"), grade, org, district, ts(at));
        return policy.apply(new MergeInput(evaluation, target, org, district, "mock", ruleSet, ruleSetVersion, "ILLEGAL", "FULL", grade,
                new BigDecimal("70"), reasons, at, at), params);
    }

    String target(String tag) {
        String id = "target-vr-" + tag + "-" + suffix;
        jdbc.update("INSERT INTO target(target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,'mock',?,?,current_timestamp,current_timestamp,0)", id, "T-VR-" + tag + "-" + suffix, org, district);
        return id;
    }

    JsonNode page(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/alarms" + (query.isEmpty() ? "" : "?" + query)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    List<String> ids(String query) throws Exception {
        return java.util.stream.StreamSupport.stream(page(query).path("items").spliterator(), false).map(n -> n.path("alarm_id").asText()).toList();
    }

    static Timestamp ts(OffsetDateTime at) { return Timestamp.from(at.toInstant()); }

    static final class StubParams implements RuleParams {
        private final Map<String, String> values = Map.of("C06.dedup_window_min", "5", "C06.upgrade_window_min", "10", "C06.auto_close_min", "15",
                "C06.severity_by_grade", "HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW");
        @Override public String ruleSetVersionId() { return "rsv-test"; }
        @Override public String paramStatus(String ruleCode, String key) { return "DEMO"; }
        @Override public BigDecimal number(String ruleCode, String key) { return new BigDecimal(required(ruleCode, key)); }
        @Override public int integer(String ruleCode, String key) { return Integer.parseInt(required(ruleCode, key)); }
        @Override public boolean bool(String ruleCode, String key) { return Boolean.parseBoolean(required(ruleCode, key)); }
        @Override public String string(String ruleCode, String key) { return required(ruleCode, key); }
        @Override public List<String> list(String ruleCode, String key) { return List.of(required(ruleCode, key).split(",")); }
        private String required(String ruleCode, String key) {
            String value = values.get(ruleCode + "." + key);
            if (value == null) throw new IllegalStateException("missing " + ruleCode + "." + key);
            return value;
        }
    }
}
