package com.uav.lowaltitude.modules.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Synthetic boundary facts in an owned disposable schema, never a business filing or penalty result. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@EnabledIfSystemProperty(named = "qa.reporting.boundary.browser", matches = "true")
class ReportingBoundaryBrowserFixtureTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.password", () -> "changeme");
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @Test void serveBoundaryFactsAndFirstPasswordUser() throws Exception {
        String org = UUID.randomUUID().toString(), user = UUID.randomUUID().toString();
        String role = "ROLE-BOUNDARY-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", org, org, "隔离跨年统计测试单位");
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,'隔离统计测试员','',false,true,0,0,0,false)", role);
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) select ?,permission_code,permission_level,menu_enabled,current_timestamp from app_role_permission where role_code='ROLE-ADMIN'", role);
        jdbc.update("""
                insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,must_change_password,permission_version,created_at,updated_at,version)
                select ?,'qa-boundary-reader','隔离统计测试员',?,'ACTIVE',password_hash,0,'ASSIGNED',true,0,0,0,0 from app_user where account='admin1'
                """, user, role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,'seed-stage3-district')", user, org);
        var start = OffsetDateTime.parse("2024-12-31T00:00:00+08:00");
        var instants = List.of(start.minusNanos(1_000_000), start,
                start.plusDays(1).minusNanos(1_000_000), start.plusDays(1),
                start.plusDays(2).minusNanos(1_000_000), start.plusDays(2));
        var facts = new ArrayList<Map<String, String>>();
        for (var at : instants) {
            String id = UUID.randomUUID().toString();
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','live',?,'seed-stage3-district',?,?)",
                    id, "QA-边界-" + facts.size(), at, start.plusDays(4), org, at, start.plusDays(4));
            facts.add(Map.of("target_id", id, "first_seen_at", at.toString(), "last_seen_at", start.plusDays(4).toString()));
        }
        // A disposable seeded case exercises the report filter; no decision or legal outcome is created.
        jdbc.update("update punishment_case set source_mode='live',owner_org_id=?,filed_at=? where case_id='seed-stage14-case-investigating'", org, start.plusDays(1));
        jdbc.update("update handoff set created_at=? where handoff_id='seed-stage14-handoff-punish'", start.minusDays(10));
        Path directory = Path.of("target", "reporting-boundary-browser").toAbsolutePath(); Files.createDirectories(directory);
        Path stop = directory.resolve("stop"); Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of("port", port, "org_id", org,
                "targets", facts, "case_filed_at", start.plusDays(1).toString(), "synthetic_fixture", true)));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(1000);
        Files.deleteIfExists(stop);
        assertThat(jdbc.queryForObject("select count(*) from target where owner_org_id=?", Integer.class, org)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select must_change_password from app_user where user_id=?", Boolean.class, user)).isTrue();
    }
}
