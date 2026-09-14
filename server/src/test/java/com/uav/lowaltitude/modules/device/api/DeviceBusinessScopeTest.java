package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.mock.LocalStage5DeviceScopeSeeder;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceBusinessScopeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceBusinessScopeRepository.ScopedIncident;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/** 设备异常进入工作台/统计前必须经过显式归属映射与精确元组过滤；名称、同名设备或缺失映射都不能扩大可见范围。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeviceBusinessScopeTest {

    private static final String ORG_A = "stage5-scope-org-a";
    private static final String ORG_B = "stage5-scope-org-b";
    private static final String DISTRICT_A = "stage5-scope-district-a";
    private static final String DISTRICT_B = "stage5-scope-district-b";

    @Autowired DeviceBusinessScopeRepository repository;
    @Autowired LocalStage5DeviceScopeSeeder seeder;
    @Autowired JdbcTemplate jdbc;

    private String userId;
    private AccessDecision assigned;

    @BeforeEach
    void fixture() {
        catalog(ORG_A, DISTRICT_A);
        catalog(ORG_B, DISTRICT_B);
        userId = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,'ROLE-ADMIN','ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "scope-" + userId.substring(0, 8), "设备范围测试");
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, ORG_A, DISTRICT_A);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, ORG_B, DISTRICT_B);
        assigned = new AccessDecision(userId, ScopeMode.ASSIGNED);
    }

    @Test
    void stage5PermissionsAreCataloguedAsActionsWithoutProductionGrants() {
        List<Map<String, Object>> rows = jdbc.queryForList("select permission_code, permission_kind, route_key from app_permission where permission_code in ('workbench:read','handoff:read','handoff:create') order by permission_code");
        assertThat(rows).extracting(row -> row.get("permission_code")).containsExactly("handoff:create", "handoff:read", "workbench:read");
        assertThat(rows).allSatisfy(row -> { assertThat(row.get("permission_kind")).isEqualTo("ACTION"); assertThat(row.get("route_key")).isNull(); });
        // 阶段 15 的本地/测试种子给演示复核员授了 handoff:read（决策 15-3，只在 dev-seed 下存在）；按名字排除这一个，不放开其它非内置角色。
        List<String> holders = jdbc.queryForList("select distinct role_code from app_role_permission where permission_code in ('workbench:read','handoff:read','handoff:create') and role_code <> 'ROLE-ADMIN' and role_code not like 'ROLE-DEMO-%' order by role_code", String.class);
        // 失败时列出持有者：共享 H2 上下文里其它用例的夹具若不清理自己的授权，会在这里冒充产品授权（14-34）。
        assertThat(holders).as("non-admin roles holding stage 5 action codes").isEmpty();
    }

    @Test
    void sameNameDevicesAreNotCrossMappedAndUnmappedDevicesStayInvisible() {
        String mapped = device("STAGE5-SAME-A", "样例雷达");
        String unmapped = device("STAGE5-SAME-B", "样例雷达");
        map(mapped, ORG_A, DISTRICT_A);
        incident("stage5-inc-mapped", mapped, 2_000);
        incident("stage5-inc-unmapped", unmapped, 3_000);

        List<ScopedIncident> items = repository.listIncidents(assigned, 0, 50).stream().filter(i -> i.incidentId().startsWith("stage5-inc-")).toList();
        assertThat(items).extracting(ScopedIncident::incidentId).containsExactly("stage5-inc-mapped");
        assertThat(items.get(0).ownerOrgId()).isEqualTo(ORG_A);
        assertThat(items.get(0).districtId()).isEqualTo(DISTRICT_A);
        assertThat(repository.findIncident("stage5-inc-unmapped", assigned)).isNull();
        assertThat(repository.findIncident("stage5-inc-unmapped", new AccessDecision(userId, ScopeMode.ALL))).isNull();
        assertThat(countMine(assigned)).isEqualTo(1);
    }

    @Test
    void assignedScopeRequiresExactTupleNotCrossProduct() {
        String cross = device("STAGE5-CROSS", "交叉元组设备");
        String exact = device("STAGE5-EXACT", "精确元组设备");
        map(cross, ORG_A, DISTRICT_B);
        map(exact, ORG_B, DISTRICT_B);
        incident("stage5-inc-cross", cross, 4_000);
        incident("stage5-inc-exact", exact, 5_000);

        assertThat(repository.findIncident("stage5-inc-cross", assigned)).isNull();
        assertThat(repository.findIncident("stage5-inc-exact", assigned)).isNotNull();
        assertThat(countMine(assigned)).isEqualTo(1);
        assertThat(repository.countIncidents(new AccessDecision(userId, ScopeMode.NONE))).isZero();
    }

    @Test
    void disabledDirectoryHidesMappedDeviceEvenForAllScope() {
        String dev = device("STAGE5-DISABLED", "停用归属设备");
        map(dev, ORG_A, DISTRICT_A);
        incident("stage5-inc-disabled", dev, 6_000);
        AccessDecision all = new AccessDecision(userId, ScopeMode.ALL);
        assertThat(repository.findIncident("stage5-inc-disabled", all)).isNotNull();

        jdbc.update("update app_org set enabled=false where org_id=?", ORG_A);
        assertThat(repository.findIncident("stage5-inc-disabled", all)).isNull();
        assertThat(repository.findIncident("stage5-inc-disabled", assigned)).isNull();
    }

    @Test
    void emptyMappingTableMeansUnconfiguredRatherThanNoIncidents() {
        String dev = device("STAGE5-UNCONF", "未配置设备");
        incident("stage5-inc-unconf", dev, 7_000);
        jdbc.update("delete from device_business_scope");
        assertThat(repository.configured()).isFalse();
        assertThat(repository.countIncidents(new AccessDecision(userId, ScopeMode.ALL))).isZero();
        map(dev, ORG_A, DISTRICT_A);
        assertThat(repository.configured()).isTrue();
    }

    @Test
    void seederMapsFixedDevicesIdempotentlyAndKeepsManualAdjustments() {
        seeder.run(null);
        String mappedId = jdbc.queryForObject("select device_id from ops_device where device_no='DEV-MOCK-001'", String.class);
        String unmappedId = jdbc.queryForObject("select device_id from ops_device where device_no=?", String.class, LocalStage5DeviceScopeSeeder.UNMAPPED_DEVICE);
        assertThat(jdbc.queryForObject("select owner_org_id from device_business_scope where ops_device_id=?", String.class, mappedId)).isEqualTo(LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID);
        assertThat(jdbc.queryForObject("select count(*) from device_business_scope where ops_device_id=?", Integer.class, unmappedId)).isZero();

        jdbc.update("update device_business_scope set owner_org_id=?, district_id=? where ops_device_id=?",
                LocalStage5DeviceScopeSeeder.OTHER_ORG_ID, LocalStage5DeviceScopeSeeder.OTHER_DISTRICT_ID, mappedId);
        int before = jdbc.queryForObject("select count(*) from device_business_scope", Integer.class);
        seeder.run(null);
        assertThat(jdbc.queryForObject("select count(*) from device_business_scope", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select owner_org_id from device_business_scope where ops_device_id=?", String.class, mappedId)).isEqualTo(LocalStage5DeviceScopeSeeder.OTHER_ORG_ID);
    }

    private long countMine(AccessDecision access) {
        return repository.listIncidents(access, 0, 500).stream().filter(i -> i.incidentId().startsWith("stage5-inc-")).count();
    }

    private void catalog(String orgId, String districtId) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,0,0,0 where not exists (select 1 from app_org where org_id=?)", orgId, orgId.toUpperCase(), orgId, orgId);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,0,0,0 where not exists (select 1 from app_district where district_id=?)", districtId, districtId.toUpperCase(), districtId, districtId);
        jdbc.update("update app_org set enabled=true where org_id=?", orgId);
        jdbc.update("update app_district set enabled=true where district_id=?", districtId);
    }

    private String device(String deviceNo, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into ops_device (device_id,device_no,name,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) values (?,?,?,'雷达','融合感知箱',true,'mock',true,0,0,0)", id, deviceNo + "-" + id.substring(0, 6), name);
        return id;
    }

    private void map(String deviceId, String orgId, String districtId) {
        jdbc.update("insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,current_timestamp,current_timestamp)", deviceId, orgId, districtId);
    }

    private void incident(String incidentId, String deviceId, long detectedAt) {
        jdbc.update("insert into device_incident (incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated) values (?,?,?,'LINK_DEGRADED','HIGH','PENDING',?,'测试异常',true)", incidentId, deviceId, "INC-" + incidentId, detectedAt);
    }
}
