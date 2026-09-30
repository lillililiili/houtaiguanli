package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Disposable PostgreSQL spatial/read-scope fixture; no device or notification adapters. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@EnabledIfSystemProperty(named = "qa.airspace.scope.browser", matches = "true")
class AirspaceScopeBrowserFixtureTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.password", () -> "changeme");
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @Test void serveScopedPoints() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "scope-a-" + suffix, orgB = "scope-b-" + suffix;
        String districtA = "scope-da-" + suffix, districtB = "scope-db-" + suffix;
        scope(orgA, districtA, "甲"); scope(orgB, districtB, "乙");
        String role = "ROLE-SCOPE-" + suffix, user = UUID.randomUUID().toString(), session = UUID.randomUUID().toString();
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,?,'',false,true,0,0,0,false)", role, "空间范围只读测试");
        for (String permission : List.of("flights", "airspace", "airspace:read", "target:read", "risk:read", "route:read", "flight:read"))
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,?,'READ',?,current_timestamp)", role, permission, List.of("flights", "airspace").contains(permission));
        jdbc.update("""
                insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)
                select ?,'qa-scope-reader','隔离范围只读员',?,'ACTIVE',password_hash,0,'ASSIGNED',0,0,0,0 from app_user where account='admin1'
                """, user, role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?),(?,?,?)", user, orgA, districtA, user, orgB, districtB);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)", session, user, System.currentTimeMillis() + 3_600_000);
        String area = "scope-area-" + suffix, foreignArea = "scope-cross-area-" + suffix;
        airspace(area, "隔离空间边界测试区", orgA, districtA);
        airspace(foreignArea, "不可见交叉范围区", orgA, districtB);
        var identityAreas = new ArrayList<Map<String, String>>();
        if (Boolean.getBoolean("qa.airspace.scope.identity")) {
            jdbc.update("delete from app_user_data_scope where user_id=? and org_id=?", user, orgB);
            jdbc.update("update airspace set name='同名范围测试区' where airspace_id=?", area);
            identityAreas.add(Map.of("id", area, "mode", "mock", "account", "qa-scope-reader"));
            for (String mode : List.of("replay", "live")) {
                String id = UUID.randomUUID().toString(); airspace(id, "同名范围测试区", orgA, districtA);
                jdbc.update("update airspace set source_mode=? where airspace_id=?", mode, id);
                identityAreas.add(Map.of("id", id, "mode", mode, "account", "qa-scope-reader"));
            }
            String other = UUID.randomUUID().toString(), secondUser = UUID.randomUUID().toString();
            airspace(other, "同名范围测试区", orgB, districtB);
            identityAreas.add(Map.of("id", other, "mode", "mock", "account", "qa-scope-second"));
            jdbc.update("""
                    insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)
                    select ?,'qa-scope-second','隔离乙单位只读员',?,'ACTIVE',password_hash,0,'ASSIGNED',0,0,0,0 from app_user where account='admin1'
                    """, secondUser, role);
            jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)", secondUser, orgB, districtB);
            String secondSession = UUID.randomUUID().toString();
            jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)", secondSession, secondUser, System.currentTimeMillis()+3_600_000);
            for (String credential : List.of(session, secondSession)) {
                boolean first = credential.equals(session);
                String list = mvc.perform(get("/api/v1/airspaces?size=100").header("Authorization", "Bearer " + credential))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(json.readTree(list).path("data").path("total").asInt()).isEqualTo(first ? 3 : 1);
                for (var item : identityAreas) {
                    boolean allowed = item.get("account").equals(first ? "qa-scope-reader" : "qa-scope-second");
                    if (allowed) assertThat(list).contains(item.get("id")); else {
                        assertThat(list).doesNotContain(item.get("id"));
                        mvc.perform(get("/api/v1/airspaces/" + item.get("id")).header("Authorization", "Bearer " + credential))
                                .andExpect(status().isNotFound());
                    }
                }
            }
        }
        var points = new ArrayList<Map<String, Object>>();
        points.add(point("范围内", 118.61, 37.41, orgA, districtA));
        points.add(point("边界上", 118.60, 37.41, orgA, districtA));
        points.add(point("范围外", 118.599, 37.41, orgA, districtA));
        points.add(point("不可见甲乙", 118.61, 37.41, orgA, districtB));
        points.add(point("不可见乙甲", 118.61, 37.41, orgB, districtA));
        if (Boolean.getBoolean("qa.airspace.scope.risks")) {
            for (int index = 0; index < points.size(); index++) {
                var point = points.get(index);
                risk(point, index == 0 ? "NOTIFIED" : index == 1 ? "EXCLUDED" : "PENDING_VERIFICATION",
                        index == 4 ? orgB : orgA, index == 3 ? districtB : districtA);
            }
            String risks = mvc.perform(get("/api/v1/risks?size=100").header("Authorization", "Bearer " + session))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(risks).path("data").path("total").asInt()).isEqualTo(3);
            assertThat(risks).doesNotContain("不可见甲乙", "不可见乙甲");
        }
        String response = mvc.perform(get("/api/v1/targets?size=100").header("Authorization", "Bearer " + session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).path("data").path("total").asInt()).isEqualTo(3);
        for (var hidden : points.subList(3, 5)) {
            assertThat(response).doesNotContain((String) hidden.get("target_id"));
            mvc.perform(get("/api/v1/targets/" + hidden.get("target_id")).header("Authorization", "Bearer " + session)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/airspaces/" + foreignArea).header("Authorization", "Bearer " + session)).andExpect(status().isNotFound());
        var relations = jdbc.queryForList("""
                select t.target_no,ST_Touches(v.boundary,s.location) as boundary,
                       ST_Covers(v.boundary,s.location) as covered
                from target t join target_latest_state s on s.target_id=t.target_id
                cross join airspace_version v where v.airspace_id=? and t.owner_org_id=? and t.district_id=? order by t.target_no
                """, area, orgA, districtA);
        assertThat(relations).hasSize(3);
        assertThat(relations).anySatisfy(row -> assertThat(row).containsEntry("target_no", "QA-范围内").containsEntry("covered", true).containsEntry("boundary", false));
        assertThat(relations).anySatisfy(row -> assertThat(row).containsEntry("target_no", "QA-边界上").containsEntry("covered", true).containsEntry("boundary", true));
        assertThat(relations).anySatisfy(row -> assertThat(row).containsEntry("target_no", "QA-范围外").containsEntry("covered", false).containsEntry("boundary", false));
        Path directory = Path.of("target", "airspace-scope-browser").toAbsolutePath(); Files.createDirectories(directory);
        Path stop = directory.resolve("stop"); Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of("port", port,
                "airspace_id", area, "foreign_airspace_id", foreignArea, "points", points, "relations", relations,
                "identity_areas", identityAreas, "simulated", true)));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) {
            // Current position snapshots are refreshed only inside the owned QA schema.
            Timestamp now = Timestamp.from(Instant.now());
            for (var point : points) {
                jdbc.update("update target set last_seen_at=?,updated_at=? where target_id=?", now, now, point.get("target_id"));
                jdbc.update("update target_latest_state set observed_at=?,received_at=?,updated_at=? where target_id=?", now, now, now, point.get("target_id"));
            }
            Thread.sleep(1000);
        }
        Files.deleteIfExists(stop);
        assertThat(jdbc.queryForObject("select count(*) from target where owner_org_id in (?,?)", Integer.class, orgA, orgB)).isEqualTo(5);
    }
    void scope(String org, String district, String name) {
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", org, org, "隔离范围" + name);
        jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", district, district, "隔离区域" + name);
    }
    void airspace(String id, String name, String org, String district) {
        jdbc.update("insert into airspace(airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,?,?,'mock',?,?,current_timestamp,current_timestamp,0)", id, id, name, org, district);
        jdbc.update("""
                insert into airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at)
                values(?,?,1,'PERMITTED',ST_GeomFromText('MULTIPOLYGON(((118.60 37.40,118.62 37.40,118.62 37.42,118.60 37.42,118.60 37.40)))',4326),current_timestamp - interval '1 minute',current_timestamp)
                """, UUID.randomUUID().toString(), id);
    }
    Map<String, Object> point(String label, double lon, double lat, String org, String district) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into target(target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'UAV',current_timestamp,current_timestamp,'mock',?,?,current_timestamp,current_timestamp,0)", id, "QA-" + label, org, district);
        jdbc.update("""
                insert into target_latest_state(target_id,location,altitude_amsl_m,height_agl_m,speed_mps,observed_at,received_at,created_at,updated_at,version)
                values(?,ST_SetSRID(ST_MakePoint(?,?),4326),100,60,0,current_timestamp,current_timestamp,current_timestamp,current_timestamp,0)
                """, id, lon, lat);
        return Map.of("label", label, "target_id", id, "longitude", lon, "latitude", lat);
    }

    void risk(Map<String, Object> point, String state, String org, String district) {
        String risk = UUID.randomUUID().toString();
        String route = UUID.randomUUID().toString(), version = UUID.randomUUID().toString(), plan = UUID.randomUUID().toString();
        jdbc.update("insert into route(route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,?,?,true,'mock',?,?,current_timestamp,current_timestamp,0)",
                route, route, "隔离范围航线", org, district);
        jdbc.update("insert into route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values(?,?,1,ST_GeomFromText('LINESTRING(118.60 37.40,118.62 37.42)',4326),100,current_timestamp,current_timestamp)", version, route);
        jdbc.update("insert into flight_plan(plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'APPROVED','mock',current_timestamp,current_timestamp + interval '2 hours',?,?,?,current_timestamp,current_timestamp,0)",
                plan, "QA-" + point.get("label"), version, org, district);
        jdbc.update("""
                insert into flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,
                    severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,
                    owner_org_id,district_id,created_at,updated_at,version)
                values(?,'rule-engine-space-risk-mock',?,?,?,?,'SPACE_OBJECT',
                    'HIGH',?,'SPACE_OBJECT',?,current_timestamp,current_timestamp,'UNKNOWN','mock',?,?,current_timestamp,current_timestamp,0)
                """, risk, risk, plan, version, point.get("target_id"), state, "隔离空间样本：" + point.get("label"), org, district);
        jdbc.update("""
                insert into space_risk_fact(risk_id,subtype_code,rule_version_id,rule_set_version_id,corridor_relation,
                    altitude_band,trend,unknown_reasons,target_location,window_from,window_to,created_at)
                values(?,'BIRD_FLOCK','space-risk-c04-v1','space-risk-demo-v1','UNKNOWN','UNKNOWN','UNKNOWN',cast('[]' as json),
                    ST_SetSRID(ST_MakePoint(?,?),4326),current_timestamp - interval '1 minute',current_timestamp,current_timestamp)
                """, risk, point.get("longitude"), point.get("latitude"));
    }
}
