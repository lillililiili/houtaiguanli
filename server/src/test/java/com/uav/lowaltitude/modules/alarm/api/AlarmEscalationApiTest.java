package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeInput;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeOutcome;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/**
 * 告警升级（BUG-11 偏航不升级、BUG-16 同一架无人机两条告警）：列表与详情给升级后的当前等级、累计的违规原因与升级次数；
 * 等级筛选与排序按当前等级；升级记录（谁、什么时候、为什么）与告警同权限、同范围。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AlarmEscalationApiTest {
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 2, 0, 0, 0, ZoneOffset.UTC);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AlarmMergePolicy policy;
    private final RuleParams params = new StubParams();
    private String suffix, org, district, ruleSet, ruleSetVersion, run, role, sessionId;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "org-esc-" + suffix; district = "dist-esc-" + suffix; ruleSet = "rs-esc-" + suffix; ruleSetVersion = "rsv-esc-" + suffix; run = "run-esc-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-ESC-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-ESC-" + suffix, "区域");
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,version,created_at,updated_at) values (?,?,?,0,current_timestamp,current_timestamp)", ruleSet, "RS-ESC-" + suffix, "测试规则集");
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at) values (?,?,1,'PUBLISHED','DEMO',?,'测试','mock',current_timestamp,current_timestamp)", ruleSetVersion, ruleSet, ts(T0));
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) values (?,?,?,'ACTIVE','MANUAL',?,?,'RUNNING',0,0,0,0,'mock',current_timestamp)", run, ruleSet, ruleSetVersion, ts(T0), ts(T0));
        role = "ROLE-ALARM-ESC-" + suffix;
        String userId = UUID.randomUUID().toString();
        sessionId = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'alarm:read','READ',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", userId, "alarm-esc-" + suffix, "告警读取人", role);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", sessionId, userId, System.currentTimeMillis() + 3_600_000L);
    }

    @Test
    void escalatedAlarmShowsCurrentLevelAccumulatedReasonsAndWhoWhenWhy() throws Exception {
        String target = target("A");
        MergeOutcome first = policy.apply(input(target, evaluation(target, "MEDIUM", T0), "MEDIUM", T0, List.of("NIGHT_FLIGHT")), params);
        MergeOutcome escalated = policy.apply(input(target, evaluation(target, "HIGH", T0.plusMinutes(2)), "HIGH", T0.plusMinutes(2),
                List.of("NIGHT_FLIGHT", "INSIDE_RESTRICTED_AIRSPACE")), params);
        assertThat(escalated.alarmId()).isEqualTo(first.alarmId());
        long escalatedAt = T0.plusMinutes(2).toInstant().toEpochMilli();

        mvc.perform(get("/api/v1/alarms/" + first.alarmId()).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.severity").value("HIGH"))
                .andExpect(jsonPath("$.data.original_severity").value("MEDIUM"))
                .andExpect(jsonPath("$.data.violation_reasons", contains("NIGHT_FLIGHT", "INSIDE_RESTRICTED_AIRSPACE")))
                .andExpect(jsonPath("$.data.escalation_count").value(1))
                .andExpect(jsonPath("$.data.escalated_at").value(escalatedAt))
                .andExpect(jsonPath("$.data.event_id").value(first.eventId()));
        mvc.perform(get("/api/v1/alarms/" + first.alarmId() + "/escalations").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].seq").value(1))
                .andExpect(jsonPath("$.data.items[0].trigger_kind").value("ENGINE"))
                .andExpect(jsonPath("$.data.items[0].severity_before").value("MEDIUM"))
                .andExpect(jsonPath("$.data.items[0].severity_after").value("HIGH"))
                .andExpect(jsonPath("$.data.items[0].reasons_added", contains("INSIDE_RESTRICTED_AIRSPACE")))
                .andExpect(jsonPath("$.data.items[0].reasons_after", contains("NIGHT_FLIGHT", "INSIDE_RESTRICTED_AIRSPACE")))
                .andExpect(jsonPath("$.data.items[0].created_at").value(escalatedAt))
                // 系统研判升级没有操作人；明细 JSON 与研判 ID 都不外露。
                .andExpect(jsonPath("$.data.items[0].actor_id").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].evaluation_id").doesNotExist());
    }

    @Test
    void neverEscalatedAlarmKeepsItsOwnLevelAndReasons() throws Exception {
        String target = target("B");
        MergeOutcome first = policy.apply(input(target, evaluation(target, "MEDIUM", T0), "MEDIUM", T0, List.of("ROUTE_DEVIATION")), params);
        mvc.perform(get("/api/v1/alarms/" + first.alarmId()).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.severity").value("MEDIUM"))
                .andExpect(jsonPath("$.data.original_severity").value("MEDIUM"))
                .andExpect(jsonPath("$.data.violation_reasons", contains("ROUTE_DEVIATION")))
                .andExpect(jsonPath("$.data.escalation_count").value(0))
                .andExpect(jsonPath("$.data.escalated_at").doesNotExist())
                .andExpect(jsonPath("$.data.detail").doesNotExist());
        mvc.perform(get("/api/v1/alarms/" + first.alarmId() + "/escalations").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void levelFilterSortAndDefaultOrderUseCurrentLevel() throws Exception {
        String escalatedTarget = target("C"), plainTarget = target("D");
        MergeOutcome low = policy.apply(input(escalatedTarget, evaluation(escalatedTarget, "LOW", T0), "LOW", T0, List.of("NIGHT_FLIGHT")), params);
        MergeOutcome medium = policy.apply(input(plainTarget, evaluation(plainTarget, "MEDIUM", T0.plusMinutes(1)), "MEDIUM", T0.plusMinutes(1), List.of("NO_AUTHORIZATION")), params);
        policy.apply(input(escalatedTarget, evaluation(escalatedTarget, "HIGH", T0.plusMinutes(2)), "HIGH", T0.plusMinutes(2),
                List.of("INSIDE_RESTRICTED_AIRSPACE")), params);
        String bearer = "Bearer " + sessionId;
        mvc.perform(get("/api/v1/alarms?owner_org_id=" + org + "&severity=HIGH").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].alarm_id").value(low.alarmId()));
        mvc.perform(get("/api/v1/alarms?owner_org_id=" + org + "&severity=LOW").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/alarms?owner_org_id=" + org + "&sort=severity&order=desc").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].alarm_id").value(low.alarmId()))
                .andExpect(jsonPath("$.data.items[1].alarm_id").value(medium.alarmId()));
        // 默认次序：两条都待核实，升级到高的排在中等级前面。
        mvc.perform(get("/api/v1/alarms?owner_org_id=" + org).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].alarm_id").value(low.alarmId()))
                .andExpect(jsonPath("$.data.items[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.data.items[0].violation_reasons", contains("NIGHT_FLIGHT", "INSIDE_RESTRICTED_AIRSPACE")))
                .andExpect(jsonPath("$.data.items[1].alarm_id").value(medium.alarmId()));
    }

    @Test
    void escalationHistoryFollowsAlarmPermissionScopeAndPaging() throws Exception {
        String target = target("E");
        MergeOutcome first = policy.apply(input(target, evaluation(target, "MEDIUM", T0), "MEDIUM", T0, List.of("NIGHT_FLIGHT")), params);
        policy.apply(input(target, evaluation(target, "MEDIUM", T0.plusMinutes(1)), "MEDIUM", T0.plusMinutes(1), List.of("ROUTE_DEVIATION")), params);
        String path = "/api/v1/alarms/" + first.alarmId() + "/escalations";
        String bearer = "Bearer " + sessionId;
        mvc.perform(get(path + "?unknown=1").header("Authorization", bearer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get(path + "?size=0").header("Authorization", bearer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/alarms/does-not-exist/escalations").header("Authorization", bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ALARM_NOT_FOUND"));
        mvc.perform(get(path + "?page=2&size=1").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items").isEmpty());
        // 目录停用后与告警详情一致地隐藏。
        jdbc.update("update app_district set enabled=false where district_id=?", district);
        mvc.perform(get(path).header("Authorization", bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ALARM_NOT_FOUND"));
        jdbc.update("update app_district set enabled=true where district_id=?", district);
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='alarm:read'", role);
        mvc.perform(get(path + "?unknown=1").header("Authorization", bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private String target(String tag) {
        String id = "target-esc-" + tag + "-" + suffix;
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'mock',?,?,current_timestamp,current_timestamp,0)", id, "T-ESC-" + tag + "-" + suffix, org, district);
        return id;
    }

    private MergeInput input(String target, String evaluationId, String grade, OffsetDateTime at, List<String> reasons) {
        return new MergeInput(evaluationId, target, org, district, "mock", ruleSet, ruleSetVersion, "ILLEGAL", "FULL", grade,
                new BigDecimal("70"), reasons, at, at);
    }

    private String evaluation(String target, String grade, OffsetDateTime at) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,"
                + "violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at)"
                + " values (?,?,?,'ACTIVE','TARGET',?,?,?,'REPLAY','FULL','ILLEGAL',?,?,cast('[]' as json),cast('[]' as json),cast('[]' as json),cast('[]' as json),cast('{}' as json),?,?,'mock',?)",
                id, run, ruleSetVersion, target, ts(at), ts(at), new BigDecimal("70"), grade, org, district, ts(at));
        return id;
    }

    private static Timestamp ts(OffsetDateTime at) { return Timestamp.from(at.toInstant()); }

    private static final class StubParams implements RuleParams {
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
