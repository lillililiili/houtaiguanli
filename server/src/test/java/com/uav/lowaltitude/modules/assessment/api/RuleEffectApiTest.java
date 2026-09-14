package com.uav.lowaltitude.modules.assessment.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;

/** 规则效果：分母 0 → {value:null, availability:NO_DENOMINATOR}；无 rule:read 403；事实分页与范围同谓词。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RuleEffectApiTest {
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 2, 0, 0, 0, ZoneOffset.UTC);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private String suffix, orgId, district, target, run, session;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        orgId = "s7e-org-" + suffix; district = "s7e-dist-" + suffix; target = "s7e-target-" + suffix; run = "s7e-run-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "S7E-" + suffix, "效果测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "S7E-" + suffix, "效果测试区域");
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'mock',?,?,current_timestamp,current_timestamp,0)", target, "MB-S7E-" + suffix, orgId, district);
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,finished_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) values (?,?,?,'ACTIVE','MANUAL',?,?,?,'DONE',1,1,0,0,'mock',?)",
                run, LocalStage7RuleEngineSeeder.RULE_SET_ID, LocalStage7RuleEngineSeeder.VERSION_1, ts(T0), ts(T0), ts(T0), ts(T0));
        session = user("assessment:read", "rule:read");
    }

    @Test
    void summaryReportsNullRatiosWithoutDenominatorAndRealRatiosAfterReview() throws Exception {
        String window = "from=" + T0.minusHours(1).toInstant().toEpochMilli() + "&to=" + T0.plusHours(1).toInstant().toEpochMilli();
        mvc.perform(get("/api/v1/rule-effects/summary?" + window + "&owner_org_id=" + orgId + "&timezone=Asia/Shanghai").header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evaluations").value(0))
                .andExpect(jsonPath("$.data.reviewed").value(0))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.false_positive_rate.value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.false_positive_rate.availability").value("NO_DENOMINATOR"))
                .andExpect(jsonPath("$.data.convergence_ratio.availability").value("NO_DENOMINATOR"))
                .andExpect(jsonPath("$.data.miss_rate.availability").value("NO_DENOMINATOR"))
                .andExpect(jsonPath("$.data.manual_override_rate.availability").value("NO_DENOMINATOR"));

        String a = evaluation("ILLEGAL", T0), b = evaluation("LEGAL", T0.plusMinutes(1)), c = evaluation("ABNORMAL", T0.plusMinutes(2)), d = evaluation("UNDETERMINED", T0.plusMinutes(3));
        // 影子研判不进默认分母：SHADOW 只有显式 mode=SHADOW 才计入，不能悄悄混进 ACTIVE 的 KPI。
        String shadow = evaluation("ILLEGAL", T0.plusMinutes(4), "SHADOW");
        review(a, "REJECTED", null); review(b, "OVERRIDDEN", "ILLEGAL"); review(c, "CONFIRMED", "ABNORMAL"); review(d, "PENDING_REVIEW", null);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,'rule-engine-legality-mock',?,'RULE_LEGALITY','HIGH',?,?,'mock',?,?,?)",
                "s7e-alarm-" + suffix, target, "eval:" + a, ts(T0), ts(T0), orgId, district, ts(T0));
        jdbc.update("insert into alarm_merge_group (group_id,target_id,alarm_type,rule_set_id,state,current_severity,first_alarm_id,latest_alarm_id,hit_count,window_opened_at,window_expires_at,last_hit_at,owner_org_id,district_id,version,created_at,updated_at) values (?,?,'RULE_LEGALITY',?,'OPEN','HIGH',?,?,2,?,?,?,?,?,0,?,?)",
                "s7e-group-" + suffix, target, LocalStage7RuleEngineSeeder.RULE_SET_ID, "s7e-alarm-" + suffix, "s7e-alarm-" + suffix, ts(T0), ts(T0.plusMinutes(5)), ts(T0), orgId, district, ts(T0), ts(T0));
        jdbc.update("insert into alarm_merge_member (member_id,group_id,evaluation_id,alarm_id,member_kind,severity_before,severity_after,created_at) values (?,?,?,?,'CREATED',null,'HIGH',?)", "s7e-m1-" + suffix, "s7e-group-" + suffix, a, "s7e-alarm-" + suffix, ts(T0));
        jdbc.update("insert into alarm_merge_member (member_id,group_id,evaluation_id,alarm_id,member_kind,severity_before,severity_after,created_at) values (?,?,?,null,'MERGED','HIGH','MEDIUM',?)", "s7e-m2-" + suffix, "s7e-group-" + suffix, c, ts(T0));

        // 4 条研判，2 条可告警（ILLEGAL/ABNORMAL），1 建 1 合并；已复核 3：误报 1/3、漏判 1/3（LEGAL→ILLEGAL 改判）、人工干预 2/3。
        mvc.perform(get("/api/v1/rule-effects/summary?" + window + "&owner_org_id=" + orgId).header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("ACTIVE"))
                .andExpect(jsonPath("$.data.evaluations").value(4))
                .andExpect(jsonPath("$.data.alarm_worthy").value(2))
                .andExpect(jsonPath("$.data.alarms_created").value(1))
                .andExpect(jsonPath("$.data.alarms_merged").value(1))
                .andExpect(jsonPath("$.data.convergence_ratio.value").value(0.5))
                .andExpect(jsonPath("$.data.reviewed").value(3))
                .andExpect(jsonPath("$.data.false_positive_rate.value").value(0.3333))
                .andExpect(jsonPath("$.data.false_positive_rate.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.miss_rate.value").value(0.3333))
                .andExpect(jsonPath("$.data.manual_override_rate.value").value(0.6667));
        mvc.perform(get("/api/v1/rule-effects/summary?" + window + "&owner_org_id=" + orgId + "&mode=SHADOW").header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SHADOW"))
                .andExpect(jsonPath("$.data.evaluations").value(1))
                .andExpect(jsonPath("$.data.alarm_worthy").value(0))
                .andExpect(jsonPath("$.data.reviewed").value(0))
                .andExpect(jsonPath("$.data.convergence_ratio.availability").value("NO_DENOMINATOR"));
        mvc.perform(get("/api/v1/rule-effects/summary?" + window + "&owner_org_id=" + orgId + "&mode=BOTH").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        // 事实分页按同一范围谓词（默认同样只看 ACTIVE）；无 alarm:read 时告警/组引用省略。
        mvc.perform(get("/api/v1/rule-effects/facts?" + window + "&owner_org_id=" + orgId + "&mode=SHADOW").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].evaluation_id").value(shadow));
        mvc.perform(get("/api/v1/rule-effects/facts?" + window + "&owner_org_id=" + orgId + "&page=1&size=2").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4)).andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].evaluation_id").value(d))
                .andExpect(jsonPath("$.data.items[0].review_state").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.items[0].target_id").doesNotExist());
        mvc.perform(get("/api/v1/rule-effects/facts?review_state=REJECTED&owner_org_id=" + orgId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].has_alarm").value(true))
                .andExpect(jsonPath("$.data.items[0].merge_kind").value("CREATED"))
                .andExpect(jsonPath("$.data.items[0].alarm_id").doesNotExist());
    }

    @Test
    void missingRuleReadIsForbiddenBeforeParametersAndUnknownParametersAreRejected() throws Exception {
        String assessmentOnly = user("assessment:read");
        mvc.perform(get("/api/v1/rule-effects/summary?from=bad").header("Authorization", bearer(assessmentOnly))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/rule-effects/facts?wat=1").header("Authorization", bearer(assessmentOnly))).andExpect(status().isForbidden());
        String ruleOnly = user("rule:read");
        mvc.perform(get("/api/v1/rule-effects/summary?from=1&to=2").header("Authorization", bearer(ruleOnly))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/rule-effects/summary?from=1&to=2&wat=1").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rule-effects/summary?from=2&to=1").header("Authorization", bearer(session))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"));
        mvc.perform(get("/api/v1/rule-effects/summary").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rule-effects/summary?from=1&to=2&timezone=Mars/Olympus").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rule-effects/facts?page_size=20").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
    }

    private String evaluation(String legalStatus, OffsetDateTime at) { return evaluation(legalStatus, at, "ACTIVE"); }

    private String evaluation(String legalStatus, OffsetDateTime at, String mode) {
        String id = "s7e-eval-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,"
                + "violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at)"
                + " values (?,?,?,?,'TARGET',?,?,?,'FRESH','NONE',?,CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,'mock',?)",
                id, run, LocalStage7RuleEngineSeeder.VERSION_1, mode, target, ts(at), ts(at), legalStatus, orgId, district, ts(at));
        return id;
    }

    private void review(String evaluationId, String state, String manualStatus) {
        jdbc.update("insert into legality_review (evaluation_id,review_state,manual_status,version,owner_org_id,district_id,created_at,updated_at) values (?,?,?,0,?,?,?,?)",
                evaluationId, state, manualStatus, orgId, district, ts(T0), ts(T0));
    }

    private String user(String... permissions) {
        String tag = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-S7E-" + tag, userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'READ',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "s7e-" + tag, "效果测试员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, orgId, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, userId, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private static Timestamp ts(OffsetDateTime value) { return Timestamp.from(value.toInstant()); }
    private static String bearer(String token) { return "Bearer " + token; }
}
