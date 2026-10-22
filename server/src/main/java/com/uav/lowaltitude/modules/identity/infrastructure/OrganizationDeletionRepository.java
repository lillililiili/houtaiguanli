package com.uav.lowaltitude.modules.identity.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import com.uav.lowaltitude.platform.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationDeletionRepository {
    private final JdbcTemplate jdbc;

    public OrganizationDeletionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> lockOrganization(String orgId) {
        var rows = jdbc.queryForList("SELECT * FROM app_org WHERE org_id=? FOR UPDATE", orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> profile(String orgId) {
        var rows = jdbc.queryForList("SELECT * FROM organization_profile WHERE org_id=?", orgId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public int deleteProfile(String orgId) {
        return jdbc.update("DELETE FROM organization_profile WHERE org_id=?", orgId);
    }

    public int deletePreviews(String orgId) {
        return jdbc.update("DELETE FROM app_org_delegation_preview WHERE org_id=?", orgId);
    }

    public int deleteOrganization(String orgId, int version) {
        return jdbc.update("DELETE FROM app_org WHERE org_id=? AND version=?", orgId, version);
    }

    /**
     * Include every declared FK plus conventional organization ownership columns without FKs.
     * Schema metadata is used so a newly added module cannot silently escape deletion protection.
     * Only base tables in the application schema are inspected; archives and aggregate views stay untouched.
     */
    public List<String> blockingReferences(String orgId) {
        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            var inventory = referenceColumns(connection);
            lockUnconstrainedReferenceTables(connection, inventory);
            Set<String> reasons = new LinkedHashSet<>();
            for (Reference column : inventory.references()) {
                String query = "SELECT 1 FROM " + identifier(column.schema()) + "." + identifier(column.table())
                        + " WHERE " + identifier(column.column()) + "=? LIMIT 1";
                try (var statement = connection.prepareStatement(query)) {
                    statement.setString(1, orgId);
                    try (var result = statement.executeQuery()) {
                        if (result.next()) reasons.add(reason(column.table()));
                    }
                }
            }
            return new ArrayList<>(reasons);
        });
    }

    private Inventory referenceColumns(Connection connection) throws SQLException {
        String catalog = connection.getCatalog(), schema = connection.getSchema();
        var metadata = connection.getMetaData();
        Set<String> tables = new LinkedHashSet<>();
        try (var rows = metadata.getTables(catalog, schema, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rows.next()) tables.add(rows.getString("TABLE_NAME"));
        }
        Set<Reference> references = new LinkedHashSet<>();
        references.add(new Reference(schema, "app_org", "parent_id"));
        try (var columns = metadata.getColumns(catalog, schema, "%", "%")) {
            while (columns.next()) {
                String table = columns.getString("TABLE_NAME"), column = columns.getString("COLUMN_NAME");
                if (!tables.contains(table) || ignored(table)) continue;
                if ((column.equals("org_id") || column.endsWith("_org_id")) && !table.equals("app_org")) {
                    references.add(new Reference(schema, table, column));
                }
            }
        }
        Set<Reference> foreignKeys = new LinkedHashSet<>();
        try (var keys = metadata.getExportedKeys(catalog, schema, "app_org")) {
            while (keys.next()) {
                String table = keys.getString("FKTABLE_NAME");
                if (!ignored(table)) foreignKeys.add(new Reference(keys.getString("FKTABLE_SCHEM"),
                        table, keys.getString("FKCOLUMN_NAME")));
            }
        }
        references.addAll(foreignKeys);
        return new Inventory(references, foreignKeys);
    }

    private void lockUnconstrainedReferenceTables(Connection connection, Inventory inventory) throws SQLException {
        if (!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        Set<String> tables = new TreeSet<>();
        for (Reference reference : inventory.references()) {
            if (!inventory.foreignKeys().contains(reference)) {
                tables.add(identifier(reference.schema()) + "." + identifier(reference.table()));
            }
        }
        if (tables.isEmpty()) return;
        // Existing FK writes serialize on app_org's row lock. For historical ownership columns without
        // FKs, prevent writes during this check/delete window only; NOWAIT avoids blocking live work.
        // This is not a substitute for validating ownership in every later business write.
        try (var statement = connection.createStatement()) {
            statement.execute("LOCK TABLE " + String.join(",", tables) + " IN SHARE MODE NOWAIT");
        } catch (SQLException ex) {
            if ("55P03".equals(ex.getSQLState())) throw new ApiException(HttpStatus.CONFLICT,
                    "ORG_DELETE_BUSY", "相关业务正在更新，暂不能删除单位，请稍后刷新重试");
            throw ex;
        }
    }

    private static boolean ignored(String table) {
        // The profile is the directory entry's own details and is frozen into the deletion audit.
        // Preview tokens are temporary validation credentials, not business history. Deletion invalidates them;
        // a later apply still has to find and lock the (now absent) organization.
        return table.equals("organization_profile") || table.equals("app_org_delegation_preview");
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("Unexpected organization reference identifier");
        }
        return "\"" + value + "\"";
    }

    private static String reason(String table) {
        return switch (table.toLowerCase(Locale.ROOT)) {
            case "app_org" -> "下级单位";
            case "app_user" -> "人员或人员历史记录";
            case "app_role" -> "单位角色";
            case "app_org_delegation", "app_org_permission", "app_org_action", "app_org_management_permission" -> "单位授权配置";
            case "app_user_data_scope" -> "人员数据范围授权";
            case "pending_user_registration" -> "人员登记记录";
            case "device_business_scope", "mqtt_broker_connection", "eo_edge_binding", "commission_task" -> "设备或接入调测记录";
            case "business_contact" -> "业务联系人";
            case "plan_source_binding" -> "任务来源关联";
            case "notification_setting" -> "通知配置";
            default -> "业务或历史记录";
        };
    }

    private record Reference(String schema, String table, String column) { }
    private record Inventory(Set<Reference> references, Set<Reference> foreignKeys) { }
}
