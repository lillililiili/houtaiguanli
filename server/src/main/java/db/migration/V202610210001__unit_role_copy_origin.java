package db.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve copy provenance without renaming roles or touching grants, membership, versions or audit history. */
public class V202610210001__unit_role_copy_origin extends BaseJavaMigration {
    private static final List<String> DDL = List.of(
        "ALTER TABLE app_role ADD COLUMN copied_from_role_code VARCHAR(32)",
        "ALTER TABLE app_role ADD COLUMN copied_from_role_name VARCHAR(64)",
        "ALTER TABLE app_role ADD COLUMN copied_at BIGINT",
        """
        ALTER TABLE app_role ADD CONSTRAINT ck_role_copy_origin CHECK (
          (copied_from_role_code IS NULL AND copied_from_role_name IS NULL AND copied_at IS NULL)
          OR (owner_org_id IS NOT NULL AND copied_from_role_code IS NOT NULL
              AND copied_from_role_name IS NOT NULL AND copied_at IS NOT NULL
              AND TRIM(copied_from_role_code)<>'' AND TRIM(copied_from_role_name)<>''
              AND copied_from_role_code<>role_code AND copied_at>=0))
        """);

    private record Role(String orgId, long createdAt) { }
    private record Source(String code, String name) { }

    @Override public Integer getChecksum() { return 31 * DDL.hashCode() + 1; }

    @Override public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        try (var statement = c.createStatement()) {
            for (String ddl : DDL) statement.execute(ddl);
        }
        Map<String, Role> roles = new HashMap<>();
        try (var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT role_code,owner_org_id,created_at FROM app_role WHERE owner_org_id IS NOT NULL")) {
            while (rows.next()) roles.put(rows.getString(1), new Role(rows.getString(2), rows.getLong(3)));
        }
        if (roles.isEmpty()) return;
        Map<String, Set<Source>> origins = new HashMap<>();
        ObjectMapper json = new ObjectMapper();
        try (var statement = c.createStatement(); var rows = statement.executeQuery("""
                SELECT object_id,occurred_at,detail FROM audit_log
                WHERE action='unit_delegation_updated' AND object_type='organization' AND result='SUCCESS'
                """)) {
            while (rows.next()) {
                JsonNode detail;
                try { detail = json.readTree(rows.getString(3)); }
                catch (JsonProcessingException | IllegalArgumentException invalid) { continue; }
                if (detail == null || !detail.isObject()) continue;
                Map<String, JsonNode> before = members(detail.path("members_before"));
                Map<String, JsonNode> after = members(detail.path("members_after"));
                if (before == null || after == null) continue;
                String org = rows.getString(1);
                if (org == null || org.isBlank()) continue;
                long occurredAt = rows.getLong(2);
                for (var member : after.entrySet()) {
                    JsonNode previous = before.get(member.getKey()), current = member.getValue();
                    if (previous == null || !org.equals(text(previous, "org_id"))
                            || !org.equals(text(current, "org_id"))) continue;
                    String target = text(current, "role_code"), source = text(previous, "role_code");
                    String sourceName = text(previous, "role_name");
                    Role role = roles.get(target);
                    if (role == null || !org.equals(role.orgId()) || role.createdAt() < 0
                            || role.createdAt() > occurredAt || source == null || source.equals(target)
                            || source.length() > 32 || sourceName == null || sourceName.length() > 64) continue;
                    // This transaction only changes a member's role when cloneRole creates its local copy.
                    // Audit snapshots, not current names or role-code conventions, prove the original role.
                    origins.computeIfAbsent(target, key -> new HashSet<>()).add(new Source(source, sourceName));
                }
            }
        }
        try (var update = c.prepareStatement("""
                UPDATE app_role SET copied_from_role_code=?,copied_from_role_name=?,copied_at=?
                WHERE role_code=? AND owner_org_id=? AND copied_from_role_code IS NULL
                """)) {
            for (var entry : origins.entrySet()) {
                if (entry.getValue().size() != 1) continue; // Conflicting or missing history must not be guessed.
                Source source = entry.getValue().iterator().next();
                Role role = roles.get(entry.getKey());
                update.setString(1, source.code()); update.setString(2, source.name());
                update.setLong(3, role.createdAt()); update.setString(4, entry.getKey());
                update.setString(5, role.orgId()); update.executeUpdate();
            }
        }
    }

    private static Map<String, JsonNode> members(JsonNode rows) {
        if (!rows.isArray()) return null;
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode row : rows) {
            String userId = text(row, "user_id");
            if (userId == null || result.putIfAbsent(userId, row) != null) return null;
        }
        return result;
    }

    private static String text(JsonNode row, String key) {
        JsonNode value = row.path(key);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }
}
