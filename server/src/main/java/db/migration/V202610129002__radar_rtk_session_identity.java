package db.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** RTK wire frame numbers may restart at zero for each TCP connection. */
public class V202610129002__radar_rtk_session_identity extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        Map<String, List<String>> uniqueColumns = new LinkedHashMap<>();
        try (var query = connection.prepareStatement("""
                SELECT c.constraint_name,k.column_name
                FROM information_schema.table_constraints c
                JOIN information_schema.key_column_usage k
                  ON k.constraint_catalog=c.constraint_catalog AND k.constraint_schema=c.constraint_schema
                 AND k.constraint_name=c.constraint_name AND k.table_name=c.table_name
                WHERE c.table_schema=? AND c.table_name='radar_rtk_sample' AND c.constraint_type='UNIQUE'
                ORDER BY c.constraint_name,k.ordinal_position
                """)) {
            query.setString(1, connection.getSchema());
            try (var rows = query.executeQuery()) {
                while (rows.next()) uniqueColumns.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>()).add(rows.getString(2));
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE radar_rtk_sample ADD COLUMN protocol_message_key VARCHAR(255)");
            for (var constraint : uniqueColumns.entrySet()) {
                if (constraint.getValue().equals(List.of("device_id", "frame_id"))) {
                    statement.execute("ALTER TABLE radar_rtk_sample DROP CONSTRAINT \"" + constraint.getKey().replace("\"", "\"\"") + "\"");
                }
            }
            statement.execute("ALTER TABLE radar_rtk_sample ADD CONSTRAINT uq_radar_rtk_message UNIQUE (device_id,protocol_message_key)");
        }
        // Historical rows keep their original frame IDs and have no invented session identity.
    }
}
