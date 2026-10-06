package com.uav.lowaltitude.modules.identity.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 空库（不跑演示种子）里 ROLE-ADMIN 没有任何动作授权行。超级管理员的动作权限与 AccessService 下发给页面的权限码同源：
 * 隐式继承整份动作目录（直接反制除外），否则新装系统的超管能看到“研判规则集”等入口，点进去却全是 403。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SuperAdminImplicitActionGrantTest {

    @Autowired AccessControlService accessControlService;
    @Autowired AccessService accessService;
    @Autowired JdbcTemplate jdbc;
    // 测试事务里 MyBatis 一级缓存会记住上一次判权结果；直接改库后要清掉，否则读到的是改之前的授权。
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

    String adminId;

    @BeforeEach
    void simulateCleanDatabaseWithoutDemoSeed() {
        // 测试库跑过演示种子（LocalStage2AccessSeeder 给 ROLE-ADMIN 插了动作行），删掉即回到空库的授权状态；事务结束后回滚。
        jdbc.update("""
                delete from app_role_permission where role_code = 'ROLE-ADMIN'
                  and permission_code in (select permission_code from app_permission where permission_kind = 'ACTION')
                """);
        adminId = jdbc.queryForObject("select user_id from app_user where role_code = 'ROLE-ADMIN'", String.class);
        // 会话里的角色码不可信，故意写成普通角色：授权只看库里的角色。
        AuthContext.set(new AuthUser(adminId, "admin1", "超级管理员", "ROLE-DUTY", 0, false, "ALL"));
    }

    @AfterEach
    void clearSession() {
        AuthContext.clear();
    }

    @Test
    void superAdminInheritsEveryActionAdvertisedToTheConsoleWithoutGrantRows() {
        for (PermissionCode permission : PermissionCode.values()) {
            if (permission == PermissionCode.DISPOSAL_DIRECT) continue;
            assertThat(require(permission)).as(permission.value())
                    .isEqualTo(new AccessDecision(adminId, ScopeMode.ALL));
        }
        assertThat(accessService.permissionCodes("ROLE-ADMIN"))
                .contains(PermissionCode.RULE_READ.value(), PermissionCode.RULE_MANAGE.value())
                .doesNotContain(PermissionCode.DISPOSAL_DIRECT.value());
    }

    @Test
    void directCountermeasureStillNeedsExplicitOpGrantAndDisabledAdminIsRejected() {
        assertForbidden(PermissionCode.DISPOSAL_DIRECT);
        jdbc.update("""
                insert into app_role_permission (role_code, permission_code, permission_level, menu_enabled, created_at)
                values ('ROLE-ADMIN', 'disposal:direct', 'READ', false, current_timestamp)
                """);
        assertForbidden(PermissionCode.DISPOSAL_DIRECT);
        jdbc.update("update app_role_permission set permission_level = 'OP' where role_code = 'ROLE-ADMIN' and permission_code = 'disposal:direct'");
        assertThat(require(PermissionCode.DISPOSAL_DIRECT)).isEqualTo(new AccessDecision(adminId, ScopeMode.ALL));

        jdbc.update("update app_user set status = 'DISABLED' where user_id = ?", adminId);
        assertForbidden(PermissionCode.RULE_READ);
    }

    @Test
    void otherRolesStillNeedTheirOwnGrantRows() {
        jdbc.update("""
                insert into app_role (role_code, name, description, builtin, enabled, created_at, updated_at, version, system_role)
                values ('ROLE-ITEST-PLAIN', '集成测试普通角色', '', false, true, 0, 0, 0, false)
                """);
        jdbc.update("update app_user set role_code = 'ROLE-ITEST-PLAIN' where user_id = ?", adminId);
        assertForbidden(PermissionCode.RULE_READ);
        jdbc.update("""
                insert into app_role_permission (role_code, permission_code, permission_level, menu_enabled, created_at)
                values ('ROLE-ITEST-PLAIN', 'rule:read', 'READ', false, current_timestamp)
                """);
        assertThat(require(PermissionCode.RULE_READ)).isEqualTo(new AccessDecision(adminId, ScopeMode.ALL));
        assertForbidden(PermissionCode.RULE_MANAGE);
    }

    private AccessDecision require(PermissionCode permission) {
        sqlSession.clearCache();
        return accessControlService.require(permission);
    }

    private void assertForbidden(PermissionCode permission) {
        ApiException error = catchThrowableOfType(ApiException.class, () -> require(permission));
        assertThat(error).as(permission.value()).isNotNull();
        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error.getCode()).isEqualTo("FORBIDDEN");
    }
}
