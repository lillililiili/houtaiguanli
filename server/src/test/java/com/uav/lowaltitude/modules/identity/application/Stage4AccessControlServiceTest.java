package com.uav.lowaltitude.modules.identity.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stage4_access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class Stage4AccessControlServiceTest {

    private static final String ROLE = "ROLE-STAGE4-DUTY";
    private static final String ORG_A = "stage4-org-a";
    private static final String ORG_B = "stage4-org-b";
    private static final String DISTRICT_A = "stage4-district-a";
    private static final String DISTRICT_B = "stage4-district-b";

    @Autowired
    AccessControlService accessControlService;

    @Autowired
    JdbcTemplate jdbc;

    String userId;

    @BeforeEach
    void createDefaultDeniedUser() {
        AuthContext.clear();
        userId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into app_role (
                    role_code, name, description, builtin, enabled,
                    created_at, updated_at, version, system_role
                ) select ?, 'Stage 4 duty role', '', false, true, 0, 0, 0, false
                where not exists (select 1 from app_role where role_code=?)
                """, ROLE, ROLE);
        jdbc.update("delete from app_role_permission where role_code=?", ROLE);
        jdbc.update("""
                insert into app_user (
                    user_id, account, name, role_code, status, password_hash, fail_count,
                    scope_mode, permission_version, created_at, updated_at, version
                ) values (?, ?, 'Stage 4 operator', ?, 'ACTIVE', 'hash', 0, 'ASSIGNED', 0, 0, 0, 0)
                """, userId, "stage4-" + userId, ROLE);
        createScopeCatalog(ORG_A, "STAGE4-ORG-A", DISTRICT_A, "STAGE4-DISTRICT-A");
        createScopeCatalog(ORG_B, "STAGE4-ORG-B", DISTRICT_B, "STAGE4-DISTRICT-B");
        AuthContext.set(new AuthUser(
                userId,
                "stage4",
                "Stage 4",
                "ROLE-ADMIN",
                0,
                false,
                "ALL"));
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
        jdbc.update("delete from app_user_data_scope where user_id=?", userId);
        jdbc.update("delete from app_user where user_id=?", userId);
        jdbc.update("delete from app_role_permission where role_code=?", ROLE);
    }

    @Test
    void catalogsStage4ActionsWithoutGrantingProductionRoles() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select permission_code, module_code, permission_kind, action_code, route_key
                from app_permission
                where permission_code in ('alarm:verify', 'risk:read', 'risk:verify')
                order by permission_code
                """);

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("permission_kind")).isEqualTo("ACTION");
            assertThat(row.get("route_key")).isNull();
        });
        assertThat(rows).extracting(row -> row.get("permission_code"))
                .containsExactly("alarm:verify", "risk:read", "risk:verify");
        assertThat(rows).extracting(row -> row.get("module_code"))
                .containsExactly("alarm", "risk", "risk");
        assertThat(rows).extracting(row -> row.get("action_code"))
                .containsExactly("verify", "read", "verify");
        assertThat(jdbc.queryForObject("""
                select count(*) from app_role_permission
                where permission_code in ('alarm:verify', 'risk:read', 'risk:verify')
                  and role_code <> 'ROLE-ADMIN' and role_code not like 'ROLE-DEMO-%'
                """, Integer.class)).isZero();   // 演示复核员是 local/test 种子角色（15-34），不是生产角色
        // 按名放行之外再钉死内容：每个演示角色各自持哪几个都列出来（第六个演示角色一出现就会红）。
        assertThat(jdbc.queryForList("""
                select role_code || ' ' || permission_code || '=' || permission_level from app_role_permission
                where permission_code in ('alarm:verify', 'risk:read', 'risk:verify')
                  and role_code like 'ROLE-DEMO-%' order by role_code, permission_code
                """, String.class)).containsExactly(
                        // 审计员经飞行监管页拿到风险读；值班员另有核实操作权。
                        "ROLE-DEMO-AUDIT risk:read=READ",
                        "ROLE-DEMO-DUTY alarm:verify=OP", "ROLE-DEMO-DUTY risk:read=READ",
                        "ROLE-DEMO-REVIEWER risk:read=READ");

    }

    @Test
    void menuPermissionAndWritePermissionDoNotImplicitlyGrantSourceRead() {
        grantRaw("alarms");
        grant(PermissionCode.ALARM_VERIFY);
        addScope(ORG_A, DISTRICT_A);

        assertThat(accessControlService.require(PermissionCode.ALARM_VERIFY))
                .isEqualTo(new AccessDecision(userId, ScopeMode.ASSIGNED));
        assertForbidden(PermissionCode.ALARM_READ);
    }

    @Test
    void assignedStage4ActionsRequireAtLeastOneEnabledCompleteScopeTuple() {
        grant(PermissionCode.RISK_VERIFY);
        assertForbidden(PermissionCode.RISK_VERIFY);

        addScope(ORG_A, DISTRICT_A);
        addScope(ORG_B, DISTRICT_B);
        assertThat(accessControlService.require(PermissionCode.RISK_VERIFY))
                .isEqualTo(new AccessDecision(userId, ScopeMode.ASSIGNED));

        jdbc.update("update app_district set enabled=false where district_id in (?, ?)", DISTRICT_A, DISTRICT_B);
        assertForbidden(PermissionCode.RISK_VERIFY);
    }

    private void createScopeCatalog(String orgId, String orgCode, String districtId, String districtCode) {
        jdbc.update("""
                insert into app_org (org_id, org_code, name, enabled, created_at, updated_at, version)
                select ?, ?, ?, true, 0, 0, 0
                where not exists (select 1 from app_org where org_id=?)
                """, orgId, orgCode, orgCode, orgId);
        jdbc.update("""
                insert into app_district (district_id, district_code, name, enabled, created_at, updated_at, version)
                select ?, ?, ?, true, 0, 0, 0
                where not exists (select 1 from app_district where district_id=?)
                """, districtId, districtCode, districtCode, districtId);
        jdbc.update("update app_org set enabled=true where org_id=?", orgId);
        jdbc.update("update app_district set enabled=true where district_id=?", districtId);
    }

    private void grant(PermissionCode permission) {
        grantRaw(permission.value());
    }

    private void grantRaw(String permission) {
        jdbc.update("""
                insert into app_role_permission (
                    role_code, permission_code, permission_level, menu_enabled, created_at
                ) values (?, ?, 'READ', false, current_timestamp)
                """, ROLE, permission);
    }

    private void addScope(String orgId, String districtId) {
        jdbc.update("""
                insert into app_user_data_scope (user_id, org_id, district_id, created_at)
                values (?, ?, ?, current_timestamp)
                """, userId, orgId, districtId);
    }

    private void assertForbidden(PermissionCode permission) {
        ApiException error = catchThrowableOfType(
                ApiException.class, () -> accessControlService.require(permission));
        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error.getCode()).isEqualTo("FORBIDDEN");
    }
}
