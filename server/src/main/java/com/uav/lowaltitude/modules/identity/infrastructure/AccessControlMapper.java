package com.uav.lowaltitude.modules.identity.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccessControlMapper {

    // MODULE/menu_enabled 只决定导航可见性；服务端业务动作必须命中 ACTION 目录，不能把“看得到菜单”当成写权限。
    @Select("""
            SELECT u.scope_mode
            FROM app_user u
            JOIN app_role r
              ON r.role_code = u.role_code
             AND r.enabled = TRUE
            JOIN app_role_permission rp
              ON rp.role_code = r.role_code
             AND rp.permission_level IN ('READ', 'OP', 'AUTH')
            JOIN app_permission p
              ON p.permission_code = rp.permission_code
             AND p.permission_kind = 'ACTION'
            WHERE u.user_id = #{userId}
              AND u.status = 'ACTIVE'
              AND p.permission_code = #{permissionCode}
            """)
    String findGrantedScopeMode(
            @Param("userId") String userId,
            @Param("permissionCode") String permissionCode);

    @Select("""
            SELECT COUNT(*)
            FROM app_user_data_scope s
            JOIN app_org o
              ON o.org_id = s.org_id
             AND o.enabled = TRUE
            JOIN app_district d
              ON d.district_id = s.district_id
             AND d.enabled = TRUE
            WHERE s.user_id = #{userId}
            """)
    int countValidAssignedScopes(@Param("userId") String userId);
}
