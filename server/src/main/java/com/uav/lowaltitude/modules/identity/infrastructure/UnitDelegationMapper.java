package com.uav.lowaltitude.modules.identity.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.PermissionRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.RoleRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.UserAdminRow;
import com.uav.lowaltitude.modules.identity.api.UnitDelegationDtos.ManagementPermission;

@Mapper
public interface UnitDelegationMapper {
    record Delegation(String orgId, String administratorUserId, int version) {}
    record Preview(String token, String orgId, String actorId, String requestHash, String snapshotHash,
            long expiresAt, boolean consumed) {}
    record BusinessCount(String orgId, String orgName, String districtId, String resourceType, long total) {}

    @Select("SELECT org_id FROM app_org WHERE org_id=#{orgId} AND deleted_at IS NULL FOR UPDATE")
    String lockOrg(String orgId);
    @Select("SELECT org_id,administrator_user_id,version FROM app_org_delegation WHERE org_id=#{orgId}")
    Delegation delegation(String orgId);
    @Select("SELECT d.org_id,d.administrator_user_id,d.version " + UnitAdministratorSql.SUBJECT
            + " WHERE u.user_id=#{userId} AND " + UnitAdministratorSql.VALID)
    @Options(flushCache=Options.FlushCachePolicy.TRUE, useCache=false)
    Delegation administrator(String userId);
    @Insert("INSERT INTO app_org_delegation(org_id,administrator_user_id,version,created_at,updated_at) VALUES(#{orgId},#{adminId},1,#{at},#{at})")
    int insertDelegation(@Param("orgId") String orgId,@Param("adminId") String adminId,@Param("at") long at);
    @Update("UPDATE app_org_delegation SET administrator_user_id=#{adminId},version=version+1,updated_at=#{at} WHERE org_id=#{orgId} AND version=#{version}")
    int updateDelegation(@Param("orgId") String orgId,@Param("adminId") String adminId,@Param("version") int version,@Param("at") long at);
    @Select("""
        SELECT p.permission_code AS permissionCode,p.module_name AS moduleName,p.route_key AS routeKey,
          p.sort_order AS sortOrder,op.permission_level AS permissionLevel,op.menu_enabled AS menuEnabled
        FROM app_org_permission op JOIN app_permission p ON p.permission_code=op.permission_code
        WHERE op.org_id=#{orgId} ORDER BY p.sort_order,p.permission_code
        """)
    List<PermissionRow> permissions(String orgId);
    @Select("SELECT permission_code FROM app_org_action WHERE org_id=#{orgId} AND permission_level='OP' ORDER BY permission_code")
    List<String> actions(String orgId);
    @Select("SELECT module_key,permission_level AS level FROM app_org_management_permission WHERE org_id=#{orgId} ORDER BY module_key")
    List<ManagementPermission> managementPermissions(String orgId);
    @Delete("DELETE FROM app_org_management_permission WHERE org_id=#{orgId}") int deleteManagementPermissions(String orgId);
    @Insert("INSERT INTO app_org_management_permission(org_id,module_key,permission_level) VALUES(#{orgId},#{moduleKey},#{level})")
    int insertManagementPermission(@Param("orgId") String orgId,@Param("moduleKey") String moduleKey,@Param("level") String level);
    @Delete("DELETE FROM app_org_permission WHERE org_id=#{orgId}") int deletePermissions(String orgId);
    @Delete("DELETE FROM app_org_action WHERE org_id=#{orgId}") int deleteActions(String orgId);
    @Insert("INSERT INTO app_org_permission(org_id,permission_code,permission_level,menu_enabled) VALUES(#{orgId},#{code},#{level},#{menu})")
    int insertPermission(@Param("orgId") String orgId,@Param("code") String code,@Param("level") String level,@Param("menu") boolean menu);
    @Insert("INSERT INTO app_org_action(org_id,permission_code,permission_level) VALUES(#{orgId},#{code},'OP')")
    int insertAction(@Param("orgId") String orgId,@Param("code") String code);
    @Select("SELECT owner_org_id FROM app_role WHERE role_code=#{roleCode}") String roleOwner(String roleCode);
    @Select("""
        SELECT r.role_code AS roleCode,r.name,r.description,r.builtin,r.enabled,r.version,
          r.owner_org_id AS ownerOrgId,r.copied_from_role_code AS copiedFromRoleCode,
          r.copied_from_role_name AS copiedFromRoleName,r.copied_at AS copiedAt,
          (SELECT COUNT(*) FROM app_user u WHERE u.role_code=r.role_code AND u.status<>'DELETED') AS userCount
        FROM app_role r WHERE r.owner_org_id=#{orgId} ORDER BY r.name,r.role_code
        """)
    List<RoleRow> roles(String orgId);
    @Insert("""
        INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,owner_org_id,name_scope)
        VALUES(#{code},#{name},#{description},FALSE,#{enabled},#{at},#{at},0,#{orgId},#{orgId})
        """)
    int insertRole(@Param("code") String code,@Param("name") String name,@Param("description") String description,
        @Param("enabled") boolean enabled,@Param("at") long at,@Param("orgId") String orgId);
    @Select("SELECT COUNT(*) FROM pending_user_registration WHERE role_code=#{code}")
    int pendingRegistrationReferences(String code);
    @Update("UPDATE app_role SET enabled=#{enabled},version=version+1,updated_at=#{at} WHERE role_code=#{code} AND version=#{version}")
    int roleStatus(@Param("code") String code,@Param("enabled") boolean enabled,@Param("version") int version,@Param("at") long at);
    @Select("""
        SELECT u.user_id AS userId,u.account,u.name,u.phone,u.org_id AS orgId,o.name AS orgName,
          u.role_code AS roleCode,r.name AS roleName,u.status,u.scope_mode AS scopeMode,
          u.scope_org_rule AS scopeOrgRule,u.must_change_password AS mustChangePassword,
          CASE WHEN EXISTS(SELECT 1 FROM app_session s WHERE s.user_id=u.user_id AND s.expire_at>#{now}) THEN TRUE ELSE FALSE END AS online,
          u.last_login_at AS lastLoginAt,u.last_login_ip AS lastLoginIp,u.created_at AS createdAt,u.version
        FROM app_user u JOIN app_role r ON r.role_code=u.role_code JOIN app_org o ON o.org_id=u.org_id
        WHERE u.org_id=#{orgId} AND u.status<>'DELETED'
          AND u.role_code NOT IN ('ROLE-ADMIN','ROLE-BACKEND','ROLE-AUTOMATION') AND u.user_id<>'automation-rule-runner'
        ORDER BY u.created_at,u.user_id
        """)
    List<UserAdminRow> users(@Param("orgId") String orgId,@Param("now") long now);
    @Update("UPDATE app_user SET permission_version=permission_version+1 WHERE user_id=#{id}")
    int bumpUser(String id);
    @Insert("INSERT INTO app_org_delegation_preview(token,org_id,actor_id,request_hash,snapshot_hash,expires_at,consumed) VALUES(#{token},#{orgId},#{actorId},#{requestHash},#{snapshotHash},#{expiresAt},FALSE)")
    int savePreview(Preview row);
    @Select("SELECT token,org_id,actor_id,request_hash,snapshot_hash,expires_at,consumed FROM app_org_delegation_preview WHERE token=#{token} FOR UPDATE")
    Preview preview(String token);
    @Update("UPDATE app_org_delegation_preview SET consumed=TRUE WHERE token=#{token} AND consumed=FALSE")
    int consumePreview(String token);
    @Select("""
        SELECT b.org_id,o.name AS org_name,b.district_id,b.resource_type,b.total
        FROM (
          SELECT owner_org_id AS org_id,district_id,'FLIGHT_PLAN' AS resource_type,COUNT(*) AS total FROM flight_plan GROUP BY owner_org_id,district_id
          UNION ALL SELECT owner_org_id,district_id,'ALARM',COUNT(*) FROM alarm GROUP BY owner_org_id,district_id
          UNION ALL SELECT owner_org_id,district_id,'FLIGHT_RISK',COUNT(*) FROM flight_risk GROUP BY owner_org_id,district_id
          UNION ALL SELECT owner_org_id,district_id,'DISPOSAL_AUTHORIZATION',COUNT(*) FROM disposal_authorization GROUP BY owner_org_id,district_id
        ) b JOIN app_org o ON o.org_id=b.org_id WHERE b.org_id<>#{orgId}
        ORDER BY b.org_id,b.resource_type,b.district_id
        """)
    List<BusinessCount> crossOrgCounts(String orgId);
    @Select("""
        SELECT p.plan_id,p.owner_org_id,p.operator_org_id FROM flight_plan p
        WHERE p.operator_org_id=#{orgId} AND (p.owner_org_id IS NULL OR p.owner_org_id<>#{orgId}) ORDER BY p.plan_id
        """)
    List<Map<String,Object>> crossOrgPlanLinks(String orgId);
}
