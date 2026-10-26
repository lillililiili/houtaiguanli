package com.uav.lowaltitude.modules.identity.infrastructure;

/** One current appointment predicate for login, business grants and restricted management. */
public final class UnitAdministratorSql {
    private UnitAdministratorSql() { }
    public static final String SUBJECT = """
        FROM app_org_delegation d
        JOIN app_org o ON o.org_id=d.org_id AND o.enabled=TRUE AND o.deleted_at IS NULL
        JOIN app_user u ON u.user_id=d.administrator_user_id AND u.org_id=d.org_id
        JOIN app_role r ON r.role_code=u.role_code AND (r.owner_org_id IS NULL OR r.owner_org_id=d.org_id)
        """;
    public static final String VALID = """
        u.status='ACTIVE' AND u.scope_mode='ASSIGNED' AND u.scope_org_rule='OWN_ORG'
        AND u.role_code NOT IN ('ROLE-ADMIN','ROLE-BACKEND','ROLE-AUTOMATION')
        AND u.user_id<>'automation-rule-runner'
        """;
}
