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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"app.dev-seed.enabled=false", "spring.datasource.url=jdbc:h2:mem:upstream_push;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UpstreamAirspaceApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    String org, district, token, role, user, no, fixedUser;
    long from;

    @BeforeEach void fixture() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        org="up-org-"+suffix; district="up-dist-"+suffix; no="UP-"+suffix;
        role="ROLE-BACKEND"; user=fixedUser==null?UUID.randomUUID().toString():fixedUser; token=UUID.randomUUID().toString();
        from=System.currentTimeMillis()-3600000;
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,"空域接收测试");
        jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",district,district,"测试区域");
        // The simulator is a backend entry point; airspace input is explicitly granted in this isolated fixture.
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " select ?,'airspace:manage','OP',false,current_timestamp where not exists"
                + " (select 1 from app_role_permission where role_code=? and permission_code='airspace:manage')",role,role);
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",user,"up-"+suffix,"接收账号",role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)",user,org,district);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)",token,user,System.currentTimeMillis()+3600000);
        // The upstream configuration is a backend endpoint; its business write grant remains explicit.
        role="ROLE-BACKEND";
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled) select ?,'airspace:manage','OP',false where not exists(select 1 from app_role_permission where role_code=? and permission_code='airspace:manage')",role,role);
        jdbc.update("update app_user set role_code=? where user_id=?",role,user);
    }

    ObjectNode input(int revision) throws Exception {
        ObjectNode n=json.createObjectNode();
        n.put("message_id",UUID.randomUUID().toString()).put("revision",revision).put("action","UPSERT")
            .put("airspace_no",no).put("name","上级测试空域").put("kind_code","PROHIBITED")
            .put("owner_org_id",org).put("district_id",district).put("valid_from",from+revision*1000L)
            .put("change_reason","上级下发");
        n.set("boundary",json.readTree("{\"type\":\"MultiPolygon\",\"coordinates\":[[[[118,37],[118.01,37],[118.01,37.01],[118,37.01],[118,37]]]]}"));
        return n;
    }
    ResultActions send(ObjectNode body) throws Exception {
        return mvc.perform(post("/api/v1/local-interface-simulator/airspaces").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    @Test void acceptsMockAndReplaysWithoutCreatingAnotherVersion() throws Exception {
        var body=input(1);
        String first=send(body).andExpect(status().isOk()).andExpect(jsonPath("$.data.source_mode").value("mock"))
            .andExpect(jsonPath("$.data.state").value("ACCEPTED")).andReturn().getResponse().getContentAsString();
        assertThat(send(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.airspace_no=?",Integer.class,no)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select source_id from airspace where airspace_no=?",String.class,no)).isEqualTo("local-airspace-upstream");
        mvc.perform(get("/api/v1/local-interface-simulator/airspaces/context").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].airspace_no").value(no));
    }
    @Test void rejectsChangedMessageAndOldRevision() throws Exception {
        var body=input(1);send(body).andExpect(status().isOk());
        body.put("kind_code","PERMITTED");send(body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SOURCE_MESSAGE_CONFLICT"));
        send(input(1)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("UPSTREAM_REVISION_CONFLICT"));
    }
    @Test void updateAndWithdrawalPreserveHistory() throws Exception {
        send(input(1)).andExpect(status().isOk());send(input(2)).andExpect(status().isOk());
        var withdraw=json.createObjectNode().put("message_id",UUID.randomUUID().toString()).put("revision",3)
            .put("action","WITHDRAW").put("airspace_no",no).put("effective_at",from+3000).put("change_reason","上级撤销");
        send(withdraw).andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("WITHDRAW"));
        var ends=jdbc.queryForList("select v.valid_to from airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.airspace_no=? order by v.version_no",java.sql.Timestamp.class,no);
        assertThat(ends).hasSize(2);assertThat(ends.get(0).getTime()).isEqualTo(from+2000);assertThat(ends.get(1).getTime()).isEqualTo(from+3000);
        var backdated=input(4).put("valid_from",from+2500);send(backdated).andExpect(status().isConflict());
    }
    @Test void outOfScopeInvalidGeometryAndSpoofedSourceDoNotWrite() throws Exception {
        send(input(1).put("owner_org_id","not-visible")).andExpect(status().isNotFound());
        var invalid=input(1);invalid.set("boundary",json.readTree("{\"type\":\"MultiPolygon\",\"coordinates\":[]}"));
        send(invalid).andExpect(status().isBadRequest());
        send(input(1).put("source_mode","live")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from airspace where airspace_no=?",Integer.class,no)).isZero();
    }
    @Test void replayAndContextRecheckCurrentScope() throws Exception {
        var body=input(1);send(body).andExpect(status().isOk());
        jdbc.update("delete from app_user_data_scope where user_id=?",user);
        send(body).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/local-interface-simulator/airspaces/context").header("Authorization","Bearer "+token))
            .andExpect(status().isForbidden());
    }
    @Test void localCrudRejectsUpstreamEnvelopeAndLiveIsNotSilentlySimulated() throws Exception {
        mvc.perform(post("/api/v1/airspaces").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(input(1).toString()))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/airspaces/import-batches").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/airspaces/import-batches/missing").header("Authorization","Bearer "+token))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/airspaces/missing/versions").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/integrations/airspaces/messages").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(input(1).toString()))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("UPSTREAM_NOT_CONFIGURED"));
    }
    @Test void permissionsAreCheckedBeforeParsing() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='airspace:manage'",role);
        send(input(1)).andExpect(status().isForbidden());
    }

    @Test void localEditRetainsSourceAndLaterPushesWorkUntilLocalDeletion() throws Exception {
        var original=input(1);
        String receipt=send(original).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id=json.readTree(receipt).path("data").path("airspace_id").asText();
        var edit=original.deepCopy();edit.remove(java.util.List.of("message_id","revision","action","airspace_no","owner_org_id","district_id"));
        edit.put("name","平台编辑后的名称").put("expected_version",0).put("valid_from",from+2000)
            .put("min_altitude_m",-12.5).put("max_altitude_m",250).put("altitude_datum","AMSL");
        String key=UUID.randomUUID().toString();
        mvc.perform(put("/api/v1/airspaces/"+id).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(edit.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version_no").value(2));
        mvc.perform(put("/api/v1/airspaces/"+id).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(edit.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        var second=input(2).put("valid_from",from+3000);
        send(second.deepCopy().put("name","平台编辑后的名称")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("UPSTREAM_IDENTITY_CONFLICT"));
        send(second).andExpect(status().isOk());
        var withdraw=json.createObjectNode().put("message_id",UUID.randomUUID().toString()).put("revision",3).put("action","WITHDRAW")
            .put("airspace_no",no).put("effective_at",from+4000).put("change_reason","上级撤销");
        send(withdraw).andExpect(status().isOk());
        send(input(4).put("valid_from",from+5000)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/airspaces/"+id).header("Authorization","Bearer "+token))
            .andExpect(jsonPath("$.data.name").value("平台编辑后的名称")).andExpect(jsonPath("$.data.management_source").value("UPSTREAM")).andExpect(jsonPath("$.data.editable").value(true));
        assertThat(jdbc.queryForList("SELECT o.origin_kind FROM airspace_version_origin o JOIN airspace_version v ON v.airspace_version_id=o.airspace_version_id WHERE v.airspace_id=? ORDER BY v.version_no",String.class,id))
            .containsExactly("UPSTREAM","MANUAL","UPSTREAM","UPSTREAM");
        mvc.perform(delete("/api/v1/airspaces/"+id).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":4,\"change_reason\":\"本地删除\"}"))
            .andExpect(status().isOk());
        send(input(5).put("valid_from",from+6000)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("AIRSPACE_DELETED"));
        assertThat(send(original).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM airspace_version WHERE airspace_id=?",Integer.class,id)).isEqualTo(4);
        mvc.perform(get("/api/v1/airspaces").param("owner_org_id",org).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test void maintainingUpstreamStillRequiresPermissionAndOriginalScope() throws Exception {
        String id=json.readTree(send(input(1)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("airspace_id").asText();
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='airspace:manage'",role);
        mvc.perform(delete("/api/v1/airspaces/"+id).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0,\"change_reason\":\"delete\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/airspaces/"+id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled) values(?,'airspace:manage','OP',false)",role);
        jdbc.update("delete from app_user_data_scope where user_id=?",user);
        mvc.perform(delete("/api/v1/airspaces/"+id).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0,\"change_reason\":\"delete\"}"))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM airspace WHERE airspace_id=?",java.sql.Timestamp.class,id)).isNull();
    }

    @Test void concurrentSameMessageProducesOneReceipt() throws Exception {
        var body=input(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> task=()->{start.await();return send(body).andReturn().getResponse().getStatus();};
        try {
            var first=executor.submit(task);var second=executor.submit(task);start.countDown();
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from airspace_delivery m join airspace a on a.airspace_id=m.airspace_id where a.airspace_no=?",Integer.class,no)).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }
}
