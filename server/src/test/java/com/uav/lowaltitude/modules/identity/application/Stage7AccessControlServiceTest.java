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

/** 阶段 7 规则引擎动作权限：只登记目录不授权，菜单可见性不等于动作，写动作仍要求精确范围元组。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stage7_access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class Stage7AccessControlServiceTest {

    private static final String ROLE = "ROLE-STAGE7-DUTY";
    private static final String ORG_A = "stage7-org-a";
    private static final String DISTRICT_A = "stage7-district-a";
    private static final List<String> STAGE7_CODES = List.of(
            "assessment:escalate", "assessment:evaluate", "assessment:revise", "rule:manage", "rule:read");

    @Autowired AccessControlService accessControlService;
    @Autowired JdbcTemplate jdbc;

    String userId;

    @BeforeEach
    void createDefaultDeniedUser() {
        AuthContext.clear();
        userId = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) select ?,'Stage 7 duty role','',false,true,0,0,0,false where not exists (select 1 from app_role where role_code=?)", ROLE, ROLE);
        jdbc.update("delete from app_role_permission where role_code=?", ROLE);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,'Stage 7 operator',?,'ACTIVE','hash',0,'ASSIGNED',0,0,0,0)", userId, "stage7-" + userId, ROLE);
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,0,0,0 where not exists (select 1 from app_org where org_id=?)", ORG_A, "STAGE7-ORG-A", "STAGE7-ORG-A", ORG_A);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,0,0,0 where not exists (select 1 from app_district where district_id=?)", DISTRICT_A, "STAGE7-DISTRICT-A", "STAGE7-DISTRICT-A", DISTRICT_A);
        AuthContext.set(new AuthUser(userId, "stage7", "Stage 7", "ROLE-ADMIN", 0, false, "ALL"));
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
        jdbc.update("delete from app_user_data_scope where user_id=?", userId);
        jdbc.update("delete from app_user where user_id=?", userId);
        jdbc.update("delete from app_role_permission where role_code=?", ROLE);
    }

    @Test
    void catalogsStage7ActionsWithoutGrantingProductionRoles() {
        List<Map<String, Object>> rows = jdbc.queryForList("select permission_code, permission_kind, route_key, module_code from app_permission where permission_code in ('rule:read','rule:manage','assessment:evaluate','assessment:revise','assessment:escalate') order by permission_code");
        assertThat(rows).extracting(row -> row.get("permission_code")).containsExactlyElementsOf(STAGE7_CODES);
        assertThat(rows).allSatisfy(row -> { assertThat(row.get("permission_kind")).isEqualTo("ACTION"); assertThat(row.get("route_key")).isNull(); });
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission where permission_code in ('rule:read','rule:manage','assessment:evaluate','assessment:revise','assessment:escalate') and role_code<>'ROLE-ADMIN'", Integer.class)).isZero();
        // 规则引擎自身的来源目录由迁移登记：三种模式各一行，启用但不带凭据。
        assertThat(jdbc.queryForObject("select count(*) from integration_source where source_code in ('RULE-ENGINE-LEGALITY-MOCK','RULE-ENGINE-LEGALITY-REPLAY','RULE-ENGINE-LEGALITY-LIVE') and enabled=true and credential_ref is null", Integer.class)).isEqualTo(3);
    }

    @Test
    void enumCoversEveryStage7CatalogRow() {
        for (String code : STAGE7_CODES) {
            assertThat(java.util.Arrays.stream(PermissionCode.values()).map(PermissionCode::value)).contains(code);
        }
    }

    @Test
    void assessmentConclusionAcceptsAbnormalAndKeepsAppendOnlyColumns() {
        List<Map<String, Object>> columns = jdbc.queryForList("select lower(column_name) as column_name from information_schema.columns where lower(table_name)='assessment_result' and lower(column_name) in ('evaluation_id','rule_set_version_id','supersedes_assessment_id')");
        assertThat(columns).extracting(row -> row.get("column_name")).containsExactlyInAnyOrder("evaluation_id", "rule_set_version_id", "supersedes_assessment_id");
        // 四态判定需要 ABNORMAL；旧 CHECK 只允许三态加不适用，迁移 040 必须重建约束。
        String planId = jdbc.queryForObject("select plan_id from flight_plan order by plan_id limit 1", String.class);
        String routeVersionId = jdbc.queryForObject("select route_version_id from flight_plan where plan_id=?", String.class, planId);
        String ruleVersionId = jdbc.queryForObject("select rule_version_id from rule_version order by rule_version_id limit 1", String.class);
        String id = "stage7-abnormal-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at) values (?,?,?,?,current_timestamp,'ABNORMAL',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),'mock',current_timestamp)", id, planId, routeVersionId, ruleVersionId);
        assertThat(jdbc.queryForObject("select conclusion_code from assessment_result where assessment_id=?", String.class, id)).isEqualTo("ABNORMAL");
        jdbc.update("delete from assessment_result where assessment_id=?", id);
    }

    @Test
    void menuPermissionDoesNotGrantRuleActionsAndAssignedNeedsExactTuple() {
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'READ',false,current_timestamp)", ROLE, "legality");
        assertForbidden(PermissionCode.ASSESSMENT_REVISE);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'OP',false,current_timestamp)", ROLE, PermissionCode.ASSESSMENT_REVISE.value());
        assertForbidden(PermissionCode.ASSESSMENT_REVISE);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)", userId, ORG_A, DISTRICT_A);
        assertThat(accessControlService.require(PermissionCode.ASSESSMENT_REVISE)).isEqualTo(new AccessDecision(userId, ScopeMode.ASSIGNED));
        // 拥有修订动作不代表拥有研判读权限；服务层必须分别校验，这里只证明动作码彼此独立。
        assertForbidden(PermissionCode.ASSESSMENT_READ);
        assertForbidden(PermissionCode.RULE_MANAGE);
    }

    private void assertForbidden(PermissionCode permission) {
        ApiException error = catchThrowableOfType(ApiException.class, () -> accessControlService.require(permission));
        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
