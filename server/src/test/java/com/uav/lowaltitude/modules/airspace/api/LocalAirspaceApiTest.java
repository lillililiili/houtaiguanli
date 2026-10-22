package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@SpringBootTest(properties="app.dev-seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LocalAirspaceApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    String session, user, role, org, district;
    long from;

    @BeforeEach void prepare() {
        user=UUID.randomUUID().toString(); session=UUID.randomUUID().toString(); role="ROLE-"+UUID.randomUUID().toString().substring(0,12);
        org=UUID.randomUUID().toString(); district=UUID.randomUUID().toString(); from=System.currentTimeMillis()-60000;
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',false,true,0,0,0,false)",role,role);
        for (String code : new String[]{"airspace:read","airspace:manage"}) jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,'OP',false,current_timestamp)",role,code);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",user,user,user,role);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",session,user,System.currentTimeMillis()+3600000);
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,true,0,0,0)",org,org,"Org");
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,true,0,0,0)",district,district,"District");
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id,created_at) VALUES(?,?,?,current_timestamp)",user,org,district);
    }

    ObjectNode input() throws Exception {
        ObjectNode n=(ObjectNode)json.readTree("""
            {"name":"Local volume","kind_code":"PROHIBITED","boundary":{"type":"Polygon","coordinates":[[[118,37],[118.02,37],[118.02,37.02],[118,37.02],[118,37]]]},"min_altitude_m":-20,"max_altitude_m":300,"altitude_datum":"AMSL","change_reason":"test change"}
            """);
        n.put("airspace_no",UUID.randomUUID().toString()); n.put("owner_org_id",org); n.put("district_id",district); n.put("valid_from",from); return n;
    }
    org.springframework.test.web.servlet.ResultActions create(ObjectNode n) throws Exception {
        return mvc.perform(post("/api/v1/airspaces").header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(n.toString()));
    }
    String createId() throws Exception { return json.readTree(create(input()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("airspace_id").asText(); }
    ObjectNode update() throws Exception { ObjectNode n=input(); n.remove(java.util.List.of("airspace_no","owner_org_id","district_id")); n.put("expected_version",0); n.put("valid_from",from+1000); n.put("name","Renamed"); return n; }
    org.springframework.test.web.servlet.ResultActions putBody(String id,ObjectNode n,String key) throws Exception {return mvc.perform(put("/api/v1/airspaces/"+id).header("Authorization","Bearer "+session).header("Idempotency-Key",key).contentType("application/json").content(n.toString()));}
    org.springframework.test.web.servlet.ResultActions deleteId(String id,long version) throws Exception {return mvc.perform(delete("/api/v1/airspaces/"+id).header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expected_version\":"+version+",\"change_reason\":\"retire\"}"));}

    @Test void completeCrudRetainsHistoricalVersionsAndAudits() throws Exception {
        String id=createId();
        mvc.perform(get("/api/v1/airspaces/"+id).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.management_source").value("LOCAL")).andExpect(jsonPath("$.data.editable").value(true));
        String key=UUID.randomUUID().toString(); ObjectNode edit=update();
        putBody(id,edit,key).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.version_no").value(2));
        putBody(id,edit,key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        putBody(id,edit,UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(get("/api/v1/airspaces/"+id).header("Authorization","Bearer "+session)).andExpect(jsonPath("$.data.name").value("Renamed"));
        deleteId(id,1).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(get("/api/v1/airspaces").param("owner_org_id",org).header("Authorization","Bearer "+session)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/airspaces/"+id+"/versions").header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/v1/airspaces/"+id).header("Authorization","Bearer "+session)).andExpect(jsonPath("$.data.deleted_at").isNumber()).andExpect(jsonPath("$.data.editable").value(false)).andExpect(jsonPath("$.data.current_version").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE object_id=? AND result='SUCCESS'",Long.class,id)).isEqualTo(4);
    }
    @Test void strictAmslGeometryAndFields() throws Exception {
        ObjectNode n=input(); n.remove("min_altitude_m"); create(n).andExpect(status().isBadRequest());
        n=input(); n.put("altitude_datum","AGL"); create(n).andExpect(status().isBadRequest());
        n=input(); n.put("max_altitude_m",-30); create(n).andExpect(status().isBadRequest());
        n=input(); n.set("boundary",json.readTree("{\"type\":\"Point\",\"coordinates\":[118,37]}")); create(n).andExpect(status().isBadRequest());
        n=input(); n.put("source_id","forged"); create(n).andExpect(status().isBadRequest());
    }
    @Test void localVolumesRequireStrictlyPositiveHeight() throws Exception {
        var n=input().put("min_altitude_m",100).put("max_altitude_m",100);
        create(n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        String id=createId();var edit=update().put("min_altitude_m",-10).put("max_altitude_m",-10);
        putBody(id,edit,UUID.randomUUID().toString()).andExpect(status().isBadRequest());
    }
    @Test void localAltitudeMustFitStorageWithoutRounding() throws Exception {
        create(input().put("min_altitude_m",new java.math.BigDecimal("100.001")).put("max_altitude_m",new java.math.BigDecimal("100.002")))
            .andExpect(status().isBadRequest());
        create(input().put("max_altitude_m",new java.math.BigDecimal("100000000")))
            .andExpect(status().isBadRequest());
    }
    @Test void permissionsAndCompleteTupleAreRequired() throws Exception {
        mvc.perform(get("/api/v1/airspaces/management-context").header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.scopes[0].owner_org_id").value(org)).andExpect(jsonPath("$.data.scopes[0].district_id").value(district));
        ObjectNode n=input(); n.put("district_id",UUID.randomUUID().toString()); create(n).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=? AND permission_code='airspace:manage'",role);
        create(input()).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/airspaces/management-context").header("Authorization","Bearer "+session)).andExpect(status().isForbidden());
    }
    @Test void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/airspaces").header("Authorization","Bearer "+session).contentType("application/json").content(input().toString())).andExpect(status().isBadRequest());
    }
    @Test void provenanceUnknownRemainsReadonly() throws Exception {
        String id=createId();
        jdbc.update("INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) SELECT ?,airspace_id,2,kind_code,boundary,?,created_at FROM airspace_version WHERE airspace_id=? AND version_no=1",UUID.randomUUID().toString(),new java.sql.Timestamp(from+86400000),id);
        putBody(id,update(),UUID.randomUUID().toString()).andExpect(status().isConflict());
        deleteId(id,0).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/airspaces/"+id).header("Authorization","Bearer "+session)).andExpect(jsonPath("$.data.management_source").value("LEGACY")).andExpect(jsonPath("$.data.editable").value(false));
    }
    @Test void linkedExternalSourceCanBeMaintainedWithoutChangingItsProvenance() throws Exception {
        String id=createId(),source=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,source_mode,enabled,created_at,updated_at,version) VALUES(?,?,?,'live',true,current_timestamp,current_timestamp,0)",source,source,"source");
        jdbc.update("UPDATE airspace SET source_id=? WHERE airspace_id=?",source,id);
        mvc.perform(get("/api/v1/airspaces").param("owner_org_id",org).param("include","current_version").header("Authorization","Bearer "+session)).andExpect(jsonPath("$.data.items[0].management_source").value("UPSTREAM")).andExpect(jsonPath("$.data.items[0].editable").value(true));
        putBody(id,update(),UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT source_id FROM airspace WHERE airspace_id=?",String.class,id)).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM airspace_version_origin o JOIN airspace_version v ON v.airspace_version_id=o.airspace_version_id WHERE v.airspace_id=? AND o.origin_kind='MANUAL'",Integer.class,id)).isEqualTo(2);
        deleteId(id,1).andExpect(status().isOk());
        putBody(id,update().put("expected_version",2),UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("AIRSPACE_DELETED"));
        mvc.perform(get("/api/v1/airspaces/"+id).header("Authorization","Bearer "+session)).andExpect(jsonPath("$.data.management_source").value("UPSTREAM")).andExpect(jsonPath("$.data.editable").value(false));
    }
}
