package com.uav.lowaltitude.modules.identity.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.identity.api.UnitDelegationDtos.ManagementPermission;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;

/** Unit management grants are independent of front-office business role permissions. */
@Service
public class UnitManagementAccessService {
    public static final List<String> MODULE_KEYS=List.of("devices","monitoring","maintenance","commissioning","statistics");
    private static final String SUBJECT="""
        FROM app_org_delegation d
        JOIN app_org o ON o.org_id=d.org_id AND o.enabled=TRUE
        JOIN app_user u ON u.user_id=d.administrator_user_id AND u.org_id=d.org_id
        JOIN app_role r ON r.role_code=u.role_code AND r.owner_org_id=d.org_id AND r.enabled=TRUE
        """;
    private static final String VALID="""
        WHERE u.user_id=:user AND u.status='ACTIVE' AND u.scope_mode='ASSIGNED' AND u.scope_org_rule='OWN_ORG'
          AND u.role_code NOT IN ('ROLE-ADMIN','ROLE-BACKEND','ROLE-AUTOMATION')
          AND u.user_id<>'automation-rule-runner'
        """;
    private final NamedParameterJdbcTemplate jdbc;
    public UnitManagementAccessService(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** Reads the database on every call, including dispatchers which have no browser session. */
    public String orgId(String userId) {
        if(userId==null||userId.isBlank()) return null;
        List<String> found=jdbc.query("SELECT d.org_id "+SUBJECT+VALID,Map.of("user",userId),(rs,n)->rs.getString(1));
        return found.isEmpty()?null:found.get(0);
    }
    public String require(String permissionCode) {
        var actor=AuthContext.require();
        if(actor.mustChangePassword()) throw new ApiException(HttpStatus.FORBIDDEN,"PASSWORD_CHANGE_REQUIRED","请先修改临时密码");
        String org=authorizedOrg(actor.userId(),permissionCode);
        if(org==null) throw new ApiException(HttpStatus.FORBIDDEN,"UNIT_MANAGEMENT_FORBIDDEN","未获本单位该项后台管理权限");
        return org;
    }
    public boolean allows(String userId,String permissionCode) { return authorizedOrg(userId,permissionCode)!=null; }
    public List<String> permissionCodes(String userId) {
        List<String> result=new ArrayList<>();
        for(ManagementPermission grant:grants(userId)) {
            if(!MODULE_KEYS.contains(grant.moduleKey())) continue;
            if("READ".equals(grant.level())||"OP".equals(grant.level())) result.add(grant.moduleKey()+".read");
            if("OP".equals(grant.level())&&!"monitoring".equals(grant.moduleKey())) result.add(grant.moduleKey()+".op");
        }
        return List.copyOf(result);
    }
    public List<String> menuKeys(String userId) {
        var codes=permissionCodes(userId);
        return MODULE_KEYS.stream().filter(key->codes.contains(key+".read")).toList();
    }
    private List<ManagementPermission> grants(String userId) {
        if(userId==null||userId.isBlank()) return List.of();
        return jdbc.query("SELECT g.module_key,g.permission_level "+SUBJECT+
                " JOIN app_org_management_permission g ON g.org_id=d.org_id "+VALID+" AND u.must_change_password=FALSE ORDER BY g.module_key",
                Map.of("user",userId),(rs,n)->new ManagementPermission(rs.getString(1),rs.getString(2)));
    }
    private String authorizedOrg(String userId,String code) {
        if(userId==null||code==null) return null;
        int dot=code.lastIndexOf('.');
        if(dot<1) return null;
        String module=code.substring(0,dot),action=code.substring(dot+1);
        if(!MODULE_KEYS.contains(module)||(!"read".equals(action)&&!"op".equals(action))
                ||("monitoring".equals(module)&&"op".equals(action))) return null;
        List<String> found=jdbc.query("SELECT d.org_id "+SUBJECT+
                " JOIN app_org_management_permission g ON g.org_id=d.org_id "+VALID+
                " AND u.must_change_password=FALSE AND g.module_key=:module AND "+("op".equals(action)?"g.permission_level='OP'":"g.permission_level IN ('READ','OP')"),
                Map.of("user",userId,"module",module),(rs,n)->rs.getString(1));
        return found.isEmpty()?null:found.get(0);
    }
}
