package com.uav.lowaltitude.modules.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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

/** 阶段 9：五个动作权限只登记不授权；airspace / risk 两个 MODULE 行获得 route_key 成为真实菜单，但不改任何角色的 menu_enabled。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stage9_access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class Stage9AccessControlServiceTest {

    private static final String ROLE = "ROLE-STAGE9-DUTY";
    private static final String ORG = "stage9-org-a";
    private static final String DISTRICT = "stage9-district-a";
    private static final List<String> STAGE9_CODES = List.of("airport:manage", "airport:read", "airspace:manage", "risk:evaluate");

    @Autowired AccessControlService accessControlService;
    @Autowired AccessService accessService;
    @Autowired JdbcTemplate jdbc;

    String userId;

    @BeforeEach
    void createDefaultDeniedUser() {
        AuthContext.clear();
        userId = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) select ?,'Stage 9 duty role','',false,true,0,0,0,false where not exists (select 1 from app_role where role_code=?)", ROLE, ROLE);
        jdbc.update("delete from app_role_permission where role_code=?", ROLE);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,'Stage 9 operator',?,'ACTIVE','hash',0,'ASSIGNED',0,0,0,0)", userId, "stage9-" + userId, ROLE);
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,0,0,0 where not exists (select 1 from app_org where org_id=?)", ORG, "STAGE9-ORG-A", "STAGE9-ORG-A", ORG);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,0,0,0 where not exists (select 1 from app_district where district_id=?)", DISTRICT, "STAGE9-DISTRICT-A", "STAGE9-DISTRICT-A", DISTRICT);
        AuthContext.set(new AuthUser(userId, "stage9", "Stage 9", "ROLE-ADMIN", 0, false, "ALL"));
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
        jdbc.update("delete from app_user_data_scope where user_id=?", userId);
        jdbc.update("delete from app_user where user_id=?", userId);
        jdbc.update("delete from app_role_permission where role_code=?", ROLE);
    }

    @Test
    void catalogsStage9ActionsWithoutGrantingProductionRoles() {
        List<Map<String, Object>> rows = jdbc.queryForList("select permission_code, permission_kind, route_key from app_permission where permission_code in ('airspace:manage','airport:read','airport:manage','risk:evaluate') order by permission_code");
        assertThat(rows).extracting(row -> row.get("permission_code")).containsExactlyElementsOf(STAGE9_CODES);
        assertThat(rows).allSatisfy(row -> { assertThat(row.get("permission_kind")).isEqualTo("ACTION"); assertThat(row.get("route_key")).isNull(); });
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission where permission_code in ('airspace:manage','airport:read','airport:manage','risk:evaluate') and role_code<>'ROLE-ADMIN'", Integer.class)).isZero();
        for (String code : STAGE9_CODES) assertThat(java.util.Arrays.stream(PermissionCode.values()).map(PermissionCode::value)).contains(code);
    }

    @Test
    void airspaceAndRiskModulesStayAliasesWithoutMenuAfterRevert() {
        // 迁移 060 曾把两行升级为真实菜单，V202609070010 撤回（用户 2026-09-07 裁定）：行仍在、route_key 为空，
        // 因此不论超级管理员还是开启了 menu_enabled 的普通角色，菜单里都不会出现这两项；阶段 9 的动作权限不受影响。
        List<Map<String, Object>> modules = jdbc.queryForList("select permission_code, route_key from app_permission where permission_code in ('airspace','risk') order by permission_code");
        assertThat(modules).extracting(row -> row.get("permission_code")).containsExactly("airspace", "risk");
        assertThat(modules).extracting(row -> row.get("route_key")).containsOnlyNulls();
        assertThat(accessService.menuKeys("ROLE-ADMIN")).contains("flights").doesNotContain("airspace", "risk");
        assertThat(accessService.menuKeys(ROLE)).doesNotContain("airspace", "risk");
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'READ',true,current_timestamp)", ROLE, "risk");
        assertThat(accessService.menuKeys(ROLE)).doesNotContain("risk", "airspace");
    }

    @Test
    void menuPermissionDoesNotGrantStage9ActionsAndAssignedNeedsExactTuple() {
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'OP',true,current_timestamp)", ROLE, "airspace");
        assertForbidden(PermissionCode.AIRSPACE_MANAGE);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'OP',false,current_timestamp)", ROLE, PermissionCode.AIRSPACE_MANAGE.value());
        assertForbidden(PermissionCode.AIRSPACE_MANAGE);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)", userId, ORG, DISTRICT);
        assertThat(accessControlService.require(PermissionCode.AIRSPACE_MANAGE)).isEqualTo(new AccessDecision(userId, ScopeMode.ASSIGNED));
        // 空域管理不附带机场/风险评估权限；各服务分别校验。
        assertForbidden(PermissionCode.AIRPORT_MANAGE);
        assertForbidden(PermissionCode.RISK_EVALUATE);
    }

    private void assertForbidden(PermissionCode permission) {
        ApiException error = catchThrowableOfType(ApiException.class, () -> accessControlService.require(permission));
        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
