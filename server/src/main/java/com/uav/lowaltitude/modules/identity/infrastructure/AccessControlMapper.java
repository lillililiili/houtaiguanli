package com.uav.lowaltitude.modules.identity.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccessControlMapper {

    // MODULE/menu_enabled 只决定导航可见性；服务端业务动作必须命中 ACTION 目录，不能把“看得到菜单”当成写权限。
    // 超级管理员固定拥有全部动作权限（docs/系统管理接口.md），与 AccessService 下发的权限码同源：按整份动作目录放行，
    // 不依赖授权行——这批行只有演示种子会插，空库（正式部署）里一条都没有，超管登录后入口可见、接口却全是 403。
    // 直接反制仍是例外：任何角色（含超级管理员）都必须有显式 OP 授权行。角色以库里为准，不信会话里的角色码。
    @Select("""
            SELECT u.scope_mode
            FROM app_user u
            JOIN app_role r
              ON r.role_code = u.role_code
             AND r.enabled = TRUE
            JOIN app_permission p
              ON p.permission_code = #{permissionCode}
             AND p.permission_kind = 'ACTION'
            LEFT JOIN app_role_permission rp
              ON rp.role_code = r.role_code
             AND rp.permission_code = p.permission_code
             AND rp.permission_level IN ('READ', 'OP', 'AUTH')
            WHERE u.user_id = #{userId}
              AND u.status = 'ACTIVE'
              AND ((p.permission_code = 'disposal:direct' AND rp.permission_level = 'OP')
                OR (p.permission_code <> 'disposal:direct'
                    AND (rp.permission_code IS NOT NULL OR r.role_code = 'ROLE-ADMIN')))
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
