package com.uav.lowaltitude.modules.identity.domain;

/** 后台身份使用固定内置角色保存，避免用户类型与角色两份数据互相矛盾。 */
public enum UserType {
    FRONTEND, BACKEND;

    public static final String BACKEND_ROLE = "ROLE-BACKEND";

    public static UserType forRole(String roleCode) {
        return "ROLE-ADMIN".equals(roleCode) || BACKEND_ROLE.equals(roleCode) ? BACKEND : FRONTEND;
    }
}
