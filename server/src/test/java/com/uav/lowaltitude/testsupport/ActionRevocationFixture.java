package com.uav.lowaltitude.testsupport;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 超级管理员固定拥有全部动作权限（直接反制除外），服务端不看它的授权行，删行收不回权限。
 * 要验证“缺某一项动作权限”时，把账号换到一个复制了超管全部授权行、只缺这一项的自定义角色上。
 *
 * <p>只在测试事务里用（结束即回滚，账号角色和临时角色都不会留下）；换完之后调用方要清 MyBatis 一级缓存，
 * 否则同一事务里已读过的账号行仍是旧角色。
 */
public final class ActionRevocationFixture {

    private ActionRevocationFixture() {
    }

    /** 把 {@code account} 换到“超管授权减去 {@code permissionCode}”的临时角色，返回该角色码。 */
    public static String withoutAction(JdbcTemplate jdbc, String account, String permissionCode) {
        String role = "ROLE-NO-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, "缺权限测试角色-" + role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " select ?,permission_code,permission_level,menu_enabled,current_timestamp from app_role_permission"
                + " where role_code='ROLE-ADMIN' and permission_code<>?", role, permissionCode);
        jdbc.update("update app_user set role_code=? where account=?", role, account);
        return role;
    }
}
