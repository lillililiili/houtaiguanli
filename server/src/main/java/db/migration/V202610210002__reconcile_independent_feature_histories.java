package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Apply only the missing branch after both preserved histories, without fabricating old history rows. */
public class V202610210002__reconcile_independent_feature_histories extends BaseJavaMigration {
    @Override public Integer getChecksum() {
        return List.of(1, 351620372, 529132896, 1496639541, 12268958, -1287599436, -367654150,
                -563788088, -891790966, -982526904, -852369259).hashCode();
    }

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!column(connection, "disposal_authorization", "countermeasure_mode"))
            execute(connection, "features-remote/V202610200001__disposal_countermeasure_mode.sql");
        if (!column(connection, "airspace", "deleted_at"))
            execute(connection, "features-remote/V202610200002__local_airspace_lifecycle.sql");

        if (!table(connection, "app_org_delegation")) {
            execute(connection, "features-local/V202610200001__remove_vendor_name_from_display_data.sql");
            new V202610200002__unit_delegation().migrate(context);
        }
        if (!table(connection, "prediction_snapshot")) {
            execute(connection, "features-local/V202610200003__trajectory_prediction.sql");
            if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                // This original script contains PostgreSQL dollar-quoted trigger bodies.
                var resource = new ClassPathResource("db/lineage/features-local-postgresql/"
                        + "V202610200004__prediction_evidence_and_notifications.sql");
                try (var input = resource.getInputStream(); var statement = connection.createStatement()) {
                    statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        if (!column(connection, "flight_plan", "filing_coordinate_system"))
            execute(connection, "features-local/V202610200005__flight_plan_filing_coordinate_system.sql");
        if (!column(connection, "advisory_voice_recording", "purpose"))
            execute(connection, "features-local/V202610200006__prediction_voice_recording_purpose.sql");
        if (!table(connection, "app_org_management_permission"))
            execute(connection, "features-local/V202610200007__unit_management_permissions.sql");
        if (!column(connection, "app_role", "copied_from_role_code"))
            new V202610210001__unit_role_copy_origin().migrate(context);
    }

    private static void execute(Connection connection, String script) {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("db/lineage/" + script), StandardCharsets.UTF_8));
    }

    private static boolean table(Connection connection, String table) throws SQLException {
        try (var rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), table,
                new String[]{"TABLE", "BASE TABLE"})) {
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
