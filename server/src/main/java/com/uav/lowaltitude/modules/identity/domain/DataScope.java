package com.uav.lowaltitude.modules.identity.domain;

/**
 * 账号的数据范围（ZT-14），对外字段 `data_scope`。
 *
 * <p>管理端只能设置 {@link #ALL}、{@link #OWN_ORG}、{@link #OWN_ORG_TREE} 三种。
 * {@link #CUSTOM}（历史手工授权元组）和 {@link #NONE}（无业务数据）只用于如实展示存量账号，不能新设。
 *
 * <p>存储：ALL → scope_mode=ALL；本单位/本单位及下级 → scope_mode=ASSIGNED 加 scope_org_rule，
 * 授权元组由 {@code UserDataScopeService} 按所属单位重建；CUSTOM → ASSIGNED 且规则为空；NONE → scope_mode=NONE。
 */
public enum DataScope {
    ALL,
    OWN_ORG,
    OWN_ORG_TREE,
    CUSTOM,
    NONE;

    public static DataScope of(String scopeMode, String scopeOrgRule) {
        if ("ALL".equals(scopeMode)) return ALL;
        if (!"ASSIGNED".equals(scopeMode)) return NONE;
        if ("OWN_ORG".equals(scopeOrgRule)) return OWN_ORG;
        if ("OWN_ORG_TREE".equals(scopeOrgRule)) return OWN_ORG_TREE;
        return CUSTOM;
    }

    /** 管理端可直接设置的取值；其余取值返回 null。 */
    public static DataScope assignable(String value) {
        if (value == null) return null;
        for (DataScope scope : values()) {
            if (scope.isAssignable() && scope.name().equals(value.trim())) return scope;
        }
        return null;
    }

    public boolean isAssignable() {
        return this == ALL || this == OWN_ORG || this == OWN_ORG_TREE;
    }

    public String scopeMode() {
        return switch (this) {
            case ALL -> "ALL";
            case NONE -> "NONE";
            default -> "ASSIGNED";
        };
    }

    /** 按所属单位自动维护授权元组的规则；不按规则维护时为 null。 */
    public String orgRule() {
        return this == OWN_ORG || this == OWN_ORG_TREE ? name() : null;
    }
}
