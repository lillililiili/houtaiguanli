package com.uav.lowaltitude.modules.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.identity.domain.AppUser;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.PermissionRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.RoleRow;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.infrastructure.AccessControlMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

/** Isolated in-memory data; no platform configuration or acceptance account is changed. */
class OrgEffectivePermissionServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final IdentityAdminMapper identity = mock(IdentityAdminMapper.class);
    private final Map<String, AppUser> people = new HashMap<>();
    private JdbcTemplate jdbc;
    private OrgEffectivePermissionService effective;
    private String unitA, unitB, child, admin, member, peer, childMember;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_org(org_id VARCHAR PRIMARY KEY, enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE app_role(role_code VARCHAR PRIMARY KEY, owner_org_id VARCHAR, enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE app_org_delegation(org_id VARCHAR PRIMARY KEY, administrator_user_id VARCHAR)");
        jdbc.execute("CREATE TABLE app_org_permission(org_id VARCHAR, permission_code VARCHAR, permission_level VARCHAR, menu_enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE app_org_action(org_id VARCHAR, permission_code VARCHAR, permission_level VARCHAR)");
        jdbc.execute("CREATE TABLE app_permission(permission_code VARCHAR, permission_kind VARCHAR, route_key VARCHAR)");
        jdbc.update("INSERT INTO app_permission VALUES ('alarms','MODULE','alarms')");
        jdbc.update("INSERT INTO app_permission VALUES ('devices','MODULE',NULL)");
        when(users.findById(anyString())).thenAnswer(call -> people.get(call.getArgument(0)));
        when(identity.findRole(anyString())).thenAnswer(call -> {
            RoleRow role = new RoleRow();
            role.setRoleCode(call.getArgument(0));
            role.setEnabled(true);
            return role;
        });
        when(identity.listPermissionsForRole(anyString())).thenReturn(
                List.of(module("alarms", "alarms", "AUTH"), module("devices", null, "OP")));
        when(identity.listActionCodesForRole(anyString())).thenReturn(List.of("alarm:read", "alarm:verify",
                "disposal:execute", "disposal:direct"));
        effective = new OrgEffectivePermissionService(users, identity, new NamedParameterJdbcTemplate(source));
        unitA = unit();
        unitB = unit();
        child = unit();
        admin = person(unitA);
        member = person(unitA);
        peer = person(unitB);
        childMember = person(child);
        jdbc.update("UPDATE app_org_delegation SET administrator_user_id=? WHERE org_id=?", admin, unitA);
    }

    @AfterEach void cleanup() {
        AuthContext.clear();
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test void moduleLevelsAndActionsAreIntersectedWithThisUnitsCeiling() {
        assertThat(effective.permissionCodes(member)).contains("alarms.read", "alarm:read", "devices.op")
                .doesNotContain("alarms.op", "alarms.auth", "alarm:verify", "disposal:direct", "disposal:execute");
        jdbc.update("INSERT INTO app_org_action VALUES (?,'disposal:execute','OP')", unitB);
        assertThat(effective.permissionCodes(peer)).contains("disposal:execute");
        assertThat(effective.permissionCodes(member)).doesNotContain("disposal:execute");
        assertThat(effective.permissionCodes(childMember)).doesNotContain("disposal:execute");
    }

    @Test void menuCeilingAndActionCeilingAreIndependent() {
        assertThat(effective.menuKeys(member)).containsExactly("alarms");
        jdbc.update("UPDATE app_org_permission SET menu_enabled=FALSE WHERE org_id=?", unitA);
        assertThat(effective.menuKeys(member)).isEmpty();
        assertThat(effective.permissionCodes(member)).contains("alarm:read", "alarms.read");
    }

    @Test void ordinaryMembersNeverBecomeAdministratorsAndRevocationKeepsTheirCeiling() {
        assertThat(effective.isUnitAdministrator(admin)).isTrue();
        assertThat(effective.isUnitAdministrator(member)).isFalse();
        assertThat(effective.isUnitAdministrator(peer)).isFalse();
        assertThat(effective.isUnitAdministrator(childMember)).isFalse();
        jdbc.update("UPDATE app_org_delegation SET administrator_user_id=NULL WHERE org_id=?", unitA);
        assertThat(effective.isUnitAdministrator(admin)).isFalse();
        assertThat(effective.managedOrgId(admin)).isEqualTo(unitA);
        assertThat(effective.permissionCodes(admin)).doesNotContain("disposal:direct");
    }

    @Test void inactiveUnitsAndMismatchedRoleOrScopeFailClosed() {
        AppUser user = people.get(member);
        jdbc.update("UPDATE app_role SET owner_org_id=? WHERE role_code=?", unitB, user.getRoleCode());
        assertThat(effective.permissionCodes(member)).isEmpty();
        jdbc.update("UPDATE app_role SET owner_org_id=? WHERE role_code=?", unitA, user.getRoleCode());
        user.setScopeMode("ALL");
        assertThat(effective.permissionCodes(member)).isEmpty();
        user.setScopeMode("ASSIGNED");
        user.setScopeOrgRule("ALL_ORGS");
        assertThat(effective.permissionCodes(member)).isEmpty();
        user.setScopeOrgRule("OWN_ORG");
        jdbc.update("UPDATE app_org SET enabled=FALSE WHERE org_id=?", unitA);
        assertThat(effective.permissionCodes(member)).isEmpty();
        assertThat(effective.isUnitAdministrator(admin)).isFalse();
    }

    @Test void currentAccountStateIsReadAgainAfterDisable() {
        assertThat(effective.permissionCodes(member)).contains("alarm:read");
        people.get(member).setStatus("DISABLED");
        assertThat(effective.permissionCodes(member)).isEmpty();
        assertThat(effective.menuKeys(member)).isEmpty();
    }

    @Test void platformAndExplicitSystemPrincipalAreNotCapturedByOrgMembership() {
        AppUser backend = people.get(admin);
        backend.setRoleCode("ROLE-BACKEND");
        backend.setScopeMode("ALL");
        assertThat(effective.managedOrgId(admin)).isNull();
        assertThat(effective.isUnitAdministrator(admin)).isFalse();
        assertThat(effective.permissionCodes(admin)).contains("users.auth", "interfaces.auth");
        AppUser system = new AppUser();
        system.setUserId(AutomationPrincipal.USER_ID);
        system.setOrgId(unitA);
        system.setRoleCode(AutomationPrincipal.ROLE);
        system.setStatus("DISABLED");
        people.put(system.getUserId(), system);
        assertThat(effective.managedOrgId(system.getUserId())).isNull();
        assertThat(effective.isUnitAdministrator(system.getUserId())).isFalse();
        assertThat(effective.permissionCodes(system.getUserId())).isEmpty();
    }

    @Test void actionApiAndModuleApiRejectRevokedCeilingEvenWhenRoleStillGrantsIt() {
        var actionRows = mock(AccessControlMapper.class);
        when(actionRows.findGrantedScopeMode(member, "alarm:read")).thenReturn("ASSIGNED");
        when(actionRows.countValidAssignedScopes(member)).thenReturn(1);
        var actions = new DatabaseAccessControlService(actionRows, effective);
        var modules = new AccessService(identity, effective);
        AppUser user = people.get(member);
        AuthContext.set(new AuthUser(member, member, "member", user.getRoleCode(), 0, false, "ASSIGNED"));
        assertThat(actions.require(PermissionCode.ALARM_READ).userId()).isEqualTo(member);
        modules.require("devices.op");
        jdbc.update("DELETE FROM app_org_action WHERE org_id=?", unitA);
        jdbc.update("UPDATE app_org_permission SET permission_level='READ' WHERE org_id=?", unitA);
        assertThatThrownBy(() -> actions.require(PermissionCode.ALARM_READ)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> modules.require("devices.op")).isInstanceOf(ApiException.class);
        assertThat(modules.canAccessTuple(unitB, "some-district")).isFalse();
    }

    private String unit() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_org VALUES (?,TRUE)", id);
        jdbc.update("INSERT INTO app_org_delegation VALUES (?,NULL)", id);
        jdbc.update("INSERT INTO app_org_permission VALUES (?,'alarms','READ',TRUE)", id);
        jdbc.update("INSERT INTO app_org_permission VALUES (?,'devices','OP',FALSE)", id);
        jdbc.update("INSERT INTO app_org_action VALUES (?,'alarm:read','OP')", id);
        return id;
    }

    private String person(String org) {
        String id = UUID.randomUUID().toString(), role = "ROLE-" + UUID.randomUUID();
        AppUser user = new AppUser();
        user.setUserId(id);
        user.setOrgId(org);
        user.setRoleCode(role);
        user.setStatus("ACTIVE");
        user.setScopeMode("ASSIGNED");
        user.setScopeOrgRule("OWN_ORG");
        people.put(id, user);
        jdbc.update("INSERT INTO app_role VALUES (?,?,TRUE)", role, org);
        return id;
    }

    private PermissionRow module(String code, String route, String level) {
        PermissionRow row = new PermissionRow();
        row.setPermissionCode(code);
        row.setRouteKey(route);
        row.setPermissionLevel(level);
        row.setMenuEnabled(true);
        return row;
    }
}
