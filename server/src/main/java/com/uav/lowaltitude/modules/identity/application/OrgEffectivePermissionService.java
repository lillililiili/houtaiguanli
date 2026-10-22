package com.uav.lowaltitude.modules.identity.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.identity.domain.AppUser;
import com.uav.lowaltitude.modules.identity.domain.UserType;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;

/**
 * The user's current role intersected with the unit ceiling. This service is also used by
 * dispatchers without a browser session; no grant is cached across revocation.
 */
@Service
public class OrgEffectivePermissionService {
    private final UserMapper users;
    private final IdentityAdminMapper identity;
    private final NamedParameterJdbcTemplate jdbc;
    private final org.mybatis.spring.SqlSessionTemplate sqlSession;

    public OrgEffectivePermissionService(UserMapper users, IdentityAdminMapper identity,
                                         NamedParameterJdbcTemplate jdbc) {
        this(users, identity, jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrgEffectivePermissionService(UserMapper users, IdentityAdminMapper identity,
                                         NamedParameterJdbcTemplate jdbc,
                                         org.mybatis.spring.SqlSessionTemplate sqlSession) {
        this.users = users;
        this.identity = identity;
        this.jdbc = jdbc;
        this.sqlSession = sqlSession;
    }

    /** Membership remains managed after the administrator is removed. */
    public String managedOrgId(String userId) {
        freshAuthorization();
        AppUser user = users.findById(userId);
        return managedOrg(user);
    }

    public boolean isUnitAdministrator(String userId) {
        freshAuthorization();
        AppUser user = activeUser(userId);
        if (user == null || !managedCandidate(user)) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_org_delegation g
                JOIN app_org o ON o.org_id=g.org_id AND o.enabled=TRUE
                WHERE g.org_id=:org AND g.administrator_user_id=:user
                """, Map.of("org", user.getOrgId(), "user", userId), Integer.class);
        return count != null && count == 1;
    }

    public List<String> permissionCodes(String userId) {
        freshAuthorization();
        AppUser user = activeUser(userId);
        if (user == null) return List.of();
        List<String> roleCodes = AccessService.rolePermissionCodes(identity, user.getRoleCode());
        String orgId = managedOrg(user);
        if (orgId == null) return roleCodes;
        if (!validManagedMembership(user, orgId)) return List.of();
        var allowed = new LinkedHashSet<String>();
        jdbc.query("""
                SELECT permission_code, permission_level FROM app_org_permission WHERE org_id=:org
                """, Map.of("org", orgId), rs -> {
            String code = rs.getString("permission_code");
            int level = AccessService.level(rs.getString("permission_level"));
            if (level >= 1) allowed.add(code + ".read");
            if (level >= 2) allowed.add(code + ".op");
            if (level >= 3) allowed.add(code + ".auth");
        });
        allowed.addAll(jdbc.query("""
                SELECT permission_code FROM app_org_action WHERE org_id=:org AND permission_level='OP'
                """, Map.of("org", orgId), (rs, n) -> rs.getString(1)));
        return roleCodes.stream().filter(allowed::contains).distinct().toList();
    }

    public List<String> menuKeys(String userId) {
        freshAuthorization();
        AppUser user = activeUser(userId);
        if (user == null) return List.of();
        List<String> roleMenus = AccessService.roleMenuKeys(identity, user.getRoleCode());
        String orgId = managedOrg(user);
        if (orgId == null) return roleMenus;
        if (!validManagedMembership(user, orgId)) return List.of();
        var menus = jdbc.query("""
                SELECT p.route_key FROM app_org_permission g
                JOIN app_permission p ON p.permission_code=g.permission_code AND p.permission_kind='MODULE'
                WHERE g.org_id=:org AND g.menu_enabled=TRUE
                  AND g.permission_level IN ('READ','OP','AUTH') AND p.route_key IS NOT NULL
                """, Map.of("org", orgId), (rs, n) -> rs.getString(1));
        return roleMenus.stream().filter(menus::contains).distinct().toList();
    }

    private AppUser activeUser(String userId) {
        if (userId == null || userId.isBlank()) return null;
        AppUser user = users.findById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) return null;
        var role = identity.findRole(user.getRoleCode());
        return role != null && role.isEnabled() ? user : null;
    }

    private void freshAuthorization() {
        // A dispatcher may recheck in one transaction after JDBC updates or concurrent revocation.
        // MyBatis SESSION cache must not turn that check into a cached copy of an earlier grant.
        if (sqlSession != null) sqlSession.clearCache();
    }

    private String managedOrg(AppUser user) {
        if (!managedCandidate(user)) return null;
        var found = jdbc.query("SELECT org_id FROM app_org_delegation WHERE org_id=:org",
                Map.of("org", user.getOrgId()), (rs, n) -> rs.getString(1));
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean validManagedMembership(AppUser user, String orgId) {
        // A stale/global/foreign role must not bypass the migration or leak another unit's roles.
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_role r JOIN app_org o ON o.org_id=r.owner_org_id
                WHERE r.role_code=:role AND r.owner_org_id=:org AND r.enabled=TRUE AND o.enabled=TRUE
                """, Map.of("role", user.getRoleCode(), "org", orgId), Integer.class);
        return count != null && count == 1 && "ASSIGNED".equals(user.getScopeMode())
                && "OWN_ORG".equals(user.getScopeOrgRule());
    }

    private static boolean managedCandidate(AppUser user) {
        return user != null && user.getOrgId() != null
                && !AutomationPrincipal.USER_ID.equals(user.getUserId())
                && UserType.forRole(user.getRoleCode()) == UserType.FRONTEND;
    }
}
