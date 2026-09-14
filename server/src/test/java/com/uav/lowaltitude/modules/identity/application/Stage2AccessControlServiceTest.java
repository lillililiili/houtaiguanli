package com.uav.lowaltitude.modules.identity.application;

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
        "spring.datasource.url=jdbc:h2:mem:stage2_access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class Stage2AccessControlServiceTest {

    private static final String ROLE = "ROLE-DUTY";
    private static final String ORG = "stage2-org";
    private static final String DISTRICT = "stage2-district";

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
                ) select ?, 'Stage 2 duty role', '', false, true, 0, 0, 0, false
                where not exists (select 1 from app_role where role_code=?)
                """, ROLE, ROLE);
        jdbc.update("delete from app_role_permission where role_code=? and permission_code like '%:read'", ROLE);
        jdbc.update("update app_role set enabled=true where role_code=?", ROLE);
        jdbc.update("""
                insert into app_user (
                    user_id, account, name, role_code, status, password_hash, fail_count,
                    scope_mode, permission_version, created_at, updated_at, version
                ) values (?, ?, 'Stage 2 reader', ?, 'ACTIVE', 'hash', 0, 'NONE', 0, 0, 0, 0)
                """, userId, "stage2-" + userId, ROLE);
        jdbc.update("""
                insert into app_org (org_id, org_code, name, enabled, created_at, updated_at, version)
                select ?, 'STAGE2-ORG', 'Stage 2 org', true, 0, 0, 0
                where not exists (select 1 from app_org where org_id=?)
                """, ORG, ORG);
        jdbc.update("""
                insert into app_district (district_id, district_code, name, enabled, created_at, updated_at, version)
                select ?, 'STAGE2-DISTRICT', 'Stage 2 district', true, 0, 0, 0
                where not exists (select 1 from app_district where district_id=?)
                """, DISTRICT, DISTRICT);
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
        jdbc.update("delete from app_user_data_scope where user_id=?", userId);
        jdbc.update("delete from app_user where user_id=?", userId);
        jdbc.update("delete from app_role_permission where role_code=? and permission_code like '%:read'", ROLE);
        jdbc.update("update app_role set enabled=true where role_code=?", ROLE);
    }

    @Test
    void rejectsMissingSessionAsUnauthenticated() {
        ApiException error = catchThrowableOfType(
                ApiException.class,
                () -> accessControlService.require(PermissionCode.DEVICE_READ));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(error.getCode()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void rejectsDisabledUserRoleMissingPermissionAndNoneScope() {
        authenticate("ROLE-ADMIN");
        grant(PermissionCode.DEVICE_READ);
        jdbc.update("update app_user set scope_mode='ALL',status='DISABLED' where user_id=?", userId);
        assertForbidden(PermissionCode.DEVICE_READ);

        jdbc.update("update app_user set status='ACTIVE' where user_id=?", userId);
        jdbc.update("update app_role set enabled=false where role_code=?", ROLE);
        assertForbidden(PermissionCode.DEVICE_READ);

        jdbc.update("update app_role set enabled=true where role_code=?", ROLE);
        jdbc.update("delete from app_role_permission where role_code=? and permission_code=?",
                ROLE, PermissionCode.DEVICE_READ.value());
        assertForbidden(PermissionCode.DEVICE_READ);

        grant(PermissionCode.DEVICE_READ);
        jdbc.update("update app_user set scope_mode='NONE' where user_id=?", userId);
        assertForbidden(PermissionCode.DEVICE_READ);
    }

    @Test
    void assignedRequiresOneEnabledExactTuple() {
        authenticate(ROLE);
        grant(PermissionCode.TARGET_READ);
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?", userId);
        assertForbidden(PermissionCode.TARGET_READ);

        addScope();
        assertThat(accessControlService.require(PermissionCode.TARGET_READ))
                .isEqualTo(new AccessDecision(userId, ScopeMode.ASSIGNED));

        jdbc.update("update app_org set enabled=false where org_id=?", ORG);
        assertForbidden(PermissionCode.TARGET_READ);
        jdbc.update("update app_org set enabled=true where org_id=?", ORG);

        jdbc.update("update app_district set enabled=false where district_id=?", DISTRICT);
        assertForbidden(PermissionCode.TARGET_READ);
    }

    @Test
    void explicitAllAndDatabaseChangesTakeEffectOnTheNextCall() {
        authenticate("ROLE-ADMIN");
        grant(PermissionCode.ALARM_READ);
        jdbc.update("update app_user set scope_mode='ALL' where user_id=?", userId);

        assertThat(accessControlService.require(PermissionCode.ALARM_READ))
                .isEqualTo(new AccessDecision(userId, ScopeMode.ALL));

        jdbc.update("delete from app_role_permission where role_code=? and permission_code=?",
                ROLE, PermissionCode.ALARM_READ.value());
        assertForbidden(PermissionCode.ALARM_READ);
    }

    private void authenticate(String untrustedRole) {
        AuthContext.set(new AuthUser(userId, "stage2", "Stage 2", untrustedRole, 0, false, "ALL"));
    }

    private void grant(PermissionCode permission) {
        jdbc.update("""
                insert into app_role_permission (
                    role_code, permission_code, permission_level, menu_enabled, created_at
                ) values (?, ?, 'READ', false, current_timestamp)
                """, ROLE, permission.value());
    }

    private void addScope() {
        jdbc.update("""
                insert into app_user_data_scope (user_id, org_id, district_id, created_at)
                values (?, ?, ?, current_timestamp)
                """, userId, ORG, DISTRICT);
    }

    private void assertForbidden(PermissionCode permission) {
        ApiException error = catchThrowableOfType(
                ApiException.class, () -> accessControlService.require(permission));
        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error.getCode()).isEqualTo("FORBIDDEN");
    }
}
