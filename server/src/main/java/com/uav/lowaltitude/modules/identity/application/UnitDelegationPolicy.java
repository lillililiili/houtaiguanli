package com.uav.lowaltitude.modules.identity.application;

import java.util.Set;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.identity.domain.UserType;

/** Business modules that are actually available to front-office users; device operations have no menu here. */
public final class UnitDelegationPolicy {
    private static final Set<String> MODULES = Set.of("dashboard", "sensing", "statistics", "flights",
            "legality", "airspace", "alarms", "risk", "punishment", "devices", "evidence");
    private static final Set<String> MENUS = Set.of("bigscreen", "situation", "flights", "legality",
            "alarms", "punish", "stats", "evidence");
    private UnitDelegationPolicy() {}
    public static boolean module(String code) { return MODULES.contains(code); }
    public static boolean menu(String key) { return key != null && MENUS.contains(key); }
    public static boolean member(String id, String role) {
        return role != null && UserType.forRole(role) == UserType.FRONTEND
                && !AutomationPrincipal.USER_ID.equals(id) && !AutomationPrincipal.ROLE.equals(role);
    }
}
