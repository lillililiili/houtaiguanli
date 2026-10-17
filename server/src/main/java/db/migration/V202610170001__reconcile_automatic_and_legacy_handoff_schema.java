package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Adds the other branch's structures, without changing original SQL, history rows or existing decisions. */
public class V202610170001__reconcile_automatic_and_legacy_handoff_schema extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!column(connection, "simulator_observation_source", "origin_ops_device_id"))
            execute(connection, "automatic/V202610160001__normalized_observation_origin_device.sql");
        if (!table(connection, "countermeasure_start_attempt"))
            execute(connection, "automatic/V202610160002__countermeasure_start_attempt_evidence.sql");
        boolean policy = table(connection, "handoff_followup_policy");
        boolean decision = table(connection, "handoff_notification_decision");
        if (policy != decision) throw new IllegalStateException("Incomplete legacy handoff schema; no history repair is permitted");
        if (!policy) {
            execute(connection, "manual/V202610160001__uav_followup_notification_decision.sql");
            execute(connection, "manual/V202610160002__handoff_internal_recipient_null_guard.sql");
        }
    }
    private static void execute(Connection connection, String script) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/lineage/" + script));
    }
    private static boolean table(Connection connection, String table) throws SQLException {
        try (var rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), table, new String[]{"TABLE"})) {
            while (rows.next()) if (table.equals(rows.getString("TABLE_NAME"))) return true;
        }
        return false;
    }
    private static boolean column(Connection connection, String table, String column) throws SQLException {
        try (var rows = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), table, column)) {
            while (rows.next()) if (table.equals(rows.getString("TABLE_NAME")) && column.equals(rows.getString("COLUMN_NAME"))) return true;
        }
        return false;
    }
}
