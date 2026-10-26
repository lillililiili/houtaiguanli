package com.uav.lowaltitude.platform.query;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/** Only for historical reads; mutation, live-data and dispatch eligibility keep current-directory checks. */
public final class OrganizationHistorySql {
    private OrganizationHistorySql() { }

    public static String readable(String alias, AccessDecision access) {
        if (!alias.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid SQL alias");
        return access.scopeMode() == ScopeMode.ALL
                ? "(" + alias + ".enabled=TRUE OR " + alias + ".deleted_at IS NOT NULL)"
                : "(" + alias + ".enabled=TRUE AND " + alias + ".deleted_at IS NULL)";
    }
}
