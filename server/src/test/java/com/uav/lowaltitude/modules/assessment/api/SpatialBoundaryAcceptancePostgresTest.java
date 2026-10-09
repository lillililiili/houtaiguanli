package com.uav.lowaltitude.modules.assessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService;

/** R34/R37: isolated facts go through the real PostGIS adapter and C01/C02/C03 evaluation. */
@Transactional
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class SpatialBoundaryAcceptancePostgresTest extends SimulatorScenarioPostgresTest {
    @Autowired RuleRunService runs;
    @Autowired com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeService automaticDecisions;
    @Autowired com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility automaticEligibility;
    @Autowired com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository automaticRuns;
    @Autowired com.uav.lowaltitude.modules.alarm.application.AlarmRuleCounter automaticCounter;
    @Autowired com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository advisory;
    @Autowired com.uav.lowaltitude.platform.time.AppClock clock;
    @SpyBean com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy automaticPolicy;
    static final OffsetDateTime START=OffsetDateTime.parse("2026-09-05T10:00:00+08:00");
    static final String POINT="SRID=4326;POINT(118.9 37.8)";
    static final String AREA="SRID=4326;MULTIPOLYGON(((118.89 37.79,118.91 37.79,118.91 37.81,118.89 37.81,118.89 37.79)))";

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void amslOnlyAgainstAglAirspaceRemainsUndeterminedAndEnabledAutomationCannotAct() throws Exception {
        // Commit synthetic facts so the independent runtime snapshot can read them. Only the engine
        // enable switch is overridden; the real spatial, legality and eligibility algorithms run.
        doReturn(true).when(automaticPolicy).enabled();
        OffsetDateTime observed=clock.now().atOffset(java.time.ZoneOffset.UTC).minusSeconds(1);
        // Plan range uses AMSL; only the active airspace requires the absent AGL fact.
        String target=facts(observed,observed,true);
        String areaVersion=airspace("ALTITUDE_LIMIT","AGL",100,observed.minusMinutes(1),observed.plusMinutes(1));
        var run=runs.start(LocalStage7RuleEngineSeeder.RULE_SET_CODE,RunMode.ACTIVE,"MANUAL",null,null,observed);
        var result=runs.evaluateOne(run,new Subject(SubjectKind.TARGET,target,null,null,null),observed,null);
        assertThat(result.freshness()).isEqualTo(Freshness.FRESH);
        assertThat(result.planMatchCode()).isEqualTo(PlanMatchCode.FULL);
        assertThat(result.legalStatus()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(result.unknownReasons()).contains("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        assertThat(result.violationReasons()).doesNotContain("AIRSPACE_ALTITUDE_EXCEEDED");
        assertThat(result.alarmCreated()).isFalse();

        String alarm=id(),event=id();
        jdbc.update("insert into alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values(?,?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",alarm,target,LocalStage7RuleEngineSeeder.SOURCE_ID,alarm,observed,observed,LocalStage7RuleEngineSeeder.ORG,LocalStage7RuleEngineSeeder.DISTRICT,observed);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'CONFIRMED',?,?,?,?,0)",event,alarm,LocalStage7RuleEngineSeeder.ORG,LocalStage7RuleEngineSeeder.DISTRICT,observed,observed);
        jdbc.update("delete from automation_rule_condition where category='counter'");
        jdbc.update("update automation_rule_group set version=version+1,scope_mode='ALL',schedule_mode='ALL_DAY',wait_seconds=15 where category='counter'");
        for (String item:List.of("counterFreshness","position")) {
            jdbc.update("insert into automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) values(?,'counter',?,?,?,0,true,?,?,'QA-R35')",id(),"QA-R35-"+item,item,"position".equals(item)?"位置已确认且仍有效":"30",clock.nowMillis(),clock.nowMillis());
        }
        assertThat(jdbc.queryForObject("select count(*) from automation_rule_condition where category='counter' and enabled=true",Integer.class)).isEqualTo(2);
        automaticDecisions.evaluate("counter",event);
        var state=automaticRuns.state("counter",event);
        assertThat(state.status()).isEqualTo("WAITING");
        var automaticRun=automaticRuns.run(state.runId());
        var conditions=json.readTree(automaticRun.conditions());
        var conditionStates=new java.util.LinkedHashMap<String,String>();
        for(var condition:conditions) conditionStates.put(condition.path("name").asText(),condition.path("result").asText());
        assertThat(conditionStates).containsEntry("QA-R35-counterFreshness","PASS").containsEntry("QA-R35-position","UNKNOWN");
        assertThat(automaticEligibility.check("counter",event).status()).isEqualTo("WAITING");
        assertThat(automaticEligibility.allowsRun("counter",event,state.runId())).isFalse();
        assertThat(advisory.counterBlockReason(event)).contains("当前违规研判");
        int commands=jdbc.queryForObject("select count(*) from device_command",Integer.class);
        automaticCounter.launchIfPassed(event,state.runId());
        automaticCounter.launchIfPassed(event,state.runId());
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?",Integer.class,event)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from device_command",Integer.class)).isEqualTo(commands);
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?",Integer.class,event)).isZero();
        // No immutable rule_evaluation row is edited to manufacture either conclusion.
        save("R35",target,result.evaluationId(),areaVersion);
        Path output=Path.of("target","spatial-boundary-evidence");
        Files.writeString(output.resolve("R35-enabled-automation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "synthetic_fixture",true,"event_id",event,"target_id",target,"evaluation_id",result.evaluationId(),
                "runtime_run_id",state.runId(),"group_version",state.version(),"runtime_status",state.status(),
                "conditions",conditions,"counter_block_reason",advisory.counterBlockReason(event),"device_command_delta",0)));
    }

    @ParameterizedTest @ValueSource(strings={"AGL","AMSL"})
    void dualHeightUsesOnlyTheMatchingAirspaceDatum(String datum) throws Exception {
        String target=facts(START);
        String version=airspace("ALTITUDE_LIMIT",datum,"AGL".equals(datum)?100:120,START.minusMinutes(1),START.plusMinutes(1));
        var result=evaluate(target,START);
        assertThat(result.planMatchCode()).isEqualTo(PlanMatchCode.FULL);
        assertThat(result.unknownReasons()).isEmpty();
        if("AGL".equals(datum)) {
            assertThat(result.violationReasons()).isEmpty();
            assertThat(result.legalStatus()).isEqualTo(LegalStatus.LEGAL);
        } else {
            assertThat(result.violationReasons()).containsExactly("AIRSPACE_ALTITUDE_EXCEEDED");
            assertThat(result.legalStatus()).isEqualTo(LegalStatus.ILLEGAL);
        }
        save("R34-"+datum,target,result.evaluationId(),version);
    }

    @ParameterizedTest @ValueSource(longs={-1,0,9999,10000})
    void spatialValidityUsesLeftClosedRightOpenInstants(long deltaMillis) throws Exception {
        OffsetDateTime observed=START.plusNanos(deltaMillis*1_000_000);
        String target=facts(observed);
        String version=airspace("TEMPORARY_CONTROL",null,null,START,START.plusSeconds(10));
        var result=evaluate(target,observed.withOffsetSameInstant(java.time.ZoneOffset.UTC));
        assertThat(result.unknownReasons()).isEmpty();
        boolean effective=deltaMillis>=0 && deltaMillis<10000;
        assertThat(result.legalStatus()).isEqualTo(effective?LegalStatus.ILLEGAL:LegalStatus.LEGAL);
        assertThat(result.violationReasons().contains("TEMPORARY_RESTRICTION_ACTIVE")).isEqualTo(effective);
        var row=jdbc.queryForMap("select observed_at,as_of,hit_details from rule_evaluation where evaluation_id=?",result.evaluationId());
        assertThat(jdbc.queryForObject("select observed_at from rule_evaluation where evaluation_id=?",OffsetDateTime.class,result.evaluationId()).toInstant()).isEqualTo(observed.toInstant());
        assertThat(jdbc.queryForObject("select as_of from rule_evaluation where evaluation_id=?",OffsetDateTime.class,result.evaluationId()).toInstant()).isEqualTo(observed.toInstant());
        assertThat(row.get("hit_details").toString().contains(version)).isEqualTo(effective);
        save("R37-"+deltaMillis,target,result.evaluationId(),version);
    }

    protected com.uav.lowaltitude.modules.assessment.engine.LegalityEvaluationService.EvaluationResult evaluate(String target,OffsetDateTime asOf) {
        var run=runs.start(LocalStage7RuleEngineSeeder.RULE_SET_CODE,RunMode.ACTIVE,"REPLAY","qa-spatial-"+id(),null,asOf);
        return runs.evaluateOne(run,new Subject(SubjectKind.TARGET,target,null,null,null),asOf,null);
    }

    private String facts(OffsetDateTime observed) {
        return facts(observed,START,false);
    }

    protected String facts(OffsetDateTime observed,OffsetDateTime anchor,boolean amslOnly) {
        return facts(observed,anchor,amslOnly,POINT,100);
    }

    protected String facts(OffsetDateTime observed,OffsetDateTime anchor,boolean amslOnly,String point,int corridorWidth) {
        String target=id(),link=id(),track=id(),route=id(),rv=id(),plan=id(),sn="QA-"+id();
        String org=LocalStage7RuleEngineSeeder.ORG,district=LocalStage7RuleEngineSeeder.DISTRICT,source=LocalStage7RuleEngineSeeder.SOURCE_ID;
        jdbc.update("insert into target(target_id,target_no,object_type_code,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,'UAV',?,?,?,'mock',?,?,?,?)",target,target,sn,observed.minusSeconds(20),observed,org,district,observed,observed);
        jdbc.update("insert into target_source_link(link_id,target_id,source_id,source_session_key,external_target_id,protocol_version,created_at) values(?,?,?,?,?,'1.0',?)",link,target,source,id(),target,observed);
        jdbc.update("insert into target_latest_state(target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,observed_at,received_at,unknown_fields,created_at,updated_at) values(?,CAST(? AS GEOMETRY),130,?,8,90,0.95,0.95,?,?,'[]',?,?)",target,point,amslOnly?null:80,observed,observed,observed,observed);
        // 飞手与目标同点：本类只验空域拓扑与时间边界，C02-6 算得出距离且在阈值内，研判明细里不带"是否经批准请核实"的提示。
        // 新-29 起飞手距离只作提示、不改变结论（10-07 那条"单独超视距判 ILLEGAL"已取消），同点只是让明细更干净。
        jdbc.update("update target_latest_state set pilot_location=CAST(? AS GEOMETRY),pilot_observed_at=? where target_id=?",point,observed,target);
        jdbc.update("insert into track(track_id,target_id,link_id,external_track_id,started_at,created_at) values(?,?,?,?,?,?)",track,target,link,track,observed.minusSeconds(20),observed);
        for(int i=0;i<5;i++) {
            var at=observed.minusSeconds(20-i*5);
            jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at) values(?,?,?,?,?,CAST(? AS GEOMETRY),130,?,?)",id(),track,i,at,at,point,amslOnly?null:80,at);
        }
        jdbc.update("insert into route(route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,true,?,'mock',?,?,?,?)",route,route,"QA空间边界航线",source,org,district,observed,observed);
        jdbc.update("insert into route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) values(?,?,1,ST_GeomFromText('LINESTRING(118.89 37.8,118.91 37.8)',4326),?,0,200,?,?,?)",rv,route,corridorWidth,amslOnly?"AMSL":"AGL",anchor.minusHours(1),observed);
        jdbc.update("insert into flight_plan(plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at) values(?,?,'PENDING',?,'mock',?,?,?,?,?,?,?,?)",plan,plan,source,sn,anchor.minusMinutes(30),anchor.plusMinutes(30),rv,org,district,observed,observed);
        return target;
    }

    private String airspace(String kind,String datum,Integer limit,OffsetDateTime from,OffsetDateTime to) {
        String area=id(),version=id();
        jdbc.update("insert into airspace(airspace_id,airspace_no,name,source_id,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'mock',?,?,?,?)",area,area,"QA空间边界",LocalStage7RuleEngineSeeder.SOURCE_ID,LocalStage7RuleEngineSeeder.ORG,LocalStage7RuleEngineSeeder.DISTRICT,START,START);
        jdbc.update("insert into airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,created_at) values(?,?,1,?,CAST(? AS GEOMETRY),?,?,?,?,?,?)",version,area,kind,AREA,limit==null?null:0,limit,datum,from,to,START);
        return version;
    }

    private void save(String label,String target,String evaluation,String version) throws Exception {
        Path output=Path.of("target","spatial-boundary-evidence");Files.createDirectories(output);
        Files.writeString(output.resolve(label+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
            "synthetic_fixture",true,"timezone","Asia/Shanghai (+08:00) versus same instant UTC",
            "target",jdbc.queryForMap("select target_id,height_agl_m,altitude_amsl_m,observed_at,received_at from target_latest_state where target_id=?",target),
            "airspace",jdbc.queryForMap("select airspace_version_id,kind_code,altitude_datum,max_altitude_m,valid_from,valid_to from airspace_version where airspace_version_id=?",version),
            "evaluation",jdbc.queryForMap("select evaluation_id,rule_set_version_id,observed_at,as_of,evaluated_at,legal_status,hit_details,violation_reasons,unknown_reasons from rule_evaluation where evaluation_id=?",evaluation))));
    }
    private static String id(){return UUID.randomUUID().toString();}
}
