package com.uav.lowaltitude.modules.identity.domain;

import java.util.Set;

/** 当前业务前台实际使用的动作；服务端目录、角色赋权和两套鉴权入口共用。 */
public final class FrontendActionPolicy {
    private static final Set<String> ACTIONS = Set.of(
            "device:read", "target:read", "alarm:read", "alarm:verify",
            "flight:read", "route:read", "airspace:read",
            "assessment:read", "assessment:evaluate", "assessment:revise", "assessment:escalate",
            "risk:read", "risk:verify", "handoff:read", "handoff:create", "rule:read", "fusion:read",
            "evidence:read", "evidence:preview", "evidence:ingest", "evidence:download",
            "evidence:hold", "evidence:destroy",
            "disposal:read", "disposal:request", "disposal:approve", "disposal:direct",
            "disposal:execute", "disposal:stop", "punishment:read");

    private FrontendActionPolicy() { }

    public static boolean isAssignable(String permissionCode) {
        return permissionCode != null && ACTIONS.contains(permissionCode);
    }

    /** 后台固定身份的现有能力独立保留；本策略不产生任何新授权。 */
    public static boolean isAvailable(String roleCode, String permissionCode) {
        return UserType.forRole(roleCode) == UserType.BACKEND || isAssignable(permissionCode);
    }
}
