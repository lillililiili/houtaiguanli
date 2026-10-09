package com.uav.lowaltitude.modules.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.reporting.application.ReportingService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportingApiTest {

    private static final String TEMP_PASSWORD = "TempUser#2026A";
    private static final String NEW_PASSWORD = "Changed#2026UserA";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void organizationFilterNarrowsFactsAndRejectsUnknownOrganization() throws Exception {
        String token = login("admin1", "changeme");
        String extraOrg = UUID.randomUUID().toString();
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", extraOrg, extraOrg, "统计单位筛选测试");
        var at = java.time.OffsetDateTime.parse("2004-01-01T00:00:00+08:00");
        for (String owner : java.util.List.of("seed-stage3-org", extraOrg)) {
            String id = UUID.randomUUID().toString();
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','live',?,'seed-stage3-district',?,?)", id, id, at, at, owner, at, at);
        }
        mvc.perform(get("/api/v1/stats/operations").param("from", "2004-01-01").param("to", "2004-01-01")
                .param("owner_org_id", extraOrg).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.total").value(1))
                .andExpect(jsonPath("$.data.owner_org_id").value(extraOrg)).andExpect(jsonPath("$.data.devices.total").value(0));
        mvc.perform(get("/api/v1/stats/operations").param("owner_org_id", "not-visible-or-missing").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_ORGANIZATION"));
        mvc.perform(get("/api/v1/stats/operations/export.csv").param("owner_org_id", "not-visible-or-missing").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        String exported = mvc.perform(get("/api/v1/stats/operations/export.csv").param("from", "2004-01-01").param("to", "2004-01-01")
                .param("owner_org_id", extraOrg).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(exported).contains(extraOrg).contains("\"新增目标数\",\"1\"");
        String userId = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?", userId);
        jdbc.update("delete from app_user_data_scope where user_id=?", userId);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,'seed-stage3-org','seed-stage3-district')", userId);
        mvc.perform(get("/api/v1/stats/operations").param("owner_org_id", extraOrg).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_ORGANIZATION"));
        mvc.perform(get("/api/v1/stats/operations/organizations").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].org_id").value("seed-stage3-org"));
    }

    @AfterEach
    void cleanCreatedFixtures() {
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'itest-stat-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'itest-stat-%')");
        jdbc.update("delete from app_user where account like 'itest-stat-%'");
        jdbc.update("delete from app_role_permission where role_code in (select role_code from app_role where name like '统计测试角色%')");
        jdbc.update("delete from app_role where name like '统计测试角色%'");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void shanghaiDateBoundariesIncludeFirstInstantAndExcludeNextDayForTargetsAndCases() throws Exception {
        String token = login("admin1", "changeme");
        var start = java.time.OffsetDateTime.parse("2024-12-31T00:00:00+08:00");
        var instants = java.util.List.of(start.minusNanos(1_000_000), start,
                start.plusDays(1).minusNanos(1_000_000), start.plusDays(1),
                start.plusDays(2).minusNanos(1_000_000), start.plusDays(2));
        String org = UUID.randomUUID().toString();
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",
                org, org, "隔离跨年统计边界");
        for (var at : instants) {
            String id = UUID.randomUUID().toString();
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','live',?,'seed-stage3-district',?,?)",
                    id, id, at, start.plusDays(4), org, at, start.plusDays(4));
        }
        // This rollback-only fixture exercises the live-source filter. It is not a real filing or penalty result.
        String caseId = "seed-stage14-case-investigating";
        jdbc.update("update punishment_case set source_mode='live',owner_org_id=? where case_id=?", org, caseId);
        jdbc.update("update handoff set created_at=? where handoff_id='seed-stage14-handoff-punish'", start.minusDays(10));
        for (int index = 0; index < instants.size(); index++) {
            jdbc.update("update punishment_case set filed_at=? where case_id=?", instants.get(index), caseId);
            for (int day = 0; day < 2; day++) {
                String date = start.plusDays(day).toLocalDate().toString();
                int expectedCases = index == day * 2 + 1 || index == day * 2 + 2 ? 1 : 0;
                JsonNode result = data(mvc.perform(get("/api/v1/stats/operations").param("from", date).param("to", date)
                        .param("owner_org_id", org).header("Authorization", bearer(token)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                assertThat(result.path("summary").path("total").asInt()).isEqualTo(2);
                assertThat(result.path("summary").path("punish").asInt()).isEqualTo(expectedCases);
                assertThat(sum(result.path("days"), "total")).isEqualTo(2);
                assertThat(sum(result.path("days"), "punish")).isEqualTo(expectedCases);
                assertThat(sum(result.path("by_penalty"), "value")).isZero();
            }
        }
        JsonNode range = data(mvc.perform(get("/api/v1/stats/operations").param("from", "2024-12-31").param("to", "2025-01-01")
                .param("owner_org_id", org).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(range.path("summary").path("total").asInt()).isEqualTo(4);
        assertThat(range.path("summary").path("punish").asInt()).isZero();
        assertThat(range.path("days").size()).isEqualTo(2);
        String csv = mvc.perform(get("/api/v1/stats/operations/export.csv").param("from", "2024-12-31").param("to", "2025-01-01")
                .param("owner_org_id", org).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("\"新增目标数\",\"4\"").contains("2024-12-31").contains("2025-01-01");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void repeatedAssessmentsAndRisksCountEachTargetOnceAndKeepHeightDatumsSeparate() throws Exception {
        String token=login("admin1","changeme");
        var at=java.time.OffsetDateTime.parse("2001-01-01T00:00:00+08:00");
        String org="seed-stage3-org", district="seed-stage3-district", id="stats-dedup";
        jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','live',?,?,?,?)",id,id,at,at.plusHours(12),org,district,at,at);
        jdbc.update("insert into target_latest_state(target_id,height_agl_m,observed_at,received_at,created_at,updated_at) values(?,150,?,?,?,?)",id,at,at,at,at);
        for(int i=0;i<2;i++) {
            jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) select ?,v.rule_set_id,v.rule_set_version_id,'ACTIVE','MANUAL',?,?,'DONE',1,1,0,0,'mock',? from rule_set_version v order by v.rule_set_version_id limit 1","stats-run-"+i,at,at,at);
            jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at) select ?,r.run_id,r.rule_set_version_id,'ACTIVE','TARGET',?,r.as_of,r.started_at,'FRESH','FULL',?,60,'HIGH',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,'mock',? from rule_run r where r.run_id=?","stats-eval-"+i,id,i==0?"ABNORMAL":"ILLEGAL",org,district,at.plusMinutes(i),"stats-run-"+i);
            jdbc.update("insert into flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,severity,state_code,reason_code,reason_text,received_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal',?,'AIRSPACE',?,'PENDING_VERIFICATION','PROHIBITED_AIRSPACE_OVERLAP','隔离统计测试',?,'mock',?,?,?,?,0)","stats-risk-"+i,"stats-risk-"+i,id,i==0?"LOW":"HIGH",at.plusMinutes(i),org,district,at,at);
        }
        JsonNode result=operations(token,"2001-01-01");
        assertThat(result.path("summary").path("total").asInt()).isEqualTo(1);
        assertThat(result.path("summary").path("illegal").asInt()).isZero();
        assertThat(result.path("summary").path("high_risk").asInt()).isZero();
        assertThat(result.path("alt_total").asInt()).isZero();
        jdbc.update("update target_latest_state set altitude_amsl_m=0,observed_at=? where target_id=?",at.plusHours(1),id);
        result=operations(token,"2001-01-01");
        assertThat(result.path("summary").path("total").asInt()).isEqualTo(1);
        assertThat(result.path("alt_total").asInt()).isEqualTo(1);
        assertThat(sum(result.path("alt_bands"),"value")).isEqualTo(1);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void scopeAndDomainPermissionsDoNotLeakOrFabricateZeros() throws Exception {
        String token=login("admin1","changeme");
        String user=jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class);
        var at=java.time.OffsetDateTime.parse("2002-01-01T00:00:00+08:00");
        String org="seed-stage3-org", district="seed-stage3-district";
        String other=jdbc.queryForObject("select district_id from app_district where district_id<>? and enabled=TRUE fetch first 1 row only",String.class,district);
        for(String[] row:new String[][]{{"stats-visible",district},{"stats-hidden",other}})
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'live',?,?,?,?)",row[0],row[0],at,at,org,row[1],at,at);
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?",user);
        jdbc.update("delete from app_user_data_scope where user_id=?",user);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)",user,org,district);
        JsonNode result=operations(token,"2002-01-01");
        assertThat(result.path("summary").path("total").asInt()).isEqualTo(1);
        jdbc.update("delete from app_role_permission where role_code='ROLE-ADMIN' and permission_code='target:read'");
        sqlSession.clearCache();
        result=operations(token,"2002-01-01");
        assertThat(result.path("summary").path("total").isMissingNode()||result.path("summary").path("total").isNull()).isTrue();
        assertThat(result.path("availability").path("total").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("observation_metrics").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("observation_metrics").hasNonNull("duration_seconds")).isFalse();
        assertThat(result.path("by_type").isEmpty()).isTrue();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void historicalSimulatedCasesAndDemoDecisionsNeverEnterFormalReports() throws Exception {
        String token=login("admin1","changeme");
        String caseId="seed-stage14-case-investigating",discretion="seed-stage14-discretion-draft";
        String actor=jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class);
        var at=java.time.OffsetDateTime.parse("2003-01-01T00:00:00+08:00");
        assertThat(jdbc.update("update punishment_case set filed_at=? where case_id=?",at,caseId)).isEqualTo(1);
        jdbc.update("update handoff set created_at=? where handoff_id='seed-stage14-handoff-punish'",at.minusDays(1));
        assertThat(operations(token,"2002-12-31").path("summary").path("punish").asInt()).isZero();
        JsonNode result=operations(token,"2003-01-01");
        assertThat(result.path("summary").path("punish").asInt()).isZero();
        assertThat(sum(result.path("by_penalty"),"value")).isZero();
        assertThat(result.path("partners").isEmpty()).isTrue();
        jdbc.update("update penalty_discretion set status='CONFIRMED',fine_amount=12345,decided_by=?,decided_at=? where discretion_id=?",actor,at,discretion);
        jdbc.update("insert into penalty_decision_document(document_id,document_no,case_id,discretion_id,template_version,status,fields,rendered_sha256,issued_by,issued_at,updated_at) values('stats-doc','STATS-DOC',?,?,'v1','ISSUED',CAST('{}' AS JSON),?,?,?,?)",caseId,discretion,"a".repeat(64),actor,at,at);
        result=operations(token,"2003-01-01");
        assertThat(sum(result.path("by_penalty"),"value")).isZero();
        assertThat(result.path("partners").isEmpty()).isTrue();
        jdbc.update("update penalty_decision_document set status='REVOKED',revoked_at=?,revoke_reason='隔离测试撤销' where document_id='stats-doc'",at.plusHours(1));
        assertThat(sum(operations(token,"2003-01-01").path("by_penalty"),"value")).isZero();
    }

    /**
     * 2026-10-07 用户决定：设备模拟器（replay）的数据计入运行统计，系统自带的演示样例（mock）不计。
     * 2026-10-08 D-4：研判和合法性研判页同一口径，用演示参数得出的结论也算，真实设备的也一样。
     */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void simulatorDataCountsButBuiltInDemoSamplesDoNot() throws Exception {
        String token=login("admin1","changeme");
        String org=UUID.randomUUID().toString(), district="seed-stage3-district";
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,"统计口径-模拟器");
        var at=java.time.OffsetDateTime.parse("2006-01-01T00:00:00+08:00");
        String demoVersion=jdbc.queryForObject("select rule_set_version_id from rule_set_version where param_status='DEMO' order by rule_set_version_id fetch first 1 row only",String.class);
        jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) select 'stats-scope-run',v.rule_set_id,v.rule_set_version_id,'ACTIVE','MANUAL',?,?,'DONE',3,3,0,0,'replay',? from rule_set_version v where v.rule_set_version_id=?",at,at,at,demoVersion);
        int n=0;
        for(String mode:java.util.List.of("live","replay","mock")) {
            String id="stats-scope-"+mode;
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV',?,?,?,?,?)",id,id,at,at,mode,org,district,at,at);
            jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at,decision_algorithm_version,decision_assurance_code,decision_assurance_reasons) select ?,r.run_id,r.rule_set_version_id,'ACTIVE','TARGET',?,r.as_of,r.started_at,'FRESH','FULL','ILLEGAL',60,'HIGH',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,?,'legality-assurance-v1','SUFFICIENT',CAST('[]' AS JSON) from rule_run r where r.run_id='stats-scope-run'","stats-scope-eval-"+mode,id,org,district,mode,at.plusMinutes(n));
            jdbc.update("insert into flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,severity,state_code,reason_code,reason_text,received_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal',?,'AIRSPACE','HIGH','PENDING_VERIFICATION','PROHIBITED_AIRSPACE_OVERLAP','隔离统计测试',?,?,?,?,?,?,0)","stats-scope-risk-"+mode,"stats-scope-risk-"+mode,id,at.plusMinutes(n),mode,org,district,at,at);
            n++;
        }
        JsonNode result=data(mvc.perform(get("/api/v1/stats/operations").param("from","2006-01-01").param("to","2006-01-01")
                .param("owner_org_id",org).header("Authorization",bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("summary").path("total").asInt()).isEqualTo(2);
        assertThat(result.path("summary").path("uav").asInt()).isEqualTo(2);
        // 真实设备和模拟器各一条非法，都算（演示参数也算，和研判页一样）；演示样例那条不算。
        assertThat(result.path("summary").path("illegal").asInt()).isEqualTo(2);
        assertThat(result.path("summary").path("high_risk").asInt()).isEqualTo(2);
        assertThat(result.path("source_mode").asText()).isEqualTo("mixed");
        assertThat(result.path("simulated").asBoolean()).isTrue();
    }

    /**
     * 2026-10-08 D-4（方案 新-2）：依据不足、待人工复核的非法结论，合法性研判页算，运行统计和大屏也算，两边数字一致。
     */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void insufficientConclusionsCountLikeTheLegalityPage() throws Exception {
        String token=login("admin1","changeme");
        String org=UUID.randomUUID().toString(), district="seed-stage3-district";
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,"统计口径-研判页");
        var at=java.time.OffsetDateTime.parse("2007-01-01T00:00:00+08:00");
        jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) select 'stats-assurance-run',v.rule_set_id,v.rule_set_version_id,'ACTIVE','MANUAL',?,?,'DONE',3,3,0,0,'replay',? from rule_set_version v order by v.rule_set_version_id fetch first 1 row only",at,at,at);
        int n=0;
        for(String[] row:new String[][]{{"sufficient","ILLEGAL","SUFFICIENT"},{"insufficient","ILLEGAL","INSUFFICIENT"},{"legal","LEGAL","INSUFFICIENT"}}) {
            String id="stats-assurance-"+row[0];
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','replay',?,?,?,?)",id,id,at,at,org,district,at,at);
            jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at,decision_algorithm_version,decision_assurance_code,decision_assurance_reasons) select ?,r.run_id,r.rule_set_version_id,'ACTIVE','TARGET',?,r.as_of,r.started_at,'FRESH','FULL',?,?,?,CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,'replay',?,'legality-assurance-v1',?,CAST('[]' AS JSON) from rule_run r where r.run_id='stats-assurance-run'",
                    "stats-assurance-eval-"+row[0],id,row[1],"LEGAL".equals(row[1])?null:60,"LEGAL".equals(row[1])?null:"HIGH",org,district,at.plusMinutes(n++),row[2]);
        }
        JsonNode stats=data(mvc.perform(get("/api/v1/stats/operations").param("from","2007-01-01").param("to","2007-01-01")
                .param("owner_org_id",org).header("Authorization",bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode page=data(mvc.perform(get("/api/v1/legality-evaluations/summary").param("mode","ACTIVE").param("latest_only","true")
                .param("object_type_code","UAV").param("owner_org_id",org).header("Authorization",bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("illegal").asInt()).isEqualTo(2);
        assertThat(stats.path("summary").path("illegal").asInt()).isEqualTo(page.path("illegal").asInt());
        assertThat(stats.path("days").get(0).path("illegal").asInt()).isEqualTo(2);
    }

    /**
     * 2026-10-08 新-2 第 3 点：每架无人机取哪一次研判，和合法性研判页一模一样——只看正式模式，按研判时间取最新。
     * 原先取任何模式里最后写入的一条：影子模式后写的“合法”、写入晚但研判时间早的“合法”，都让统计少算非法。
     */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void latestConclusionPerDroneIsTheLegalityPagesOne() throws Exception {
        String token=login("admin1","changeme");
        String org=UUID.randomUUID().toString(), district="seed-stage3-district";
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,"统计口径-最新研判");
        var at=java.time.OffsetDateTime.parse("2008-01-01T00:00:00+08:00");
        for(String mode:java.util.List.of("ACTIVE","SHADOW"))
            jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) select ?,v.rule_set_id,v.rule_set_version_id,?,'MANUAL',?,?,'DONE',2,2,0,0,'replay',? from rule_set_version v order by v.rule_set_version_id fetch first 1 row only","stats-latest-run-"+mode,mode,at,at,at);
        for(String id:java.util.List.of("stats-latest-shadow","stats-latest-order"))
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','replay',?,?,?,?)",id,id,at,at,org,district,at,at);
        // 目标 1：正式模式判非法，之后影子模式又判了一次合法。研判页只看正式模式。
        evaluation("stats-latest-shadow-active","ACTIVE","stats-latest-shadow","ILLEGAL",at.plusMinutes(1),at.plusMinutes(1),org,district);
        evaluation("stats-latest-shadow-shadow","SHADOW","stats-latest-shadow","LEGAL",at.plusMinutes(2),at.plusMinutes(2),org,district);
        // 目标 2：研判时间较晚的是非法；研判时间较早的合法那条写入得更晚（比如重算补写）。研判页按研判时间取最新。
        evaluation("stats-latest-order-illegal","ACTIVE","stats-latest-order","ILLEGAL",at.plusMinutes(5),at.plusMinutes(3),org,district);
        evaluation("stats-latest-order-legal","ACTIVE","stats-latest-order","LEGAL",at.plusMinutes(4),at.plusMinutes(6),org,district);
        JsonNode page=data(mvc.perform(get("/api/v1/legality-evaluations/summary").param("mode","ACTIVE").param("latest_only","true")
                .param("object_type_code","UAV").param("owner_org_id",org).header("Authorization",bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode stats=data(mvc.perform(get("/api/v1/stats/operations").param("from","2008-01-01").param("to","2008-01-01")
                .param("owner_org_id",org).header("Authorization",bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("illegal").asInt()).isEqualTo(2);
        assertThat(stats.path("summary").path("illegal").asInt()).isEqualTo(page.path("illegal").asInt());
        assertThat(stats.path("days").get(0).path("illegal").asInt()).isEqualTo(2);
        assertThat(stats.path("availability").path("illegal").path("reason").asText()).contains("合法性研判页");
    }

    private void evaluation(String id,String mode,String target,String legal,java.time.OffsetDateTime evaluatedAt,java.time.OffsetDateTime createdAt,String org,String district) {
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at,decision_algorithm_version,decision_assurance_code,decision_assurance_reasons) select ?,r.run_id,r.rule_set_version_id,?,'TARGET',?,r.as_of,?,'FRESH','FULL',?,?,?,CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,'replay',?,'legality-assurance-v1','SUFFICIENT',CAST('[]' AS JSON) from rule_run r where r.run_id=?",
                id,mode,target,evaluatedAt,legal,"LEGAL".equals(legal)?null:60,"LEGAL".equals(legal)?null:"HIGH",org,district,createdAt,"stats-latest-run-"+mode);
    }

    /**
     * ZT-17 复测 2：被合并的目标是存活目标的别名（决策 16-6），新增目标数不另计——目标列表、态势页、大屏同样不列它；
     * 原先运行统计把它也算一个，比大屏多出 21 个。它名下的风险也不另计，存活目标按它自己的最新风险计。
     */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void mergedTargetsAreNotCountedAgain() throws Exception {
        String token=login("admin1","changeme");
        var at=java.time.OffsetDateTime.parse("2007-01-01T00:00:00+08:00");
        String org="seed-stage3-org", district="seed-stage3-district";
        for(String id:java.util.List.of("stats-merge-survivor","stats-merge-alias"))
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','live',?,?,?,?)",id,id,at,at,org,district,at,at);
        jdbc.update("insert into flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,severity,state_code,reason_code,reason_text,received_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) values('stats-merge-risk','seed-stage3-source','stats-merge-risk','seed-stage3-plan-legal','seed-stage3-rv-legal','stats-merge-alias','AIRSPACE','HIGH','PENDING_VERIFICATION','PROHIBITED_AIRSPACE_OVERLAP','隔离统计测试',?,'live',?,?,?,?,0)",at,org,district,at,at);
        JsonNode before=operations(token,"2007-01-01");
        assertThat(before.path("summary").path("total").asInt()).isEqualTo(2);
        assertThat(before.path("summary").path("high_risk").asInt()).isEqualTo(1);
        jdbc.update("insert into target_track_status(target_id,status,since,updated_at) values('stats-merge-alias','MERGE',?,?)",at,at);
        JsonNode after=operations(token,"2007-01-01");
        assertThat(after.path("summary").path("total").asInt()).isEqualTo(1);
        assertThat(after.path("summary").path("high_risk").asInt()).isZero();
        assertThat(sum(after.path("by_risk"),"value")).isEqualTo(1);
        assertThat(sum(after.path("days"),"total")).isEqualTo(1);
        assertThat(after.path("availability").path("total").path("reason").asText()).contains("被合并");
        // 与目标列表一致：列表默认也不列被合并的目标。
        long from=at.toInstant().toEpochMilli(), to=at.plusDays(1).toInstant().toEpochMilli()-1;
        mvc.perform(get("/api/v1/targets").param("seen_from",String.valueOf(from)).param("seen_to",String.valueOf(to)).header("Authorization",bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].target_id").value("stats-merge-survivor"));
    }

    private JsonNode operations(String token,String day) throws Exception {
        return data(mvc.perform(get("/api/v1/stats/operations").param("from",day).param("to",day).header("Authorization",bearer(token)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/stats/operations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/stats/operations/export.csv"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/stats/reports/preview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/stats/reports/export.xlsx"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void businessTargetsUseFirstSeenAndExposeUnavailableMetrics() throws Exception {
        String id = "stat-" + UUID.randomUUID().toString().substring(0, 20);
        var tuple = jdbc.queryForMap("select o.org_id,d.district_id from app_org o cross join app_district d where o.enabled=TRUE and d.enabled=TRUE fetch first 1 row only");
        var first = java.time.OffsetDateTime.parse("2040-01-01T15:59:59Z");
        var last = java.time.OffsetDateTime.parse("2040-01-02T02:00:00Z");
        jdbc.update("insert into target(target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,?,'live',?,?,?,?)",
                id,id,"UAV",first,last,tuple.get("org_id"),tuple.get("district_id"),first,last);
        try {
            String token = login("admin1", "changeme");
            JsonNode firstDay = data(mvc.perform(get("/api/v1/stats/operations")
                    .param("from", "2040-01-01").param("to", "2040-01-01").header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(firstDay.path("summary").path("total").asInt()).isEqualTo(1);
            assertThat(firstDay.path("source_mode").asText()).isEqualTo("live");
            assertThat(firstDay.path("simulated").asBoolean()).isFalse();
            assertThat(firstDay.path("generated_at").asLong()).isPositive();
            assertThat(firstDay.path("availability").path("by_duration").path("status").asText()).isEqualTo("UNAVAILABLE");
            assertThat(firstDay.path("by_duration").isEmpty()).isTrue();
            assertThat(firstDay.path("alt_total").asInt()).isZero();
            assertThat(firstDay.path("availability").path("alt_bands").path("missing_count").asInt()).isEqualTo(1);
            JsonNode nextDay = data(mvc.perform(get("/api/v1/stats/operations")
                    .param("from", "2040-01-02").param("to", "2040-01-02").header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(nextDay.path("summary").path("total").asInt()).isZero();
        } finally { jdbc.update("delete from target where target_id=?", id); }
    }

    @Test
    void operationsAggregatesBusinessFactsAndDeviceCounts() throws Exception {
        String token = login("admin1", "changeme");
        JsonNode data = data(mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))

                .andExpect(jsonPath("$.data.simulated").value(false))
                .andReturn().getResponse().getContentAsString());

        int total = data.path("summary").path("total").asInt();
        int illegal = data.path("summary").path("illegal").asInt();
        int punish = data.path("summary").path("punish").asInt();
        int highRisk = data.path("summary").path("high_risk").asInt();
        assertThat(total).isGreaterThanOrEqualTo(0);
        assertThat(illegal).isBetween(0, total);
        assertThat(highRisk).isBetween(0, total);
        assertThat(punish).isGreaterThanOrEqualTo(0);
        assertThat(data.path("days").size()).isEqualTo(30);
        assertThat(sum(data.path("days"), "total")).isEqualTo(total);
        assertThat(sum(data.path("days"), "illegal")).isEqualTo(illegal);
        assertThat(sum(data.path("days"), "punish")).isEqualTo(punish);
        assertThat(sum(data.path("regions"), "total")).isEqualTo(total);
        assertThat(sum(data.path("regions"), "punish")).isEqualTo(punish);
        assertThat(sum(data.path("by_type"), "value")).isEqualTo(total);
        assertThat(sum(data.path("by_risk"), "value")).isEqualTo(total);
        assertThat(data.path("by_duration").isEmpty()).isTrue();
        assertThat(data.path("by_track").isEmpty()).isTrue();
        assertThat(sum(data.path("alt_bands"), "value")).isEqualTo(data.path("alt_total").asInt());
        assertThat(data.path("alt_total").asInt()).isBetween(0,total);
        assertThat(sum(data.path("by_penalty"), "value")).isLessThanOrEqualTo(punish);
        assertThat(data.path("by_risk").size()).isEqualTo(5);
        assertThat(data.path("regions").size()).isGreaterThanOrEqualTo(6);
        assertThat(data.path("partners").size()).isBetween(0, 5);

        // 统计口径：正式接入设备加上报过的设备模拟器设备（StatisticsScope），不含系统自带的演示样例设备。
        int dbDevices = jdbc.queryForObject("select count(*) from ops_device d where d.deleted_at is null and ((d.source_mode='live' and d.simulated=FALSE)"
                + " or (d.source_mode='replay' and exists (select 1 from ops_device_state s where s.device_id=d.device_id"
                + " and (s.last_heartbeat_at is not null or s.observed_at is not null))))", Integer.class);
        assertThat(data.path("devices").path("total").asInt()).isEqualTo(dbDevices);
        assertThat(data.path("devices").path("online").asInt()).isBetween(0, dbDevices);
    }

    @Test
    void dateRangeFiltersAndRejectsInvalidBounds() throws Exception {
        String token = login("admin1", "changeme");
        JsonNode full = data(mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String from = full.path("from").asText();
        String to = full.path("to").asText();
        JsonNode oneDay = data(mvc.perform(get("/api/v1/stats/operations")
                        .param("from", to).param("to", to)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(oneDay.path("from").asText()).isEqualTo(to);
        assertThat(oneDay.path("to").asText()).isEqualTo(to);
        assertThat(oneDay.path("days").size()).isEqualTo(1);
        assertThat(oneDay.path("summary").path("total").asInt())
                .isLessThanOrEqualTo(full.path("summary").path("total").asInt());

        mvc.perform(get("/api/v1/stats/operations").param("from", to).param("to", from)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/stats/operations").param("from", "2026-13-01")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/stats/operations/export.csv").param("from", to).param("to", from)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void exportCsvMatchesOperationsAndWritesAudit() throws Exception {
        String token = login("admin1", "changeme");
        JsonNode data = data(mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        int total = data.path("summary").path("total").asInt();
        int punish = data.path("summary").path("punish").asInt();
        String from = data.path("from").asText();
        String to = data.path("to").asText();

        String csv = mvc.perform(get("/api/v1/stats/operations/export.csv")
                        .param("from", from).param("to", to)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"operations-stats.csv\""))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("\"分类\",\"项目\",\"指标\",\"数值\"");
        assertThat(csv).contains("\"总览\",\"合计\",\"新增目标数\",\"" + total + "\"");
        assertThat(csv).contains("\"总览\",\"合计\",\"处罚案件数\",\"" + punish + "\"");
        assertThat(csv).contains("\"元数据\",\"统计区间\",\"开始日期\",\"" + from + "\"");
        // 新-2 第 4 点：目标的风险等级是空中异物这类风险，不是告警等级；页面、口径说明和导出都写明“异物”。
        int highRisk = data.path("summary").path("high_risk").asInt();
        assertThat(csv).contains("\"总览\",\"合计\",\"异物高风险目标数\",\"" + highRisk + "\"").doesNotContain("\"高风险目标数\"");
        assertThat(csv).contains("\"异物风险等级\",\"未识别\"");
        assertThat(data.path("availability").path("high_risk").path("reason").asText()).contains("空中异物", "不是告警等级");
        assertThat(data.path("availability").path("by_risk").path("reason").asText()).contains("空中异物", "不是告警等级");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action='stats_export_requested' and module_code='statistics' and detail like ?",
                Integer.class, "%from=" + from + "; to=" + to + "%")).isGreaterThan(0);
    }

    @Test
    void reportPreviewUsesNaturalMonthAndRejectsInvalidSelections() throws Exception {
        String token = login("admin1", "changeme");
        LocalDate today = LocalDate.now(ReportingService.ZONE);
        JsonNode preview = data(mvc.perform(get("/api/v1/stats/reports/preview")
                        .param("report_type", "MONTHLY").param("anchor_date", today.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_type").value("MONTHLY"))
                .andExpect(jsonPath("$.data.anchor_date").value(today.toString()))
                .andExpect(jsonPath("$.data.report.from").value(today.withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.data.report.to").value(today.toString()))
                .andReturn().getResponse().getContentAsString());
        assertThat(preview.path("period_label").asText()).isNotBlank();
        assertThat(preview.path("generated_at").asLong()).isPositive();

        mvc.perform(get("/api/v1/stats/reports/preview")
                        .param("report_type", "YEARLY").param("anchor_date", today.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/stats/reports/preview")
                        .param("report_type", "DAILY").param("anchor_date", today.plusDays(1).toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void excelExportContainsExistingSheetsPlusObservationSheetAndAudit() throws Exception {
        String token = login("admin1", "changeme");
        LocalDate today = LocalDate.now(ReportingService.ZONE);
        byte[] bytes = mvc.perform(get("/api/v1/stats/reports/export.xlsx")
                        .param("report_type", "DAILY").param("anchor_date", today.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 'P', (byte) 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(workbook.getSheetName(0)).isEqualTo("报表摘要");
            assertThat(workbook.getSheetName(1)).isEqualTo("每日趋势");
            assertThat(workbook.getSheetName(2)).isEqualTo("分类分布");
            assertThat(workbook.getSheetName(3)).isEqualTo("区域与处置");
            assertThat(workbook.getSheetName(4)).isEqualTo("监测时长与里程");
            assertThat(workbook.getSheet("每日趋势").getRow(2).getCell(0).getStringCellValue()).isEqualTo("日期");
            assertThat(workbook.getSheet("每日趋势").getRow(3).getCell(1).getCellType().name()).isEqualTo("NUMERIC");
            assertThat(workbook.getSheet("分类分布").getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(workbook.getSheet("每日趋势").getPaneInformation()).isNotNull();
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action='stats_export_requested' and module_code='statistics' and detail like ?",
                Integer.class, "%format=XLSX; report_type=DAILY%")).isGreaterThan(0);
    }

    @Test
    void roleWithoutStatisticsCannotReadOperations() throws Exception {
        String admin = login("admin1", "changeme");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode catalog = data(mvc.perform(get("/api/v1/permissions/catalog").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ObjectNode roleBody = json.createObjectNode();
        ArrayNode permissions = roleBody.putArray("permissions");
        for (JsonNode item : catalog) {
            String code = item.path("permission_code").asText();
            boolean devices = "devices".equals(code);
            permissions.addObject().put("permission_code", code).put("level", devices ? "READ" : "NONE")
                    .put("menu_enabled", devices);
        }
        roleBody.put("name", "统计测试角色-" + suffix).put("description", "无统计分析权限");
        String roleCode = data(mvc.perform(post("/api/v1/roles").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "stat-role-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(roleBody)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("role_code").asText();
        String orgId = jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'", String.class);
        String account = "itest-stat-" + suffix;
        ObjectNode createUser = json.createObjectNode().put("account", account).put("name", "统计越权用户")
                .put("org_id", orgId).put("role_code", roleCode).put("temporary_password", TEMP_PASSWORD);
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "stat-user-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(createUser)))
                .andExpect(status().isOk());
        String first = login(account, TEMP_PASSWORD);
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"" + TEMP_PASSWORD + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());
        String userSession = login(account, NEW_PASSWORD);
        mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/stats/operations/export.csv").header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/stats/reports/preview")
                        .param("report_type", "DAILY").param("anchor_date", LocalDate.now(ReportingService.ZONE).toString())
                        .header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/stats/reports/export.xlsx")
                        .param("report_type", "DAILY").param("anchor_date", LocalDate.now(ReportingService.ZONE).toString())
                        .header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static int sum(JsonNode array, String field) {
        int total = 0;
        for (JsonNode item : array) total += item.path(field).asInt();
        return total;
    }

    private String login(String account, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("account", account).put("password", password).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return data(body).path("session_id").asText();
    }

    private JsonNode data(String body) throws Exception { return json.readTree(body).path("data"); }

    private static String bearer(String token) { return "Bearer " + token; }
}
