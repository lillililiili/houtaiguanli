package com.uav.lowaltitude.modules.assessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.LegalStatus;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;

/** Authorized synthetic upstream airspace inputs and real PostGIS/engine conclusions. */
@AutoConfigureMockMvc
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class SpatialVersionLifecyclePostgresTest extends SpatialBoundaryAcceptancePostgresTest {
    @Autowired MockMvc mvc;

    @Test
    void authorizedVersionSuccessionPreservesOldEvaluationAndFrozenHandoffAndRejectsConflicts() throws Exception {
        String token=operator(),no="QA-R38-"+UUID.randomUUID();
        var firstInput=airspaceInput(no,1,START.minusMinutes(1),"TEMPORARY_CONTROL");
        var firstReceipt=push(token,firstInput,200);
        String firstVersion=version(no,1);
        String oldTarget=facts(START,START,false);
        var first=evaluate(oldTarget,START);
        assertThat(first.legalStatus()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(first.violationReasons()).containsExactly("TEMPORARY_RESTRICTION_ACTIVE");
        assertThat(first.alarmCreated()).isTrue();
        assertThat(first.alarmId()).isNotBlank();
        String event=jdbc.queryForObject("select event_id from uav_event where alarm_id=?",String.class,first.alarmId());
        long eventVersion=jdbc.queryForObject("select version from uav_event where event_id=?",Long.class,event);
        // 回放事实的观测时刻早于当前时间；人工核实为属实要求目标数据仍在有效时长内，这里补一条当前观测。
        jdbc.update("update target_latest_state set observed_at=?,received_at=? where target_id=?",clock.now().atOffset(java.time.ZoneOffset.UTC),clock.now().atOffset(java.time.ZoneOffset.UTC),oldTarget);
        var verification=json.createObjectNode().put("conclusion","CONFIRMED").put("expected_version",eventVersion)
                .put("note","隔离 QA 合成事实人工核实；研判 "+first.evaluationId()+"；旧空间版本依据 "+firstVersion);
        mvc.perform(post("/api/v1/uav-events/{id}/verifications",event).header("Authorization","Bearer "+token)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(verification.toString())).andExpect(status().isOk());
        String recipient=UUID.randomUUID().toString();
        jdbc.update("insert into handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values(?,'隔离版本历史接收方','UAV_PUNISHMENT',true,current_timestamp,current_timestamp)",recipient);
        var handoffInput=json.createObjectNode().put("source_kind","UAV_EVENT").put("source_id",event)
                .put("handoff_type","UAV_PUNISHMENT").put("recipient_id",recipient).put("expected_version",eventVersion+1);
        var created=mvc.perform(post("/api/v1/handoffs").header("Authorization","Bearer "+token)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(handoffInput.toString())).andExpect(status().isCreated()).andReturn().getResponse();
        String handoff=json.readTree(created.getContentAsString()).path("data").path("handoff_id").asText();
        assertThat(handoff).isNotBlank();
        // A real material freeze is sufficient here; unconfigured delivery must not claim delivery or punishment.
        assertThat(json.readTree(created.getContentAsString()).path("data").path("delivery_status").asText()).isEqualTo("PENDING_DELIVERY");
        String frozenBefore=frozen(handoff);
        var materialBefore=handoffMaterial(token,handoff);
        assertThat(materialBefore.path("event").path("event_id").asText()).isEqualTo(event);
        assertThat(materialBefore.path("event").path("alarm_id").asText()).isEqualTo(first.alarmId());
        assertThat(materialBefore.path("event").path("target_id").asText()).isEqualTo(oldTarget);
        assertThat(materialBefore.path("verifications").toString()).contains(firstVersion,first.evaluationId());
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation e join uav_event u on u.alarm_id=e.alarm_id where e.evaluation_id=? and u.event_id=?",Integer.class,first.evaluationId(),event)).isEqualTo(1);
        var evaluationBefore=evaluationRow(first.evaluationId());
        assertThat(evaluationBefore.get("input_snapshot").toString()).contains(firstVersion);
        var firstGeometry=jdbc.queryForMap("select kind_code,ST_AsEWKT(boundary) as boundary,valid_from from airspace_version where airspace_version_id=?",firstVersion);

        OffsetDateTime switchAt=START.plusSeconds(10);
        var secondInput=airspaceInput(no,2,switchAt,"PERMITTED");
        var secondReceipt=push(token,secondInput,200);
        String secondVersion=version(no,2);
        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?",OffsetDateTime.class,firstVersion).toInstant()).isEqualTo(switchAt.toInstant());
        assertThat(jdbc.queryForMap("select kind_code,ST_AsEWKT(boundary) as boundary,valid_from from airspace_version where airspace_version_id=?",firstVersion)).isEqualTo(firstGeometry);
        String newTarget=facts(switchAt,switchAt,false);
        var second=evaluate(newTarget,switchAt);
        assertThat(second.legalStatus()).isEqualTo(LegalStatus.LEGAL);
        assertThat(second.unknownReasons()).isEmpty();
        assertThat(second.violationReasons()).isEmpty();
        var evaluationAfter=evaluationRow(second.evaluationId());
        assertThat(evaluationAfter.get("input_snapshot").toString()).contains(secondVersion).doesNotContain(firstVersion);
        assertThat(evaluationRow(first.evaluationId())).isEqualTo(evaluationBefore);
        assertThat(frozen(handoff)).isEqualTo(frozenBefore);
        assertThat(handoffMaterial(token,handoff)).isEqualTo(materialBefore);

        var backwardInput=airspaceInput(no,3,switchAt.minusSeconds(1),"PROHIBITED");
        var overlap=push(token,backwardInput,409);
        assertThat(overlap.path("error").path("code").asText()).isEqualTo("VERSION_OVERLAP");
        var duplicateRevision=push(token,airspaceInput(no,2,switchAt.plusSeconds(1),"PROHIBITED"),409);
        assertThat(duplicateRevision.path("error").path("code").asText()).isEqualTo("UPSTREAM_REVISION_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.airspace_no=?",Integer.class,no)).isEqualTo(2);
        var afterRejection=evaluate(newTarget,switchAt);
        assertThat(afterRejection.legalStatus()).isEqualTo(LegalStatus.LEGAL);
        assertThat(evaluationRow(afterRejection.evaluationId()).get("input_snapshot").toString()).contains(secondVersion).doesNotContain(firstVersion);
        assertThat(evaluationRow(first.evaluationId())).isEqualTo(evaluationBefore);
        assertThat(frozen(handoff)).isEqualTo(frozenBefore);
        assertThat(handoffMaterial(token,handoff)).isEqualTo(materialBefore);
        assertThat(jdbc.queryForObject("select count(*) from punishment_case where event_id=?",Integer.class,event)).isZero();
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("synthetic_fixture",true);evidence.put("first_input",firstInput);evidence.put("first_receipt",firstReceipt);
        evidence.put("second_input",secondInput);evidence.put("second_receipt",secondReceipt);
        evidence.put("old_evaluation_unchanged",evaluationBefore);evidence.put("new_evaluation",evaluationAfter);
        evidence.put("frozen_material_unchanged",materialBefore);evidence.put("handoff_id",handoff);
        evidence.put("overlap_rejected",overlap);evidence.put("revision_conflict_rejected",duplicateRevision);
        evidence.put("after_rejection",evaluationRow(afterRejection.evaluationId()));
        evidence.put("scope","Authorized API rejects conflicting versions; no synthetic historical corruption or immutable evaluation updates.");
        saveEvidence("R38-version-history",evidence);
    }

    @ParameterizedTest @ValueSource(strings={"INSIDE","OUTSIDE","BOUNDARY"})
    void authorizedWgs84BoundaryInputProducesExplicitThreePointRelations(String pointKind) throws Exception {
        String token=operator(),no="QA-R36-"+UUID.randomUUID();
        var body=airspaceInput(no,1,START.minusMinutes(1),"TEMPORARY_CONTROL");
        mvc.perform(post("/api/v1/local-interface-simulator/airspaces")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isUnauthorized());
        var accepted=push(token,body,200);
        assertThat(accepted.path("data").path("state").asText()).isEqualTo("ACCEPTED");
        String version=jdbc.queryForObject("select v.airspace_version_id from airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.airspace_no=?",String.class,no);
        double longitude=switch(pointKind){case "INSIDE"->118.9;case "OUTSIDE"->118.911;default->118.91;};
        String wkt="SRID=4326;POINT("+longitude+" 37.8)";
        // A wide synthetic route keeps the outside point from accidentally failing C01 deviation.
        // All immutable track/version facts are inserted once; no evaluation is rewritten.
        String target=facts(START,START,false,wkt,1000);
        var result=evaluate(target,START);
        assertThat(result.planMatchCode()).isEqualTo(PlanMatchCode.FULL);
        var topology=jdbc.queryForMap("select ST_Covers(v.boundary,s.location) as covers,ST_Touches(v.boundary,s.location) as touches,ST_Disjoint(v.boundary,s.location) as disjoint,ST_AsGeoJSON(v.boundary) as boundary,ST_X(s.location) as longitude,ST_Y(s.location) as latitude from airspace_version v cross join target_latest_state s where v.airspace_version_id=? and s.target_id=?",version,target);
        assertThat(topology.get("touches")).isEqualTo("BOUNDARY".equals(pointKind));
        assertThat(topology.get("disjoint")).isEqualTo("OUTSIDE".equals(pointKind));
        assertThat(topology.get("covers")).isEqualTo(!"OUTSIDE".equals(pointKind));
        if("BOUNDARY".equals(pointKind)) {
            assertThat(result.legalStatus()).isEqualTo(LegalStatus.UNDETERMINED);
            assertThat(result.unknownReasons()).contains("BOUNDARY_POLICY_UNKNOWN");
            assertThat(result.violationReasons()).doesNotContain("TEMPORARY_RESTRICTION_ACTIVE");
            assertThat(result.alarmCreated()).isFalse();
        } else {
            assertThat(result.legalStatus()).isEqualTo("INSIDE".equals(pointKind)?LegalStatus.ILLEGAL:LegalStatus.LEGAL);
            assertThat(result.unknownReasons()).isEmpty();
            assertThat(result.violationReasons().contains("TEMPORARY_RESTRICTION_ACTIVE")).isEqualTo("INSIDE".equals(pointKind));
        }
        var evaluation=jdbc.queryForMap("select evaluation_id,rule_set_version_id,observed_at,as_of,legal_status,hit_details,violation_reasons,unknown_reasons from rule_evaluation where evaluation_id=?",result.evaluationId());
        assertThat(evaluation.get("hit_details").toString().contains(version)).isEqualTo(!"OUTSIDE".equals(pointKind));
        saveEvidence("R36-"+pointKind,Map.of("synthetic_fixture",true,"coordinate_system","WGS-84 / EPSG:4326","authorized_input",body,"receipt",accepted,"topology",topology,"evaluation",evaluation,"boundary_policy","TOUCHES is UNKNOWN until business policy is confirmed"));
    }

    private String operator() {
        String role="QA-SP-"+UUID.randomUUID().toString().substring(0,8),user=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,?,'',false,true,0,0,0,false)",role,role);
        for(String permission:new String[]{"airspace:read","airspace:manage","interfaces","alarm:read","alarm:verify","handoff:create","handoff:read","evidence:read"})
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,?,?,false,current_timestamp)",role,permission,permission.endsWith("read")?"READ":"OP");
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",user,user,"隔离空间验收操作员",role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)",user,LocalStage7RuleEngineSeeder.ORG,LocalStage7RuleEngineSeeder.DISTRICT);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)",token,user,System.currentTimeMillis()+3600000);
        return token;
    }

    private ObjectNode airspaceInput(String no,int revision,OffsetDateTime from,String kind) throws Exception {
        var body=json.createObjectNode().put("message_id",UUID.randomUUID().toString()).put("revision",revision).put("action","UPSERT")
                .put("airspace_no",no).put("name","隔离空间版本验收").put("kind_code",kind)
                .put("owner_org_id",LocalStage7RuleEngineSeeder.ORG).put("district_id",LocalStage7RuleEngineSeeder.DISTRICT)
                .put("valid_from",from.toInstant().toEpochMilli()).put("valid_to",START.plusHours(1).toInstant().toEpochMilli()).put("change_reason","显式隔离 QA 合成边界");
        body.set("boundary",json.readTree("{\"type\":\"MultiPolygon\",\"coordinates\":[[[[118.89,37.79],[118.91,37.79],[118.91,37.81],[118.89,37.81],[118.89,37.79]]]]}"));
        return body;
    }

    private com.fasterxml.jackson.databind.JsonNode push(String token,ObjectNode body,int expected) throws Exception {
        var response=mvc.perform(post("/api/v1/local-interface-simulator/airspaces").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return json.readTree(response.getContentAsString());
    }

    private void saveEvidence(String label,Map<String,?> evidence) throws Exception {
        Path dir=Path.of("target","spatial-boundary-evidence");Files.createDirectories(dir);
        Files.writeString(dir.resolve(label+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    private String version(String no,int revision) {
        return jdbc.queryForObject("select v.airspace_version_id from airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.airspace_no=? and v.version_no=?",String.class,no,revision);
    }

    private Map<String,Object> evaluationRow(String id) {
        // Read the entire immutable decision, including the actual input-version snapshot.
        return jdbc.queryForMap("select * from rule_evaluation where evaluation_id=?",id);
    }

    private String frozen(String handoff) {
        return jdbc.queryForObject("select cast(snapshot as varchar) from handoff_material_snapshot where handoff_id=?",String.class,handoff);
    }

    private com.fasterxml.jackson.databind.JsonNode handoffMaterial(String token,String handoff) throws Exception {
        var response=mvc.perform(get("/api/v1/handoffs/{id}",handoff).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("material");
    }
}
