package com.uav.lowaltitude.modules.risk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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

import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService.TrustedRiskFact;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RiskReadApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RiskIngestionService ingestion;

    private String session;

    @BeforeEach
    void fixture() {
        session = reader("ASSIGNED", true, false);
        grant(session,"flight:read");
    }

    @Test
    void listUsesFixedOrderingPagingAndExactFilters() throws Exception {
        insertRisk("risk-read-old", "src-risk-old", "MEDIUM", "PENDING_VERIFICATION", "seed-stage3-plan-legal",
                "seed-stage3-rv-legal", "seed-stage3-org", "seed-stage3-district", 1_000, 2_000, null, null);
        insertRisk("risk-read-new", "src-risk-new", "HIGH", "PENDING_VERIFICATION", "seed-stage3-plan-legal",
                "seed-stage3-rv-legal", "seed-stage3-org", "seed-stage3-district", 3_000, 4_000, null, null);

        mvc.perform(get("/api/v1/risks?severity=HIGH&plan_id=seed-stage3-plan-legal&occurred_from=3000&occurred_to=3001&page=1&size=1")
                        .header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].risk_id").value("risk-read-new"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void unknownRepeatedAndLegacyPageSizeParametersAreRejected() throws Exception {
        for (String query : new String[]{"wat=1", "severity=HIGH&severity=LOW", "severity=URGENT", "source_mode=browser", "page_size=20", "occurred_from=1&occurred_to=1"}) {
            mvc.perform(get("/api/v1/risks?" + query).header("Authorization", bearer(session)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void assignedScopeCannotReadAnotherScopeAndCountMatchesItems() throws Exception {
        insertRisk("risk-visible", "src-visible", "LOW", "PENDING_VERIFICATION", "seed-stage3-plan-legal",
                "seed-stage3-rv-legal", "seed-stage3-org", "seed-stage3-district", 5_000, 5_100, null, null);
        insertRisk("risk-hidden", "src-hidden", "CRITICAL", "PENDING_VERIFICATION", "seed-stage3-plan-cross-scope",
                "seed-stage3-rv-cross-scope", "seed-stage3-other-org", "seed-stage3-other-district", 6_000, 6_100, null, null);
        mvc.perform(get("/api/v1/risks?plan_id=seed-stage3-plan-cross-scope").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/risks/risk-hidden").header("Authorization", bearer(session)))
                .andExpect(status().isNotFound());
    }

    @Test
    void allScopeStillCannotReadRiskWhoseDirectoryRootIsDisabled() throws Exception {
        String all=reader("ALL",true,false);
        insertRisk("risk-disabled-root","src-disabled-root","HIGH","PENDING_VERIFICATION","seed-stage3-plan-legal",
                "seed-stage3-rv-legal","seed-stage3-org","seed-stage3-district",6_200,6_300,null,null);
        jdbc.update("update app_org set enabled=false where org_id='seed-stage3-org'");
        mvc.perform(get("/api/v1/risks/risk-disabled-root").header("Authorization",bearer(all))).andExpect(status().isNotFound());
    }

    @Test
    void twoGrantedTuplesCannotBeCrossJoinedIntoAThirdScope() throws Exception {
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) select user_id,'seed-stage3-other-org','seed-stage3-other-district' from app_session where session_id=?", session);
        String suffix=UUID.randomUUID().toString().substring(0,8),route="route-cross-"+suffix,version="rv-cross-"+suffix,plan="plan-cross-"+suffix;
        Timestamp at=ts(9_000);
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'seed-stage3-source','mock','seed-stage3-org','seed-stage3-other-district',?,?,0)",route,"R-"+suffix,"交叉范围航线",at,at);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values (?,?,1,cast('SRID=4326;LINESTRING (118 37,118.1 37.1)' as geometry),100,?,?)",version,route,at,at);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING','seed-stage3-source','mock',?,?,?,'seed-stage3-org','seed-stage3-other-district',?,?,0)",plan,"P-"+suffix,at,ts(10_000),version,at,at);
        insertRisk("risk-cross-tuple", "src-cross-tuple", "HIGH", "PENDING_VERIFICATION", plan, version,
                "seed-stage3-org", "seed-stage3-other-district", 9_000, 9_100, null, null);
        mvc.perform(get("/api/v1/risks/risk-cross-tuple").header("Authorization", bearer(session))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/risks?owner_org_id=seed-stage3-org&district_id=seed-stage3-other-district")
                        .header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void trustedIngestionRejectsPlanRouteAndAssessmentMismatches() {
        var now=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        TrustedRiskFact wrongRoute=new TrustedRiskFact("seed-stage3-source","INGEST-WRONG-ROUTE","seed-stage3-plan-legal",
                "seed-stage3-rv-illegal",null,null,null,"ROUTE_DEVIATION","HIGH","ROUTE_MISMATCH","错误航线版本",now,now,null,null,"mock");
        TrustedRiskFact wrongAssessment=new TrustedRiskFact("seed-stage3-source","INGEST-WRONG-ASSESSMENT","seed-stage3-plan-legal",
                "seed-stage3-rv-legal","seed-stage3-assessment-illegal",null,null,"ROUTE_DEVIATION","HIGH","ASSESSMENT_MISMATCH","错误研判引用",now,now,null,null,"mock");
        TrustedRiskFact wrongTrackScope=new TrustedRiskFact("seed-stage3-source","INGEST-WRONG-TRACK","seed-stage3-plan-legal",
                "seed-stage3-rv-legal",null,"seed-target-uav-wgs84","seed-track-uav-001","ROUTE_DEVIATION","HIGH","TRACK_SCOPE","跨范围轨迹引用",now,now,null,null,"mock");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class,()->ingestion.ingest(wrongRoute)).getMessage()).contains("航线版本");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class,()->ingestion.ingest(wrongAssessment)).getMessage()).contains("研判");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class,()->ingestion.ingest(wrongTrackScope)).getMessage()).contains("目标");
    }

    @Test
    void unauthorizedReadWinsOverInvalidParametersAndPath() throws Exception {
        String denied=reader("ALL",false,false);
        mvc.perform(get("/api/v1/risks?page=bad&page=again").header("Authorization",bearer(denied))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/risks/   ").header("Authorization",bearer(denied))).andExpect(status().isForbidden());
    }

    @Test
    void unknownHeightRemainsUnknownAndNeverBecomesAFrontendSafetyVerdict() throws Exception {
        insertRisk("risk-height-unknown", "src-height-unknown", "HIGH", "PENDING_VERIFICATION", "seed-stage3-plan-undetermined",
                "seed-stage3-rv-undetermined", "seed-stage3-org", "seed-stage3-district", 7_000, 7_100, null, null);
        mvc.perform(get("/api/v1/risks/risk-height-unknown").header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.height_relation").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.observed_altitude_m").doesNotExist())
                .andExpect(jsonPath("$.data.observed_altitude_datum").doesNotExist());
    }

    @Test
    void riskReadAloneDoesNotExposeIdentifiersFromOtherPermissionDomains() throws Exception {
        String riskOnly=reader("ASSIGNED",true,false);
        insertRisk("risk-reference-mask", "src-reference-mask", "HIGH", "PENDING_VERIFICATION", "seed-stage3-plan-legal",
                "seed-stage3-rv-legal", "seed-stage3-org", "seed-stage3-district", 7_200, 7_300, null, null);
        jdbc.update("update target set owner_org_id='seed-stage3-org',district_id='seed-stage3-district' where target_id='seed-target-uav-wgs84'");
        jdbc.update("update flight_risk set assessment_id='seed-stage3-assessment-legal',target_id='seed-target-uav-wgs84',track_id='seed-track-uav-001' where risk_id='risk-reference-mask'");
        mvc.perform(get("/api/v1/risks/risk-reference-mask").header("Authorization",bearer(riskOnly)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.plan_id").doesNotExist())
                .andExpect(jsonPath("$.data.route_version_id").doesNotExist())
                .andExpect(jsonPath("$.data.assessment_id").doesNotExist())
                .andExpect(jsonPath("$.data.target_id").doesNotExist())
                .andExpect(jsonPath("$.data.track_id").doesNotExist())
                .andExpect(jsonPath("$.data.allowed_actions").isEmpty());
        String linked=reader("ASSIGNED",true,false);
        for(String permission:new String[]{"flight:read","route:read","assessment:read","target:read"})grant(linked,permission);
        mvc.perform(get("/api/v1/risks/risk-reference-mask").header("Authorization",bearer(linked)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.plan_id").value("seed-stage3-plan-legal"))
                .andExpect(jsonPath("$.data.route_version_id").value("seed-stage3-rv-legal"))
                .andExpect(jsonPath("$.data.assessment_id").value("seed-stage3-assessment-legal"))
                .andExpect(jsonPath("$.data.target_id").value("seed-target-uav-wgs84"))
                .andExpect(jsonPath("$.data.track_id").value("seed-track-uav-001"));
    }

    @Test
    void planFilterRequiresFlightReadAfterRiskRead() throws Exception {
        String riskOnly=reader("ASSIGNED",true,false);
        mvc.perform(get("/api/v1/risks?plan_id=seed-stage3-plan-legal").header("Authorization",bearer(riskOnly)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/risks?plan_id=x&occurred_from=bad").header("Authorization",bearer(riskOnly)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/risks?plan_id=x&plan_id=y").header("Authorization",bearer(riskOnly)))
                .andExpect(status().isForbidden());
        String withFlight=reader("ASSIGNED",true,false);grant(withFlight,"flight:read");
        mvc.perform(get("/api/v1/risks?plan_id=x&occurred_from=bad").header("Authorization",bearer(withFlight)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sourceIdentityIsDeduplicatedByDatabase() {
        var now=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        TrustedRiskFact fact=new TrustedRiskFact("seed-stage3-source","same-source-risk","seed-stage3-plan-legal",
                "seed-stage3-rv-legal",null,null,null,"ROUTE_DEVIATION","HIGH","ROUTE_DEVIATION","同源幂等事实",now,now,null,null,"mock");
        assertThat(ingestion.ingest(fact)).isEqualTo(ingestion.ingest(fact));
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where source_id='seed-stage3-source' and source_risk_id='same-source-risk'", Long.class)).isEqualTo(1L);
    }

    /* ---------- 阶段 9 追加：新增过滤不改变既有默认列表 ---------- */

    @Test
    void stage9FiltersDoNotChangeTheDefaultRiskList() throws Exception {
        insertRisk("risk-stage9-a", "src-stage9-a", "HIGH", "PENDING_VERIFICATION", "seed-stage3-plan-legal",
                "seed-stage3-rv-legal", "seed-stage3-org", "seed-stage3-district", 11_000, 11_100, null, null);
        // 默认列表（不带阶段 9 过滤）与阶段 4 行为一致：作业风险照常可见，且没有空间事实字段。
        mvc.perform(get("/api/v1/risks?occurred_from=11000&occurred_to=11001").header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].risk_id").value("risk-stage9-a"))
                .andExpect(jsonPath("$.data.items[0].risk_type").value("ROUTE_DEVIATION"))
                .andExpect(jsonPath("$.data.items[0].space_fact").doesNotExist());
        // 按类型过滤：既有类型仍在，异物类型为空（本夹具没有异物风险）。
        mvc.perform(get("/api/v1/risks?risk_type=ROUTE_DEVIATION&occurred_from=11000&occurred_to=11001").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/risks?risk_type=SPACE_OBJECT&occurred_from=11000&occurred_to=11001").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        // 细类过滤只作用于有空间事实的风险，不会把作业风险带出来。
        mvc.perform(get("/api/v1/risks?object_subtype=BIRD_FLOCK&occurred_from=11000&occurred_to=11001").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        // risk_type 在库里是自由文本：未出现过的取值只是筛不到，不是参数错误。
        mvc.perform(get("/api/v1/risks?risk_type=UNKNOWN_TYPE&occurred_from=11000&occurred_to=11001").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    private void insertRisk(String id, String sourceRiskId, String severity, String state, String plan, String routeVersion,
            String org, String district, long occurred, long received, Double altitude, String datum) {
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,observed_altitude_m,observed_altitude_datum,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,'seed-stage3-source',?,?,?,?,?,?,'ROUTE_DEVIATION','服务端保存的风险依据',?,?,?,?,'UNKNOWN','mock',?,?,?, ?,0)",
                id, sourceRiskId, plan, routeVersion, "ROUTE_DEVIATION", severity, state, ts(occurred), ts(received), altitude, datum,
                org, district, ts(received), ts(received));
    }

    private String reader(String scope, boolean read, boolean verify) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-RISK-" + suffix;
        String user = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        if (read) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'risk:read','READ',false,current_timestamp)", role);
        if (verify) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'risk:verify','OP',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)", user, "risk-" + suffix, "风险测试", role, scope);
        if ("ASSIGNED".equals(scope)) jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,'seed-stage3-org','seed-stage3-district')", user);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private void grant(String token,String permission){jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) select u.role_code,?,'READ',false,current_timestamp from app_session s join app_user u on u.user_id=s.user_id where s.session_id=?",permission,token);}

    private static Timestamp ts(long millis) { return Timestamp.from(Instant.ofEpochMilli(millis)); }
    private static String bearer(String token) { return "Bearer " + token; }
}
