package db.migration;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** The original name constraint was database-generated, so discover its exact name on H2 and PostgreSQL. */
public class V202610200002__unit_delegation extends BaseJavaMigration {
    private static final List<String> DDL = List.of(
        "ALTER TABLE app_role ADD COLUMN owner_org_id VARCHAR(36) REFERENCES app_org(org_id)",
        "ALTER TABLE app_role ADD COLUMN name_scope VARCHAR(36) NOT NULL DEFAULT 'GLOBAL'",
        "ALTER TABLE app_role ADD CONSTRAINT ck_role_name_scope CHECK ((owner_org_id IS NULL AND name_scope='GLOBAL') OR (owner_org_id IS NOT NULL AND name_scope=owner_org_id))",
        "ALTER TABLE app_role ADD CONSTRAINT uk_role_scoped_name UNIQUE(name_scope,name)",
        """
        CREATE TABLE app_org_delegation (
          org_id VARCHAR(36) PRIMARY KEY REFERENCES app_org(org_id),
          administrator_user_id VARCHAR(36) UNIQUE REFERENCES app_user(user_id),
          version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
        """,
        """
        CREATE TABLE app_org_permission (
          org_id VARCHAR(36) NOT NULL REFERENCES app_org_delegation(org_id),
          permission_code VARCHAR(64) NOT NULL REFERENCES app_permission(permission_code),
          permission_level VARCHAR(8) NOT NULL CHECK(permission_level IN ('NONE','READ','OP','AUTH')),
          menu_enabled BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(org_id,permission_code))
        """,
        """
        CREATE TABLE app_org_action (
          org_id VARCHAR(36) NOT NULL REFERENCES app_org_delegation(org_id),
          permission_code VARCHAR(64) NOT NULL REFERENCES app_permission(permission_code),
          permission_level VARCHAR(8) NOT NULL CHECK(permission_level='OP'), PRIMARY KEY(org_id,permission_code))
        """,
        """
        CREATE TABLE app_org_delegation_preview (
          token VARCHAR(36) PRIMARY KEY, org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
          actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id), request_hash VARCHAR(64) NOT NULL,
          snapshot_hash VARCHAR(64) NOT NULL, expires_at BIGINT NOT NULL, consumed BOOLEAN NOT NULL DEFAULT FALSE)
        """,
        "CREATE INDEX idx_org_preview_expire ON app_org_delegation_preview(expires_at)",
        "CREATE INDEX idx_role_owner_org ON app_role(owner_org_id,role_code)");

    @Override public Integer getChecksum() { return DDL.hashCode(); }

    @Override public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        String schema = c.getSchema();
        List<String> constraints = new ArrayList<>();
        try (var ps = c.prepareStatement("""
                SELECT tc.constraint_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage ku
                    ON tc.constraint_schema=ku.constraint_schema AND tc.constraint_name=ku.constraint_name
                 WHERE LOWER(tc.table_name)='app_role' AND tc.table_schema=?
                   AND tc.constraint_type='UNIQUE'
                 GROUP BY tc.constraint_name
                HAVING COUNT(*)=1 AND MIN(LOWER(ku.column_name))='name'
                """)) {
            ps.setString(1, schema);
            try (var rs = ps.executeQuery()) { while (rs.next()) constraints.add(rs.getString(1)); }
        }
        if (constraints.size() != 1) throw new IllegalStateException("Expected one app_role.name unique constraint");
        try (var st = c.createStatement()) {
            st.execute("ALTER TABLE app_role DROP CONSTRAINT \"" + constraints.get(0).replace("\"", "\"\"") + "\"");
            for (String sql : DDL) st.execute(sql);
        }
    }
}
